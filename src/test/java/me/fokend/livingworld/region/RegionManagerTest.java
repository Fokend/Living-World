package me.fokend.livingworld.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import me.fokend.livingworld.config.Defaults;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.history.HistoryService;
import me.fokend.livingworld.model.Accumulators;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.HistoryEventType;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.sim.SimClock;
import me.fokend.livingworld.storage.SqliteStorage;
import me.fokend.livingworld.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionManagerTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    private final ConcurrentLinkedQueue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
    private SqliteStorage sqlite;
    private StorageService storage;
    private HistoryService history;
    private SimClock clock;
    private RegionManager manager;

    private LivingWorldConfig cfg(int maxLoaded, int maxPending) {
        LivingWorldConfig d = Defaults.config();
        LivingWorldConfig.Cache c = d.cache();
        return new LivingWorldConfig(d.general(), d.simulation(), d.ecology(), d.wildlife(), d.development(),
                d.influence(), d.history(), new LivingWorldConfig.Cache(maxLoaded, 1, maxPending, 4096,
                c.saplingMemory(), c.placedLogMemory(), true, 1, 64, 1.0), d.tracking(), d.ui(), d.profiles());
    }

    private void start(LivingWorldConfig cfg, long clockStart) throws Exception {
        sqlite = new SqliteStorage(dir.resolve("lw.db").toFile(), LOG);
        sqlite.open();
        storage = new StorageService(sqlite, mainQueue::add, LOG, cfg.cache().dbQueueCapacity());
        history = new HistoryService(cfg.history());
        clock = new SimClock(clockStart, 1.0);
        manager = new RegionManager(cfg, storage, history, clock, LOG);
    }

    private void stop() {
        storage.shutdown(st -> {
            st.saveBatch(manager.snapshotDirty(), history.drainUnsaved(), clock.now());
            return null;
        }, 5);
        manager.clear();
    }

    @AfterEach
    void cleanup() {
        if (storage != null && !storage.isClosed()) {
            storage.shutdown(null, 5);
        }
    }

    /** Waits for the storage thread and runs the callbacks it posted to the "main thread". */
    private void pump() throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            while (!mainQueue.isEmpty()) {
                mainQueue.poll().run();
            }
            if (storage.queueSize() == 0 && storage.activeCount() == 0 && mainQueue.isEmpty()) {
                Thread.sleep(2);
                if (mainQueue.isEmpty() && storage.activeCount() == 0) {
                    return;
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError("storage did not settle");
    }

    @Test
    void activityIsBufferedWhileLoadingAndMergedAfterwards() throws Exception {
        start(cfg(2048, 256), 0);
        RegionKey key = new RegionKey("world", 3, 3);
        Accumulators acc = manager.activity(key, () -> BiomeProfile.FOREST, 0);
        assertNotNull(acc);
        acc.logsCut += 40;
        assertNull(manager.getLoaded(key));
        assertTrue(manager.isPending(key));
        pump();
        RegionState r = manager.getLoaded(key);
        assertNotNull(r);
        assertEquals(BiomeProfile.FOREST, r.profile());
        assertEquals(40, r.accumulators().logsCut, 1e-9);
        assertEquals(0, manager.pendingCount());
        stop();
    }

    @Test
    void pendingLoadsAreBounded() throws Exception {
        start(cfg(2048, 8), 0);
        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            if (manager.activity(new RegionKey("world", i, 0), () -> BiomeProfile.PLAINS, 0) != null) {
                accepted++;
            }
        }
        assertEquals(8, accepted);
        assertEquals(8, manager.pendingCount());
        assertEquals(92, manager.droppedActivity());
        pump();
        assertEquals(0, manager.pendingCount());
        stop();
    }

    @Test
    void stateSurvivesRestartAndCatchesUp() throws Exception {
        start(cfg(2048, 256), 1000);
        RegionKey key = new RegionKey("world", 0, 0);
        manager.activity(key, () -> BiomeProfile.FOREST, 0);
        pump();
        RegionState r = manager.getLoaded(key);
        r.forest(0.3);
        r.markDirty();
        long simAtStop = clock.tick();
        stop();

        // "restart": the clock resumes from the persisted value plus 10 days of uptime
        long persisted;
        try (SqliteStorage s = new SqliteStorage(dir.resolve("lw.db").toFile(), LOG)) {
            s.open();
            persisted = s.metaLong("sim_clock", -1);
        }
        assertTrue(persisted >= simAtStop);
        start(cfg(2048, 256), persisted);
        clock.advance(10 * DAY);
        manager.ensureLoaded(key, () -> BiomeProfile.PLAINS, 0);
        pump();
        RegionState again = manager.getLoaded(key);
        assertEquals(BiomeProfile.FOREST, again.profile(), "profile from storage, not the hint");
        assertTrue(again.forest() > 0.3, "forest recovered during catch-up: " + again.forest());
        assertTrue(again.forest() < 0.9);
        stop();
    }

    @Test
    void autosavePersistsDirtyRegionsAndHistory() throws Exception {
        start(cfg(2048, 256), 0);
        for (int i = 0; i < 50; i++) {
            manager.activity(new RegionKey("world", i, i), () -> BiomeProfile.PLAINS, 0);
        }
        pump();
        assertEquals(50, manager.dirtyCount());
        history.record(me.fokend.livingworld.model.HistoryEvent.of(1, HistoryEventType.REGION_NAMED,
                new RegionKey("world", 0, 0), 0, "x", "y"));
        assertTrue(manager.saveAsync());
        assertTrue(manager.saveInFlight());
        assertEquals(0, manager.evict(Long.MAX_VALUE, true), "no eviction while a save is in flight");
        pump();
        assertFalse(manager.saveInFlight());
        assertEquals(0, manager.dirtyCount());
        assertEquals(0, history.unsavedSize());
        stop();
        try (SqliteStorage s = new SqliteStorage(dir.resolve("lw.db").toFile(), LOG)) {
            s.open();
            assertEquals(50, s.stats().regions());
            assertEquals(1, s.stats().historyEvents());
        }
    }

    @Test
    void failedSaveKeepsDataDirty() throws Exception {
        start(cfg(2048, 256), 0);
        manager.activity(new RegionKey("world", 0, 0), () -> BiomeProfile.PLAINS, 0);
        pump();
        sqlite.close(); // simulate a broken database
        assertTrue(manager.saveAsync());
        pump();
        assertEquals(1, manager.dirtyCount(), "region re-marked dirty after failure");
        storage.shutdown(null, 5);
    }

    @Test
    void cacheStaysBoundedAndOnlyCleanIdleRegionsAreEvicted() throws Exception {
        start(cfg(64, 256), 0);
        for (int i = 0; i < 200; i++) {
            manager.activity(new RegionKey("world", i, 0), () -> BiomeProfile.PLAINS, 0);
            if (i % 50 == 49) {
                pump();
            }
        }
        pump();
        // all regions are new => dirty => must not be evicted before they are saved
        assertEquals(0, manager.evict(Long.MAX_VALUE, true));
        assertEquals(200, manager.loadedCount());
        manager.saveAsync();
        pump();
        manager.evict(0, true); // nothing idle yet at wall time 0, but force brings it under the cap
        assertTrue(manager.loadedCount() <= 64, "loaded=" + manager.loadedCount());
        manager.evict(Long.MAX_VALUE, false);
        assertEquals(0, manager.loadedCount());
        stop();
    }

    @Test
    void longRunKeepsCollectionsBounded() throws Exception {
        start(cfg(128, 64), 0);
        long wall = 0;
        for (int round = 0; round < 60; round++) {
            for (int i = 0; i < 64; i++) {
                Accumulators a = manager.activity(new RegionKey("world", round * 64 + i, 7), () -> BiomeProfile.FOREST, wall);
                if (a != null) {
                    a.logsCut += 3;
                    a.addInfluence("player:" + i, "P" + i, 1);
                }
            }
            pump();
            clock.advance(60_000L);
            wall += 120_000L;
            manager.tickSimulation(wall);
            while (manager.passQueueSize() > 0) {
                manager.tickSimulation(wall);
            }
            manager.saveAsync();
            pump();
            manager.evict(wall, true);
            assertTrue(manager.loadedCount() <= 128 + 64, "round " + round + " loaded=" + manager.loadedCount());
            assertTrue(history.unsavedSize() <= HistoryService.MAX_UNSAVED);
            assertTrue(history.recentSize() <= Defaults.history().memoryRecent());
        }
        assertEquals(0, manager.pendingCount());
        assertEquals(0, manager.failedCount());
        stop();
        try (SqliteStorage s = new SqliteStorage(dir.resolve("lw.db").toFile(), LOG)) {
            s.open();
            assertEquals(60 * 64, s.stats().regions(), "every region persisted");
        }
    }

    @Test
    void dormantSweepAdvancesForgottenSettlementsWithoutPlayers() throws Exception {
        start(cfg(2048, 256), 0);
        RegionKey key = new RegionKey("world", 9, 9);
        manager.activity(key, () -> BiomeProfile.PLAINS, 0);
        pump();
        RegionState r = manager.getLoaded(key);
        r.state(DevelopmentState.ACTIVE);
        r.development(60);
        r.lastActivitySim(clock.now());
        r.markDirty();
        manager.saveAsync();
        pump();
        manager.evict(Long.MAX_VALUE, false);
        assertNull(manager.getLoaded(key));

        clock.advance(20 * DAY);
        manager.dormantSweep(0);
        pump();
        RegionState swept = manager.getLoaded(key);
        assertNotNull(swept, "sweep loaded the stale region");
        assertEquals(DevelopmentState.ACTIVE, swept.state(), "candidate needs confirmation");
        clock.advance(DAY);
        manager.stepRegion(swept, clock.now(), 0);
        assertEquals(DevelopmentState.ABANDONED, swept.state());
        assertTrue(history.recent(10).stream().anyMatch(e -> e.type() == HistoryEventType.SETTLEMENT_ABANDONED));
        stop();
    }

    @Test
    void historyServiceIsRateLimitedAndBounded() {
        HistoryService h = new HistoryService(Defaults.history());
        int accepted = 0;
        for (int i = 0; i < 10_000; i++) {
            if (h.record(me.fokend.livingworld.model.HistoryEvent.of(1000, HistoryEventType.DEFORESTATION,
                    new RegionKey("w", i, 0), 0, "", ""))) {
                accepted++;
            }
        }
        assertEquals(Defaults.history().maxPerHour(), accepted);
        assertTrue(h.recentSize() <= Defaults.history().memoryRecent());
        List<me.fokend.livingworld.model.HistoryEvent> drained = h.drainUnsaved();
        assertEquals(accepted, drained.size());
        h.requeue(java.util.Collections.nCopies(5000, drained.get(0)));
        assertEquals(HistoryService.MAX_UNSAVED, h.unsavedSize());
    }
}
