package dev.mempatch.ledgerqueue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.sqlite.SQLiteConfig;

/** Used only before Ledger opens its default datasource; never unlinks a live connection. */
public final class LedgerSmallDatabaseRecovery {
    public static final long LIMIT_BYTES = 10_000;
    private static final String[] SUFFIXES = {"", "-wal", "-shm", "-journal"};
    private static final Set<String> TABLES = Set.of(
        "players", "actions", "actionidentifiers", "worlds", "objectidentifiers", "sources");

    private LedgerSmallDatabaseRecovery() {}

    /** Returns the backup directory when reset, otherwise null. Ledger builds the new schema itself. */
    public static synchronized Path beforeOpen(Path suppliedPath) {
        Path path = suppliedPath.toAbsolutePath().normalize();
        try {
            List<Snapshot> original = snapshot(path);
            if (original == null || !damaged(path)) return null;
            // Lock SQLite's pending/reserved/shared byte range. No local Ledger
            // connections exist here; an external active SQLite connection vetoes reset.
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock(0x40000000L, 512L, false)) {
                if (lock == null || !same(original, snapshot(path))) return null;
                Path backup = Files.createTempDirectory(path.getParent(), "ledger-reset-backup-");
                List<Snapshot> moved = new ArrayList<>();
                try {
                    for (Snapshot file : original) {
                        Files.move(file.path, backup.resolve(file.path.getFileName()));
                        moved.add(file);
                    }
                } catch (IOException failure) {
                    // Refuse startup if rollback is incomplete; never initialize over
                    // a partially moved database/WAL set.
                    for (int i = moved.size() - 1; i >= 0; i--) {
                        Snapshot file = moved.get(i);
                        try { Files.move(backup.resolve(file.path.getFileName()), file.path); }
                        catch (IOException restore) { failure.addSuppressed(restore); }
                    }
                    throw new IllegalStateException("Ledger reset backup failed; inspect " + backup, failure);
                }
                LedgerQueueGuardConfig.LOGGER.warn(
                    "[LedgerRecovery] Reset damaged SQLite database smaller than {} bytes: {}. Previous files preserved at {}. Ledger will recreate its schema.",
                    LIMIT_BYTES, path, backup);
                return backup;
            }
        } catch (IOException | SQLException | java.nio.channels.OverlappingFileLockException failure) {
            LedgerQueueGuardConfig.LOGGER.warn(
                "[LedgerRecovery] Could not safely reset {}; left database files in place: {}", path, failure.toString());
            return null;
        }
    }

    private static List<Snapshot> snapshot(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        List<Snapshot> files = new ArrayList<>();
        long dataBytes = 0;
        for (String suffix : SUFFIXES) {
            Path file = Path.of(path + suffix);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isSymbolicLink()) return null;
            if (!suffix.equals("-shm")) {
                dataBytes += attrs.size();
                if (dataBytes >= LIMIT_BYTES) return null;
            } else if (attrs.size() > 1_048_576) return null;
            byte[] bytes;
            try (var input = Files.newInputStream(file)) {
                bytes = input.readNBytes(suffix.equals("-shm") ? 1_048_577 : (int) LIMIT_BYTES);
            }
            if (bytes.length != attrs.size()) return null;
            files.add(new Snapshot(file, attrs.fileKey(), attrs.lastModifiedTime(), bytes));
        }
        return files;
    }

    private static boolean same(List<Snapshot> a, List<Snapshot> b) {
        if (b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Snapshot x = a.get(i), y = b.get(i);
            if (!x.path.equals(y.path) || !java.util.Objects.equals(x.key, y.key)
                || !x.modified.equals(y.modified) || !Arrays.equals(x.bytes, y.bytes)) return false;
        }
        return true;
    }

    private static boolean damaged(Path path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(500);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA quick_check")) {
                boolean result = false;
                while (rows.next()) {
                    result = true;
                    if (!"ok".equalsIgnoreCase(rows.getString(1))) return true;
                }
                if (!result) return false;
            }
            Set<String> found = new HashSet<>();
            try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rows.next()) found.add(rows.getString(1).toLowerCase(java.util.Locale.ROOT));
            }
            if (!found.containsAll(TABLES)) return true;
            String[] checks = {
                "SELECT id,player_id,player_name,first_join,last_join FROM players LIMIT 0",
                "SELECT id,action_id,time,x,y,z,world_id,object_id,old_object_id,block_state,old_block_state,source,player_id,extra_data,rolled_back FROM actions LIMIT 0",
                "SELECT id,action_identifier FROM ActionIdentifiers LIMIT 0",
                "SELECT id,identifier FROM worlds LIMIT 0",
                "SELECT id,identifier FROM ObjectIdentifiers LIMIT 0",
                "SELECT id,name FROM sources LIMIT 0"
            };
            for (String query : checks) {
                try (var ignored = statement.executeQuery(query)) { /* Validate Ledger's columns. */ }
            }
            return false;
        } catch (SQLException failure) {
            // Permission, busy/locked, disk-full and generic I/O errors are not
            // evidence of corruption and must never authorize reset.
            int code = failure.getErrorCode() & 0xff;
            if (code == 11 || code == 26) return true; // SQLITE_CORRUPT, SQLITE_NOTADB
            String message = String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (code == 1 && (message.contains("no such table:") || message.contains("no such column:"))) return true;
            throw failure;
        }
    }

    private record Snapshot(Path path, Object key, java.nio.file.attribute.FileTime modified, byte[] bytes) {}
}
