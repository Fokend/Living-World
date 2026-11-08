package me.fokend.livingworld.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelTest {

    @Test
    void regionKeyUsesFloorDivision() {
        assertEquals(new RegionKey("w", -1, -1), RegionKey.ofBlock("w", -1, -1, 256));
        assertEquals(new RegionKey("w", 0, 0), RegionKey.ofBlock("w", 255, 0, 256));
        assertEquals(new RegionKey("w", 1, 0), RegionKey.ofBlock("w", 256, 0, 256));
    }

    @Test
    void biomeClassification() {
        assertEquals(BiomeProfile.FOREST, BiomeProfile.classify("minecraft:dark_forest"));
        assertEquals(BiomeProfile.TAIGA, BiomeProfile.classify("snowy_taiga"));
        assertEquals(BiomeProfile.OCEAN, BiomeProfile.classify("deep_frozen_ocean"));
        assertEquals(BiomeProfile.MUSHROOM, BiomeProfile.classify("mushroom_fields"));
        assertEquals(BiomeProfile.PLAINS, BiomeProfile.classify("sunflower_plains"));
        assertEquals(BiomeProfile.MOUNTAINS, BiomeProfile.classify("jagged_peaks"));
        assertEquals(BiomeProfile.CAVE, BiomeProfile.classify("lush_caves"));
        assertEquals(BiomeProfile.OTHER, BiomeProfile.classify("the_void"));
    }

    @Test
    void accumulatorsAreBounded() {
        Accumulators a = new Accumulators();
        for (int i = 0; i < 10_000; i++) {
            a.addInfluence("g" + i, "n", 1);
            a.addSteward(UUID.randomUUID(), "n", 1);
        }
        assertEquals(Accumulators.MAX_CONTRIBUTORS, a.influence().size());
        assertEquals(Accumulators.MAX_CONTRIBUTORS, a.stewards().size());
        a.clear();
        assertTrue(a.isEmpty());
    }

    @Test
    void stewardsAreBounded() {
        RegionState r = new RegionState(new RegionKey("w", 0, 0), BiomeProfile.PLAINS, 0, 0, 1);
        for (int i = 0; i < 500; i++) {
            r.steward(UUID.randomUUID(), "p").addTrees(i);
        }
        assertEquals(RegionState.MAX_STEWARD_ENTRIES, r.stewardsSorted().size());
    }

    @Test
    void corruptedSnapshotIsSanitised() {
        RegionSnapshot s = new RegionSnapshot(new RegionKey("w", 0, 0), BiomeProfile.FOREST, null,
                DevelopmentState.ACTIVE, null, 0, Double.NaN, 500, 7.5, -3, Double.NaN,
                new double[]{Double.NaN, 2}, -1, Double.POSITIVE_INFINITY, 3, 1, -5, 10, 0, -1, 2, 3, 4, 5, 0, null,
                List.of(new RegionSnapshot.Influence("g", "G", Double.NaN)), List.of());
        RegionState r = RegionState.fromSnapshot(s, 0);
        assertEquals(0, r.development());
        assertEquals(1, r.forest());
        assertEquals(0, r.stability());
        assertEquals(0, r.population(SpeciesGroup.GRAZERS));
        assertEquals(1, r.population(SpeciesGroup.SMALL_GAME));
        assertEquals(1, r.population(SpeciesGroup.PREDATORS), "missing columns default to healthy");
        assertEquals(0, r.buildEma());
        assertTrue(r.influenceMutable().isEmpty());
        assertTrue(r.lastActivitySim() >= 0);
    }

    @Test
    void snapshotRoundTrip() {
        RegionState r = new RegionState(new RegionKey("w", 3, -4), BiomeProfile.TAIGA, 100, 50, 0.7);
        r.forest(0.42);
        r.development(33);
        r.state(DevelopmentState.DEVELOPING);
        r.addInfluence("g", "G", 12);
        r.steward(UUID.randomUUID(), "Alex").addTrees(5);
        r.flag(RegionState.FLAG_FOREST_LOW, true);
        RegionState copy = RegionState.fromSnapshot(r.toSnapshot(), 0);
        assertEquals(r.toSnapshot().toString().replaceAll("\\[D@[0-9a-f]+", ""),
                copy.toSnapshot().toString().replaceAll("\\[D@[0-9a-f]+", ""));
        assertEquals(0.7, copy.population(SpeciesGroup.AQUATIC), 1e-9);
    }
}
