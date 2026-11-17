package dev.mempatch.verify;

import carpet.logging.LoggerRegistry;
import carpet.patches.EntityPlayerMPFake;
import carpet.patches.FakeClientConnection;
import carpettisaddition.helpers.carpet.tweaks.logger.projectile.TrajectoryLoggerUtil;
import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.ChunkLoadStatePublisher;
import cn.net.rms.confluxmap.server.ChunkLoadStateAccess;
import cn.net.rms.confluxmap.server.ChunkLoadStateService;
import com.github.quiltservertools.ledger.actions.BlockChangeActionType;
import com.github.quiltservertools.ledger.database.ActionQueueService;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import kotlinx.coroutines.Job;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.impl.networking.GlobalReceiverRegistry;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VerifyHarnessMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("patch_verify_harness");
    private static final Set<UUID> DISCONNECT_EVENTS = ConcurrentHashMap.newKeySet();

    private int phase = 0;
    private int waitTicks = 0;
    private boolean done = false;

    private EntityPlayerMPFake fakePlayer;
    private UUID fakeUuid;
    private FakeClientConnection fakeConn;
    private int baselineAddons;
    private MapItemSavedData mapData;
    private ItemFrame itemFrame;
    private ItemStack mapStack;

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DISCONNECT_EVENTS.add(handler.getPlayer().getUUID());
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.phase = 1;
            this.waitTicks = 0;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (this.done || this.phase == 0) {
                return;
            }
            try {
                this.step(server);
            } catch (Throwable t) {
                LOGGER.error("VERIFY FAILED in phase {}: {}", this.phase, t.getMessage(), t);
                this.done = true;
                Runtime.getRuntime().halt(42);
            }
        });
    }

    private void step(MinecraftServer server) throws Exception {
        ServerLevel overworld = server.overworld();
        if (this.phase == 1) {
            if (++this.waitTicks < 3) {
                return;
            }
            this.baselineAddons = trackedAddonsCount();
            this.fakeUuid = UUID.nameUUIDFromBytes("OfflinePlayer:VerifyBot".getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(this.fakeUuid, "VerifyBot");
            this.fakeConn = new FakeClientConnection(PacketFlow.SERVERBOUND);
            this.fakePlayer = EntityPlayerMPFake.respawnFake(server, overworld, profile, ClientInformation.createDefault());
            server.getPlayerList().placeNewPlayer(
                this.fakeConn,
                this.fakePlayer,
                new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false)
            );

            int afterSpawnAddons = trackedAddonsCount();
            assertTrue(
                afterSpawnAddons == this.baselineAddons + 1,
                "Expected trackedAddons to increase by 1 after fake player join: before=" + this.baselineAddons + " after=" + afterSpawnAddons
            );

            // Prepare MapItemSavedData + ItemFrame (triggers Lithium entity.framed_maps fast path)
            this.mapData = MapItemSavedData.createFresh(0.0, 0.0, (byte) 0, true, false, overworld.dimension());
            this.itemFrame = new ItemFrame(overworld, new BlockPos(0, 80, 0), Direction.NORTH);
            this.mapStack = new ItemStack(Items.FILLED_MAP);
            this.mapData.tickCarriedBy(this.fakePlayer, this.mapStack, this.itemFrame);
            assertTrue(mapCarriedByList(this.mapData).size() == 1, "Expected carriedBy size == 1 before disconnect");
            assertTrue(mapCarriedByMap(this.mapData).size() == 1, "Expected carriedByPlayers size == 1 before disconnect");

            // Kill fake player
            this.fakePlayer.kill(Component.literal("Verify disconnect"));
            this.phase = 2;
            this.waitTicks = 0;
            return;
        }

        if (this.phase == 2) {
            if (++this.waitTicks < 3) {
                return;
            }
            assertTrue(this.fakePlayer.isRemoved(), "Expected fakePlayer.isRemoved() == true after kill");

            // Verify Patch 1: MapItemSavedData evicted dead fakePlayer immediately!
            int mapListSize = mapCarriedByList(this.mapData).size();
            int mapMapSize = mapCarriedByMap(this.mapData).size();
            assertTrue(mapListSize == 0, "Patch 1 failed: carriedBy size=" + mapListSize);
            assertTrue(mapMapSize == 0, "Patch 1 failed: carriedByPlayers size=" + mapMapSize);

            // Re-ticking with removed player and placedInFrame != null (Lithium path) must not re-leak!
            this.mapData.tickCarriedBy(this.fakePlayer, this.mapStack, this.itemFrame);
            assertTrue(mapCarriedByList(this.mapData).isEmpty(), "Patch 1 failed: tickCarriedBy re-added removed player");
            LOGGER.info("PASS [1/5] patch-map-player-leak verified with Lithium framed_maps active");

            // Verify Patch 2: GlobalReceiverRegistry & ServerPlayConnectionEvents.DISCONNECT & EmbeddedChannel
            int afterKillAddons = trackedAddonsCount();
            assertTrue(
                afterKillAddons == this.baselineAddons,
                "Patch 2 failed: trackedAddons leaked! baseline=" + this.baselineAddons + " afterKill=" + afterKillAddons
            );
            assertTrue(
                DISCONNECT_EVENTS.contains(this.fakeUuid),
                "Patch 2 failed: ServerPlayConnectionEvents.DISCONNECT did not fire for fake player"
            );
            LOGGER.info("PASS [2/5] patch-fakeplayer-network-leak verified: trackedAddons={}", afterKillAddons);

            // Verify Patch 3: Carpet-TIS TrajectoryLoggerUtil ThreadLocal cleanup
            boolean prevProjLogger = LoggerRegistry.__projectiles;
            try {
                LoggerRegistry.__projectiles = false;
                new Arrow(overworld, 0.0, 90.0, 0.0, new ItemStack(Items.ARROW), null);
                assertTrue(TrajectoryLoggerUtil.currentEntity.get() == null, "Patch 3 failed: Arrow left ThreadLocal when logger disabled");
                new FireworkRocketEntity(overworld, 0.0, 90.0, 0.0, ItemStack.EMPTY);
                assertTrue(TrajectoryLoggerUtil.currentEntity.get() == null, "Patch 3 failed: Firework left ThreadLocal when logger disabled");

                AtomicReference<Entity> workerLeak = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    new Arrow(overworld, 0.0, 90.0, 0.0, new ItemStack(Items.ARROW), null);
                    workerLeak.set(TrajectoryLoggerUtil.currentEntity.get());
                }, "Verify-Async-Projectile");
                AtomicReference<Throwable> workerError = new AtomicReference<>();
                worker.setUncaughtExceptionHandler((t, e) -> workerError.set(e));
                worker.start();
                worker.join(5000);
                assertTrue(!worker.isAlive() && workerError.get() == null, "Projectile worker failed: " + workerError.get());
                assertTrue(workerLeak.get() == null, "Patch 3 failed on worker thread: " + workerLeak.get());

                LoggerRegistry.__projectiles = true;
                Arrow loggedArrow = new Arrow(overworld, 0.0, 90.0, 0.0, new ItemStack(Items.ARROW), null);
                assertTrue(TrajectoryLoggerUtil.currentEntity.get() == null, "Patch 3 failed: Carpet Arrow logger should consume currentEntity");
                assertLoggerEntity(loggedArrow);
                FireworkRocketEntity fw = new FireworkRocketEntity(overworld, 0.0, 90.0, 0.0, ItemStack.EMPTY);
                overworld.addFreshEntity(fw);
                assertTrue(TrajectoryLoggerUtil.currentEntity.get() == null, "Patch 3 failed: Firework addFreshEntity left orphaned ThreadLocal");
                fw.tick();
                loggedArrow.discard();
                fw.discard();
                assertTrue(TrajectoryLoggerUtil.currentEntity.get() == null, "Patch 3 failed: Projectile.tick left ThreadLocal");
            } finally {
                LoggerRegistry.__projectiles = prevProjLogger;
                TrajectoryLoggerUtil.currentEntity.remove();
            }
            LOGGER.info("PASS [3/5] patch-tis-projectile-threadlocal verified on main + worker threads");

            // Verify Patch 4: ConfluxMap ChunkLoadStateService zero-churn ring & parity
            verifyConfluxMap(server, overworld);
            LOGGER.info("PASS [4/5] patch-confluxmap-chunkstate-churn verified: 0 LinkedHashSet entries & exact state parity");

            // Verify Patch 5: Ledger ActionQueueService bounded queue
            verifyLedgerQueueGuard();
            LOGGER.info("PASS [5/5] patch-ledger-queue-guard verified: bounded queue cap enforced");

            auditThreadLocal(overworld);
            auditConcurrentQueue();
            verifyLoggerContext(overworld);
            verifyDisconnectFailure(server, overworld);
            LOGGER.info("ALL_5_PATCH_CHECKS_PASSED");
            verifyDatabaseRecovery();
            LOGGER.info("ALL_REGRESSION_CHECKS_PASSED");
            this.done = true;
            server.halt(false);
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyConfluxMap(MinecraftServer server, ServerLevel overworld) throws Exception {
        ChunkLoadStateService service = new ChunkLoadStateService();
        ServerChunkCache scc = overworld.getChunkSource();
        int loadedCount = 0;
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                LevelChunk chunk = overworld.getChunk(cx, cz);
                service.onChunkLoad(overworld, chunk);
                loadedCount++;
            }
        }
        assertTrue(loadedCount == 25, "Expected 25 loaded chunks");

        for (int t = 0; t < 20; t++) {
            service.tick(server);
        }

        Field lcqField = ChunkLoadStateService.class.getDeclaredField("levelCheckQueue");
        lcqField.setAccessible(true);
        Set<?> lcq = (Set<?>) lcqField.get(service);
        assertTrue(lcq.isEmpty(), "Patch 4 failed: legacy levelCheckQueue should stay empty (size=" + lcq.size() + ")");

        Field lcField = ChunkLoadStateService.class.getDeclaredField("loadedChunks");
        lcField.setAccessible(true);
        Map<?, ?> lc = (Map<?, ?>) lcField.get(service);
        assertTrue(lc.isEmpty(), "Patch 4 failed: legacy loadedChunks should stay empty (size=" + lc.size() + ")");

        Field pubField = ChunkLoadStateService.class.getDeclaredField("publisher");
        pubField.setAccessible(true);
        ChunkLoadStatePublisher pub = (ChunkLoadStatePublisher) pubField.get(service);

        Field statesField = ChunkLoadStatePublisher.class.getDeclaredField("states");
        statesField.setAccessible(true);
        Map<Integer, Map<?, ?>> states = (Map<Integer, Map<?, ?>>) statesField.get(pub);
        Map<?, ?> dim0 = states.get(0);
        assertTrue(dim0 != null && dim0.size() == 25, "Patch 4 failed: expected 25 published chunk states, got " + (dim0 == null ? "null" : dim0.size()));

        assertStateParity(overworld, dim0);
        // Snapshot and delta delivery must work for multiple subscribers.
        java.util.List<cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C> deliveries = new java.util.ArrayList<>();
        for (int id = 1; id <= 2; id++) {
            assertTrue(service.subscribe(server, UUID.randomUUID(),
                new cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S(id, 0, true, -2, -2, 2, 2),
                deliveries::add), "subscribe failed");
        }
        service.tick(server);
        assertTrue(deliveries.size() == 2, "Both subscribers must receive snapshots");
        for (var delta : deliveries) {
            assertTrue(delta.entries().size() == 25, "Incomplete subscriber snapshot");
            for (var entry : delta.entries()) assertPublishedEntry(overworld, entry);
        }
        deliveries.clear();
        // Seed an older published ticket and cache, then verify the current holder
        // replaces it. C2ME holders intentionally forbid setTicketLevel().
        Field ringField = java.util.Arrays.stream(ChunkLoadStateService.class.getDeclaredFields())
            .filter(f -> f.getName().contains("ring$pcmc")).findFirst().orElseThrow();
        ringField.setAccessible(true);
        List<?> ring = (List<?>) ringField.get(service);
        int currentTicket = ChunkLoadStateAccess.read(overworld, new ChunkPos(0, 0)).orElseThrow().level();
        int previousTicket = currentTicket == 31 ? 32 : 31;
        for (Object entry : ring) {
            if (entry.getClass().getField("chunkX").getInt(entry) == 0
                && entry.getClass().getField("chunkZ").getInt(entry) == 0) {
                entry.getClass().getField("lastTicketLevel").setInt(entry, previousTicket);
            }
        }
        pub.update(0, 0, 0, previousTicket, ChunkLoadBand.fromTicketLevel(previousTicket));
        service.tick(server);
        assertStateParity(overworld, dim0);
        assertTrue(deliveries.size() == 2, "Ticket refresh must reach both subscribers");
        for (var delta : deliveries) {
            assertTrue(delta.entries().size() == 1 && delta.entries().getFirst().level() == currentTicket,
                "Incorrect changed ticket delivery");
        }

        // More than one tick's budget, without generating thousands of world chunks.
        for (int x = 100; x < 2200; x++) {
            service.onChunkLoad(overworld, new LevelChunk(overworld, new ChunkPos(x, 100)));
        }
        assertTrue(ring.size() == 2125, "Unexpected ring size");
        for (Object entry : ring) entry.getClass().getField("publishedState").setByte(entry, (byte) 0);
        service.tick(server);
        long pending = 0;
        for (Object entry : ring) if (entry.getClass().getField("publishedState").getByte(entry) == 0) pending++;
        assertTrue(pending == 77, "Per-tick check budget violated: " + pending);
        service.tick(server);
        for (Object entry : ring) assertTrue(entry.getClass().getField("publishedState").getByte(entry) != 0,
            "Ring cursor skipped a chunk");
        assertStateParity(overworld, dim0);
        for (int x = 100; x < 2200; x++) {
            service.onChunkUnload(overworld, new LevelChunk(overworld, new ChunkPos(x, 100)));
        }
        assertTrue(ring.size() == 25, "Ring removal leaked entries");

        LevelChunk sample = overworld.getChunk(0, 0);
        service.onChunkUnload(overworld, sample);
        assertTrue(dim0.size() == 24, "Patch 4 failed: expected 24 states after unload, got " + dim0.size());
        service.onChunkLoad(overworld, sample);
        assertTrue(dim0.size() == 25, "Reload did not republish state");
        assertStateParity(overworld, dim0);
        // Empty dimension buckets must not retain the world.
        LevelChunk netherChunk = new LevelChunk(server.getLevel(net.minecraft.world.level.Level.NETHER), new ChunkPos(100, 100));
        service.onChunkLoad(server.getLevel(net.minecraft.world.level.Level.NETHER), netherChunk);
        service.onChunkUnload(server.getLevel(net.minecraft.world.level.Level.NETHER), netherChunk);
        Field dimsField = java.util.Arrays.stream(ChunkLoadStateService.class.getDeclaredFields())
            .filter(f -> f.getName().contains("dims$pcmc")).findFirst().orElseThrow();
        dimsField.setAccessible(true);
        assertTrue(((Map<?, ?>) dimsField.get(service)).size() == 1, "Empty dimension retained");
        service.clear();
        assertTrue(ring.isEmpty() && ((Map<?, ?>) dimsField.get(service)).isEmpty(), "clear retained indexes");
        assertTrue(states.isEmpty(), "Patch 4 failed: expected empty publisher after clear()");
    }

    private static void verifyLedgerQueueGuard() throws Exception {
        int max = Integer.getInteger("ledger.queueGuard.maxSize", 32768);
        assertTrue(max == 25, "Expected -Dledger.queueGuard.maxSize=25 in test run, got " + max);

        Field jobField = ActionQueueService.class.getDeclaredField("job");
        jobField.setAccessible(true);
        Job job = (Job) jobField.get(null);
        if (job != null) {
            job.cancel(null);
        }
        Field qField = ActionQueueService.class.getDeclaredField("queue");
        qField.setAccessible(true);
        java.util.concurrent.LinkedBlockingQueue<?> q = (java.util.concurrent.LinkedBlockingQueue<?>) qField.get(null);
        q.clear();

        int accepted = 0;
        int rejected = 0;
        var actions = new java.util.ArrayList<BlockChangeActionType>();
        for (int i = 0; i < 60; i++) {
            var action = new BlockChangeActionType();
            actions.add(action);
            if (ActionQueueService.INSTANCE.addToQueue(action)) {
                accepted++;
            } else {
                rejected++;
            }
        }
        boolean dropOldest = Boolean.getBoolean("ledger.queueGuard.dropOldest");
        assertTrue(accepted == (dropOldest ? 60 : 25), "Unexpected accepted count: " + accepted);
        assertTrue(rejected == (dropOldest ? 0 : 35), "Unexpected rejected count: " + rejected);
        assertTrue(ActionQueueService.INSTANCE.getSize() == 25, "Patch 5 failed: queue size=" + ActionQueueService.INSTANCE.getSize());
        Object[] queued = q.toArray();
        for (int i = 0; i < 25; i++) assertTrue(queued[i] == actions.get(i + (dropOldest ? 35 : 0)), "Drop policy retained wrong action");
        q.clear();
    }

    private static void auditThreadLocal(ServerLevel level) throws Exception {
        LoggerRegistry.__projectiles = true;
        AtomicReference<FireworkRocketEntity> rocket = new AtomicReference<>();
        AtomicReference<Entity> residual = new AtomicReference<>();
        java.util.concurrent.CountDownLatch constructed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch added = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                rocket.set(new FireworkRocketEntity(level, 0, 90, 0, ItemStack.EMPTY));
                constructed.countDown();
                if (!added.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("timeout");
                residual.set(TrajectoryLoggerUtil.currentEntity.get());
            } catch (Throwable e) { error.set(e); constructed.countDown(); }
            finally { TrajectoryLoggerUtil.currentEntity.remove(); }
        }, "Audit-Persistent-Worker");
        worker.start();
        if (!constructed.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("worker timeout");
        level.addFreshEntity(rocket.get());
        added.countDown();
        worker.join(10000);
        if (error.get() != null) throw new AssertionError(error.get());
        assertTrue(!worker.isAlive(), "Worker failed to finish");
        assertTrue(residual.get() == null, "Cross-thread construction left context on worker");
        LOGGER.info("PASS cross-thread projectile context: no worker residual");
        rocket.get().discard();
        LoggerRegistry.__projectiles = false;
    }

    private static void auditConcurrentQueue() throws Exception {
        Field qf = ActionQueueService.class.getDeclaredField("queue"); qf.setAccessible(true);
        java.util.concurrent.LinkedBlockingQueue<?> q = (java.util.concurrent.LinkedBlockingQueue<?>) qf.get(null);
        int threads = 32, rounds = 500;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads + 1);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    for (int r = 0; r < rounds; r++) {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        ActionQueueService.INSTANCE.addToQueue(new BlockChangeActionType());
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Throwable e) { error.set(e); }
            }); workers.add(t); t.start();
        }
        int peak = 0, exceeded = 0;
        for (int r = 0; r < rounds; r++) {
            q.clear();
            for (int n = 0; n < 24; n++) ActionQueueService.INSTANCE.addToQueue(new BlockChangeActionType());
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            peak = Math.max(peak, q.size());
            if (q.size() > 25) exceeded++;
        }
        for (Thread t : workers) t.join(10000);
        if (error.get() != null) throw new AssertionError(error.get());
        assertTrue(exceeded == 0 && peak == 25, "Concurrent queue capacity violated: peak=" + peak);
        LOGGER.info("PASS concurrent Ledger queue: cap=25 peak={} exceeded={}/{} dropOldest={}",
            peak, exceeded, rounds, Boolean.getBoolean("ledger.queueGuard.dropOldest"));
        q.clear();
    }

    private static void assertPublishedEntry(ServerLevel level, cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C.Entry entry) {
        var expected = ChunkLoadStateAccess.read(level, new ChunkPos(entry.chunkX(), entry.chunkZ())).orElseThrow();
        assertTrue(entry.level() == expected.level() && entry.band() == expected.band(), "Published state differs from holder: " + entry);
    }

    private static void assertStateParity(ServerLevel level, Map<?, ?> states) throws Exception {
        assertTrue(states.size() == 25, "Unexpected state count: " + states.size());
        java.util.Set<Long> coords = new java.util.HashSet<>();
        for (var kv : states.entrySet()) {
            var entry = (cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C.Entry) kv.getValue();
            var key = kv.getKey();
            var x = key.getClass().getDeclaredMethod("chunkX"); x.setAccessible(true);
            var z = key.getClass().getDeclaredMethod("chunkZ"); z.setAccessible(true);
            assertTrue((int) x.invoke(key) == entry.chunkX() && (int) z.invoke(key) == entry.chunkZ(), "Publisher key mismatch");
            coords.add(new ChunkPos(entry.chunkX(), entry.chunkZ()).pack());
            assertPublishedEntry(level, entry);
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            assertTrue(coords.contains(new ChunkPos(x, z).pack()), "Missing published coordinate");
    }

    private static void assertLoggerEntity(Entity entity) throws Exception {
        Class<?> type = entity.getClass();
        Field helper = null;
        while (type != null && helper == null) {
            for (Field f : type.getDeclaredFields()) if (f.getType() == carpet.logging.logHelpers.TrajectoryLogHelper.class) { helper = f; break; }
            type = type.getSuperclass();
        }
        assertTrue(helper != null, "Missing Carpet logger field"); helper.setAccessible(true);
        Object logger = helper.get(entity); assertTrue(logger != null, "Logger was not constructed");
        Field ef = logger.getClass().getDeclaredField("entity"); ef.setAccessible(true);
        assertTrue(ef.get(logger) == entity, "TIS helper lost constructor entity");
    }

    private static void verifyLoggerContext(ServerLevel level) throws Exception {
        LoggerRegistry.__projectiles = true;
        try {
            var ball = new net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball(level, 0, 90, 0, new ItemStack(Items.SNOWBALL));
            assertLoggerEntity(ball);
            assertTrue(TrajectoryLoggerUtil.currentEntity.get() == null, "Throwable logger left context");
            ball.discard();
            // This constructor has no Carpet helper. Retain only a WeakReference to
            // it, while the same thread's context remains installed and unconsumed.
            java.lang.ref.WeakReference<Entity> abandoned = abandonedArrow(level);
            for (int i = 0; i < 20 && abandoned.get() != null; i++) { System.gc(); Thread.sleep(10); }
            assertTrue(abandoned.get() == null, "Abandoned constructor entity pinned by context");
            assertTrue(TrajectoryLoggerUtil.currentEntity.get() == null, "Cleared weak context not removed");
            LOGGER.info("PASS logger identity for Arrow/Throwable and abandoned-constructor GC");
        } finally { LoggerRegistry.__projectiles = false; TrajectoryLoggerUtil.currentEntity.remove(); }
    }

    private static java.lang.ref.WeakReference<Entity> abandonedArrow(ServerLevel level) {
        Arrow arrow = new Arrow(net.minecraft.world.entity.EntityTypes.ARROW, level);
        assertTrue(TrajectoryLoggerUtil.currentEntity.get() == arrow, "Context not available to active constructor");
        return new java.lang.ref.WeakReference<>(arrow);
    }

    private static void verifyDisconnectFailure(MinecraftServer server, ServerLevel level) throws Exception {
        UUID uuid = UUID.nameUUIDFromBytes("OfflinePlayer:FailureBot".getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, "FailureBot");
        FakeClientConnection conn = new FakeClientConnection(PacketFlow.SERVERBOUND);
        var player = EntityPlayerMPFake.respawnFake(server, level, profile, ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(conn, player, new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false));
        int before = trackedAddonsCount();
        var invocations = new java.util.concurrent.atomic.AtomicInteger();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            if (handler.getPlayer() == player) {
                invocations.incrementAndGet();
                throw new IllegalStateException("expected-test-disconnect-failure");
            }
        });
        boolean caught = false;
        try { player.connection.onDisconnect(new net.minecraft.network.DisconnectionDetails(Component.literal("failure test"))); }
        catch (IllegalStateException e) { if (!"expected-test-disconnect-failure".equals(e.getMessage())) throw e; caught = true; }
        assertTrue(caught, "Disconnect exception was swallowed");
        assertTrue(trackedAddonsCount() == before - 1, "Callback exception retained addon");
        assertTrue(!((io.netty.channel.Channel) getField(net.minecraft.network.Connection.class, "channel", conn)).isOpen(), "Channel not closed after event failure");
        var addon = ((net.fabricmc.fabric.impl.networking.PacketListenerExtensions) player.connection).getAddon();
        addon.handleDisconnect();
        assertTrue(invocations.get() == 1, "Disconnect event dispatched twice");
        LOGGER.info("PASS disconnect listener exception cleanup and idempotence");
    }

    private static Object getField(Class<?> owner, String name, Object object) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }

    private static void verifyDatabaseRecovery() throws Exception {
        String mode = System.getProperty("ledger.verify.recoveryFixture", "none");
        if (mode.equals("none")) return;
        var path = com.github.quiltservertools.ledger.config.DatabaseSpecKt.getDatabasePath(
            com.github.quiltservertools.ledger.config.LedgerConfigKt.getConfig()).resolve("ledger.sqlite");
        assertTrue(java.nio.file.Files.exists(path), "Ledger did not recreate database");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path); var st = c.createStatement()) {
            try (var rs = st.executeQuery("PRAGMA quick_check")) { assertTrue(rs.next() && rs.getString(1).equals("ok"), "Recreated database invalid"); }
            for (String table : java.util.List.of("players", "actions", "ActionIdentifiers", "worlds", "ObjectIdentifiers", "sources")) {
                try (var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rs.next(), "Missing recreated table " + table); }
            }
            c.setAutoCommit(false);
            st.executeUpdate("INSERT INTO players(player_id,player_name,first_join,last_join) VALUES(zeroblob(16),'RecoveryTest','2000-01-01','2000-01-01')");
            c.rollback();
        }
        try (var dirs = java.nio.file.Files.list(path.toAbsolutePath().getParent())) {
            var backups = dirs.filter(p -> p.getFileName().toString().startsWith("ledger-reset-backup-")).toList();
            assertTrue(backups.size() == 1, "Expected one recovery backup, got " + backups.size());
            var old = backups.getFirst().resolve("ledger.sqlite");
            assertTrue(java.nio.file.Files.size(old) < 10000, "Backup exceeded reset threshold");
            if (mode.equals("corrupt")) assertTrue(java.nio.file.Files.readString(old).equals("ledger recovery fixture: not a database"), "Backup not byte-preserving");
        }
        LOGGER.info("PASS Ledger startup {} database reset: backup, schema, readable/writable new database", mode);
    }

    private static int trackedAddonsCount() throws Exception {
        Field f = GlobalReceiverRegistry.class.getDeclaredField("trackedAddons");
        f.setAccessible(true);
        return ((Set<?>) f.get(ServerNetworkingImpl.PLAY)).size();
    }

    private static List<?> mapCarriedByList(MapItemSavedData data) throws Exception {
        Field f = MapItemSavedData.class.getDeclaredField("carriedBy");
        f.setAccessible(true);
        return (List<?>) f.get(data);
    }

    private static Map<?, ?> mapCarriedByMap(MapItemSavedData data) throws Exception {
        Field f = MapItemSavedData.class.getDeclaredField("carriedByPlayers");
        f.setAccessible(true);
        return (Map<?, ?>) f.get(data);
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
