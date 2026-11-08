package me.fokend.livingworld.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.HistoryEventType;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionSnapshot;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.model.SpeciesGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path dir;

    private SqliteStorage open() throws Exception {
        SqliteStorage s = new SqliteStorage(dir.resolve("lw.db").toFile(), LOG);
        s.open();
        return s;
    }

    private static RegionState sample(int x, int z) {
        RegionState r = new RegionState(new RegionKey("world", x, z), BiomeProfile.FOREST, 1000, 5, 0.9);
        r.forest(0.4);
        r.stability(0.6);
        r.development(42);
        r.state(DevelopmentState.ACTIVE);
        r.customName("Дубрава «Тест»'; DROP TABLE lw_regions;--");
        r.population(SpeciesGroup.PREDATORS, 0.25);
        r.addInfluence("team:red", "Красные", 30);
        r.steward(UUID.randomUUID(), "Steve").addTrees(12);
        return r;
    }

    @Test
    void regionsSurviveReopen() throws Exception {
        RegionState r = sample(1, -2);
        try (SqliteStorage s = open()) {
            s.saveBatch(List.of(r.toSnapshot()), List.of(), 777);
        }
        try (SqliteStorage s = open()) {
            assertEquals(777, s.metaLong("sim_clock", 0));
            RegionSnapshot loaded = s.loadRegion(new RegionKey("world", 1, -2));
            assertNotNull(loaded);
            RegionState copy = RegionState.fromSnapshot(loaded, 0);
            assertEquals(r.customName(), copy.customName());
            assertEquals(0.4, copy.forest(), 1e-9);
            assertEquals(0.25, copy.population(SpeciesGroup.PREDATORS), 1e-9);
            assertEquals(DevelopmentState.ACTIVE, copy.state());
            assertEquals(30, copy.influenceOf("team:red"), 1e-9);
            assertEquals(12, copy.stewardsSorted().get(0).treesGrown());
            assertNull(s.loadRegion(new RegionKey("world", 9, 9)));
            assertNull(s.loadRegion(new RegionKey("world_nether", 1, -2)));
        }
    }

    @Test
    void influenceRowsAreReplacedNotAccumulated() throws Exception {
        RegionState r = sample(0, 0);
        try (SqliteStorage s = open()) {
            s.saveBatch(List.of(r.toSnapshot()), List.of(), 1);
            r.influenceMutable().clear();
            r.addInfluence("player:x", "X", 5);
            s.saveBatch(List.of(r.toSnapshot()), List.of(), 2);
            RegionSnapshot loaded = s.loadRegion(r.key());
            assertEquals(1, loaded.influence().size());
            assertEquals("player:x", loaded.influence().get(0).groupId());
        }
    }

    @Test
    void historyPaginationAndRetention() throws Exception {
        try (SqliteStorage s = open()) {
            List<HistoryEvent> events = new ArrayList<>();
            RegionKey a = new RegionKey("world", 0, 0);
            RegionKey b = new RegionKey("world", 1, 0);
            for (int i = 0; i < 50; i++) {
                events.add(HistoryEvent.of(1000L + i, HistoryEventType.DEFORESTATION, i % 2 == 0 ? a : b, i, "", ""));
            }
            s.saveBatch(List.of(), events, 0);
            List<HistoryRow> page = s.history(null, 0, 10);
            assertEquals(10, page.size());
            assertEquals(49, page.get(0).event().value(), 1e-9, "newest first");
            assertNull(page.get(0).customName());
            assertEquals(25, s.history(a, 0, 100).size());
            assertEquals(5, s.history(a, 20, 10).size());

            int removed = s.pruneHistory(1010L, 30);
            assertEquals(20, removed);
            assertEquals(30, s.history(null, 0, 100).size());
            assertEquals(30, s.stats().historyEvents());
        }
    }

    @Test
    void historyJoinsCurrentRegionName() throws Exception {
        try (SqliteStorage s = open()) {
            RegionState r = sample(4, 4);
            s.saveBatch(List.of(r.toSnapshot()), List.of(HistoryEvent.of(1, HistoryEventType.REGION_NAMED,
                    r.key(), 0, "old", "Steve")), 0);
            HistoryRow row = s.history(r.key(), 0, 5).get(0);
            assertEquals(r.customName(), row.customName());
            assertEquals(BiomeProfile.FOREST, row.profile());
        }
    }

    @Test
    void staleRegionsAndRankings() throws Exception {
        try (SqliteStorage s = open()) {
            List<RegionSnapshot> list = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                RegionState r = new RegionState(new RegionKey("world", i, 0), BiomeProfile.PLAINS, i * 100L, 0, 1);
                r.development(i);
                r.stability(1 - i / 40.0);
                list.add(r.toSnapshot());
            }
            s.saveBatch(list, List.of(), 0);
            List<RegionKey> stale = s.staleRegions(500, 3);
            assertEquals(3, stale.size());
            assertEquals(0, stale.get(0).x());
            List<RegionSummary> top = s.top(TopMetric.DEVELOPED, null, 5);
            assertEquals(19, top.get(0).development(), 1e-9);
            assertEquals(5, top.size());
            assertEquals(19, s.top(TopMetric.DAMAGED, "world", 1).get(0).key().x());
            assertTrue(s.top(TopMetric.DEVELOPED, "other_world", 5).isEmpty());
            WorldStats stats = s.stats();
            assertEquals(20, stats.regions());
            s.deleteRegion(new RegionKey("world", 0, 0));
            assertEquals(19, s.stats().regions());
        }
    }

    @Test
    void corruptedDatabaseIsQuarantinedNotDeleted() throws Exception {
        File f = dir.resolve("lw.db").toFile();
        Files.write(f.toPath(), "this is definitely not a sqlite database file, just garbage bytes......".repeat(200).getBytes());
        try (SqliteStorage s = open()) {
            assertTrue(s.isOpen());
            s.saveBatch(List.of(sample(0, 0).toSnapshot()), List.of(), 1);
        }
        File[] quarantined = dir.toFile().listFiles((d, n) -> n.startsWith("lw.db.corrupt-"));
        assertNotNull(quarantined);
        assertEquals(1, quarantined.length);
    }

    @Test
    void newerSchemaIsRefused() throws Exception {
        try (SqliteStorage s = open()) {
            s.putMeta("schema_version", String.valueOf(SqliteStorage.SCHEMA_VERSION + 5));
        }
        SqliteStorage s = new SqliteStorage(dir.resolve("lw.db").toFile(), LOG);
        assertThrows(StorageException.class, s::open);
        s.close();
    }

    @Test
    void serviceRunsWorkOffThreadAndShutsDownCleanly() throws Exception {
        SqliteStorage storage = open();
        ConcurrentLinkedQueue<Runnable> main = new ConcurrentLinkedQueue<>();
        StorageService service = new StorageService(storage, main::add, LOG, 16);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean offMain = new AtomicBoolean();
        Thread caller = Thread.currentThread();
        assertTrue(service.submit(st -> {
            offMain.set(Thread.currentThread() != caller);
            st.saveBatch(List.of(sample(5, 5).toSnapshot()), List.of(), 9);
            done.countDown();
            return 1;
        }, r -> {
        }, null));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(offMain.get());

        AtomicBoolean finalRan = new AtomicBoolean();
        service.shutdown(st -> {
            finalRan.set(st.loadRegion(new RegionKey("world", 5, 5)) != null);
            return null;
        }, 5);
        assertTrue(finalRan.get());
        assertFalse(storage.isOpen(), "connection closed on shutdown");
        assertFalse(service.submit(st -> 1, null, null), "no work after shutdown");
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.getName().equals("LivingWorld-Storage") && t.isAlive()), "thread terminated");
    }

    @Test
    void queueIsBoundedUnderBackPressure() throws Exception {
        SqliteStorage storage = open();
        StorageService service = new StorageService(storage, r -> { }, LOG, 16);
        CountDownLatch block = new CountDownLatch(1);
        service.submit(st -> {
            block.await();
            return null;
        }, null, null);
        int accepted = 0;
        for (int i = 0; i < 1000; i++) {
            if (service.submit(st -> null, null, null)) {
                accepted++;
            }
        }
        assertEquals(16, accepted);
        assertEquals(1000 - 16, service.rejectedCount());
        block.countDown();
        service.shutdown(null, 5);
    }
}
