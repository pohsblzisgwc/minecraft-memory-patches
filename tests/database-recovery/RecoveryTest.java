import dev.mempatch.ledgerqueue.LedgerSmallDatabaseRecovery;
import java.nio.file.*;
import java.sql.*;
import java.util.Arrays;

public final class RecoveryTest {
    private static final String[] TABLES = {"players", "actions", "ActionIdentifiers", "worlds", "ObjectIdentifiers", "sources"};
    private static int passed;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ledger-recovery-tests-");
        for (int size : new int[]{0, 1, 9999, 10000, 10001, 10240}) {
            Path path = root.resolve("size-" + size + ".sqlite");
            byte[] data = new byte[size]; Arrays.fill(data, (byte) 'X'); Files.write(path, data);
            Path backup = LedgerSmallDatabaseRecovery.beforeOpen(path);
            check((backup != null) == (size < 10000), "strict threshold size=" + size);
            check(Arrays.equals(data, Files.readAllBytes(backup != null ? backup.resolve(path.getFileName()) : path)), "content preserved");
        }
        Path absent = root.resolve("absent.sqlite");
        check(LedgerSmallDatabaseRecovery.beforeOpen(absent) == null && !Files.exists(absent), "absent file untouched");
        Path healthy = root.resolve("healthy.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + healthy); var s = c.createStatement()) {
            s.execute("PRAGMA page_size=512");
            String[] defs = {
                "players(id INTEGER,player_id BLOB,player_name TEXT,first_join TEXT,last_join TEXT)",
                "actions(id INTEGER,action_id INT,time TEXT,x INT,y INT,z INT,world_id INT,object_id INT,old_object_id INT,block_state TEXT,old_block_state TEXT,source INT,player_id INT,extra_data TEXT,rolled_back INT)",
                "ActionIdentifiers(id INTEGER,action_identifier TEXT)",
                "worlds(id INTEGER,identifier TEXT)", "ObjectIdentifiers(id INTEGER,identifier TEXT)", "sources(id INTEGER,name TEXT)"
            };
            for (String def : defs) s.execute("CREATE TABLE " + def);
            s.execute("INSERT INTO actions(id) VALUES(42)");
        }
        check(Files.size(healthy) < 10000, "healthy fixture must be small");
        byte[] valid = Files.readAllBytes(healthy);
        check(LedgerSmallDatabaseRecovery.beforeOpen(healthy) == null, "healthy small DB not reset");
        check(Arrays.equals(valid, Files.readAllBytes(healthy)), "healthy DB unmodified");
        Path incomplete = root.resolve("missing-tables.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + incomplete); var s = c.createStatement()) {
            s.execute("CREATE TABLE players(id INTEGER)");
        }
        check(LedgerSmallDatabaseRecovery.beforeOpen(incomplete) != null, "confirmed missing tables recover");
        Path columns = root.resolve("missing-columns.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + columns); var s = c.createStatement()) {
            s.execute("PRAGMA page_size=512");
            for (String name : TABLES) s.execute("CREATE TABLE " + name + "(id INTEGER)");
        }
        check(LedgerSmallDatabaseRecovery.beforeOpen(columns) != null, "confirmed missing columns recover");
        Path wal = root.resolve("large-wal.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + wal); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA wal_autocheckpoint=0");
            s.execute("CREATE TABLE retained(data BLOB)"); s.execute("INSERT INTO retained VALUES(zeroblob(20000))");
            check(Files.size(wal) < 10000 && Files.size(Path.of(wal + "-wal")) >= 10000, "WAL fixture");
            check(LedgerSmallDatabaseRecovery.beforeOpen(wal) == null, "WAL contents prevent destructive reset");
            try (var rs = s.executeQuery("SELECT length(data) FROM retained")) { check(rs.next() && rs.getInt(1) == 20000, "WAL data preserved"); }
        }
        Path sides = root.resolve("sidecars.sqlite"); Files.write(sides, new byte[]{'X'});
        Files.write(Path.of(sides + "-wal"), new byte[9999]);
        check(LedgerSmallDatabaseRecovery.beforeOpen(sides) == null, "aggregate threshold including WAL");
        Path bundle = root.resolve("bundle.sqlite"); Files.write(bundle, new byte[]{'X'});
        Files.write(Path.of(bundle + "-journal"), new byte[]{1,2,3});
        Files.write(Path.of(bundle + "-shm"), new byte[]{4,5,6});
        Path bundleBackup = LedgerSmallDatabaseRecovery.beforeOpen(bundle);
        check(bundleBackup != null && !Files.exists(bundle) && !Files.exists(Path.of(bundle + "-journal")), "sidecar bundle moved");
        check(Arrays.equals(new byte[]{1,2,3}, Files.readAllBytes(bundleBackup.resolve("bundle.sqlite-journal")))
            && Arrays.equals(new byte[]{4,5,6}, Files.readAllBytes(bundleBackup.resolve("bundle.sqlite-shm"))), "sidecar backup bytes preserved");
        Path link = root.resolve("link.sqlite"); Files.createSymbolicLink(link, healthy);
        check(LedgerSmallDatabaseRecovery.beforeOpen(link) == null && Files.isSymbolicLink(link), "symlink refused");
        Path blocked = root.resolve("blocked.sqlite"); Files.write(blocked, new byte[]{'X'});
        Path target = root.resolve("journal-target"); Files.write(target, new byte[]{'Y'});
        Files.createSymbolicLink(Path.of(blocked + "-journal"), target);
        check(LedgerSmallDatabaseRecovery.beforeOpen(blocked) == null && Files.exists(blocked), "sidecar symlink refused");
        // Another process holding SQLite's lock-byte range must veto a reset.
        Path locked = root.resolve("locked.sqlite"); Files.write(locked, new byte[]{'X'});
        Process keeper = new ProcessBuilder("python3", "-c", "import fcntl,sys,time; f=open(sys.argv[1],'r+b'); fcntl.lockf(f,fcntl.LOCK_EX,512,0x40000000); print('locked',flush=True); sys.stdin.read()", locked.toString()).start();
        try {
            check(new java.io.BufferedReader(new java.io.InputStreamReader(keeper.getInputStream())).readLine().equals("locked"), "lock ready");
            check(LedgerSmallDatabaseRecovery.beforeOpen(locked) == null && Files.exists(locked), "external lock vetoes reset");
        } finally { keeper.getOutputStream().close(); keeper.waitFor(); }
        System.out.println("ALL_DATABASE_RECOVERY_TESTS_PASSED assertions=" + passed + " fixtures=" + root);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
}
