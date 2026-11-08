package me.fokend.livingworld.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import me.fokend.livingworld.config.Defaults;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.HistoryEventType;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.model.SpeciesGroup;
import org.junit.jupiter.api.Test;

class RegionSimulatorTest {

    private static final long MINUTE = 60_000L;
    private static final long DAY = 86_400_000L;

    private final LivingWorldConfig cfg = Defaults.config();
    private final RegionSimulator sim = new RegionSimulator(cfg);
    private final List<HistoryEvent> events = new ArrayList<>();
    private final List<StepOutput.Transition> transitions = new ArrayList<>();
    private long now = 1_000_000L;

    private RegionState region(BiomeProfile profile) {
        return new RegionState(new RegionKey("world", 0, 0), profile, now, 0L, 1.0);
    }

    /** Runs the simulation for {@code days} using one-minute steps, applying {@code perStep} activity. */
    private void run(RegionState r, double days, Consumer<RegionState> perStep) {
        StepOutput out = new StepOutput();
        long steps = Math.round(days * 24 * 60);
        for (long i = 0; i < steps; i++) {
            if (perStep != null) {
                perStep.accept(r);
            }
            now += MINUTE;
            sim.step(r, now, now, out);
            events.addAll(out.events());
            transitions.addAll(out.transitions());
            out.clear();
        }
    }

    private boolean hasEvent(HistoryEventType type) {
        return events.stream().anyMatch(e -> e.type() == type);
    }

    @Test
    void untouchedRegionStaysPristineWilderness() {
        RegionState r = region(BiomeProfile.FOREST);
        run(r, 10, null);
        assertEquals(DevelopmentState.WILDERNESS, r.state());
        assertTrue(r.forest() > 0.99);
        assertTrue(r.stability() > 0.95);
        assertTrue(events.isEmpty(), "no chronicle noise for untouched land: " + events);
    }

    @Test
    void massLoggingDeforestsAndForestRecoversAfterwards() {
        RegionState r = region(BiomeProfile.FOREST);
        // ~2400 logs per day for 1 day = 60% of a 4000-log forest
        run(r, 1, s -> s.accumulators().logsCut += 2400.0 / 1440);
        assertTrue(r.forest() < 0.5, "forest=" + r.forest());
        assertTrue(hasEvent(HistoryEventType.DEFORESTATION));
        assertTrue(r.exploitation() > 0.5, "exploitation=" + r.exploitation());
        double afterCut = r.forest();

        // planting + natural regrowth: 30 trees/day of ~5 logs each, for 20 days
        run(r, 20, s -> {
            s.accumulators().treeLogsGrown += 150.0 / 1440;
        });
        assertTrue(r.forest() > afterCut + 0.3, "forest should recover: " + r.forest());
        assertTrue(hasEvent(HistoryEventType.FOREST_RESTORED));
        assertTrue(r.exploitation() < 0.1);
    }

    @Test
    void huntingReducesPopulationAndItRecovers() {
        RegionState r = region(BiomeProfile.PLAINS);
        double herd = cfg.herdSize(BiomeProfile.PLAINS, SpeciesGroup.GRAZERS);
        // kill 1.5 herds over 2 days
        run(r, 2, s -> s.accumulators().kills[SpeciesGroup.GRAZERS.ordinal()] += herd * 0.75 / 1440);
        assertTrue(r.population(SpeciesGroup.GRAZERS) < 0.3, "pop=" + r.population(SpeciesGroup.GRAZERS));
        assertTrue(hasEvent(HistoryEventType.WILDLIFE_DECLINE));
        // other groups untouched
        assertTrue(r.population(SpeciesGroup.SMALL_GAME) > 0.85);

        run(r, 30, null);
        assertTrue(r.population(SpeciesGroup.GRAZERS) > 0.7, "pop=" + r.population(SpeciesGroup.GRAZERS));
        assertTrue(hasEvent(HistoryEventType.WILDLIFE_RECOVERED));
    }

    @Test
    void absentSpeciesAreNeverSimulated() {
        RegionState r = region(BiomeProfile.OCEAN);
        r.accumulators().kills[SpeciesGroup.GRAZERS.ordinal()] = 1000;
        run(r, 1, null);
        assertFalse(hasEvent(HistoryEventType.WILDLIFE_DECLINE));
    }

    @Test
    void longStepEqualsManyShortSteps() {
        RegionState a = region(BiomeProfile.FOREST);
        RegionState b = region(BiomeProfile.FOREST);
        a.forest(0.3);
        b.forest(0.3);
        a.population(SpeciesGroup.GRAZERS, 0.2);
        b.population(SpeciesGroup.GRAZERS, 0.2);
        a.stability(0.5);
        b.stability(0.5);
        long start = now;
        run(a, 5, null);
        StepOutput out = new StepOutput();
        sim.step(b, start + 5 * DAY, start + 5 * DAY, out);
        assertEquals(a.forest(), b.forest(), 0.02);
        assertEquals(a.stability(), b.stability(), 0.05);
        assertEquals(a.population(SpeciesGroup.GRAZERS), b.population(SpeciesGroup.GRAZERS), 0.08);
    }

    @Test
    void catchUpIsCappedByMaxCatchupDays() {
        RegionState r = region(BiomeProfile.FOREST);
        r.forest(0.1);
        StepOutput out = new StepOutput();
        sim.step(r, now + 10_000L * DAY, now, out);
        assertTrue(Double.isFinite(r.forest()));
        assertTrue(r.lastStepDays() <= cfg.simulation().maxCatchupDays() + 1e-9);
    }

    @Test
    void settlementLifecycleFoundGrowHubAbandonReclaim() {
        RegionState r = region(BiomeProfile.PLAINS);
        Consumer<RegionState> village = s -> {
            s.accumulators().buildActions += 300.0 / 1440;      // 300 build actions per day
            s.accumulators().presenceSeconds += 3 * 3600.0 / 1440; // 3 player-hours per day
        };
        run(r, 2, village);
        assertEquals(DevelopmentState.DEVELOPING, r.state(), "dev=" + r.development());
        assertTrue(hasEvent(HistoryEventType.SETTLEMENT_FOUNDED));

        run(r, 3, village);
        assertEquals(DevelopmentState.ACTIVE, r.state(), "dev=" + r.development());
        assertTrue(hasEvent(HistoryEventType.SETTLEMENT_GREW));

        run(r, 4, s -> {
            village.accept(s);
            s.accumulators().trades += 80.0 / 1440;
        });
        assertEquals(DevelopmentState.COMMERCIAL_HUB, r.state(), "dev=" + r.development() + " trade=" + r.tradeEma());
        assertTrue(hasEvent(HistoryEventType.BECAME_COMMERCIAL_HUB));

        // everybody leaves
        run(r, cfg.development().abandonDays() + 6, null);
        assertEquals(DevelopmentState.ABANDONED, r.state());
        assertTrue(hasEvent(HistoryEventType.SETTLEMENT_ABANDONED));

        // nature slowly takes it back
        run(r, 120, null);
        assertEquals(DevelopmentState.WILDERNESS, r.state(), "dev=" + r.development());
        assertTrue(hasEvent(HistoryEventType.RECLAIMED_BY_NATURE));
    }

    @Test
    void abandonedSettlementCanBeRevived() {
        RegionState r = region(BiomeProfile.PLAINS);
        Consumer<RegionState> village = s -> {
            s.accumulators().buildActions += 300.0 / 1440;
            s.accumulators().presenceSeconds += 3 * 3600.0 / 1440;
        };
        run(r, 6, village);
        run(r, cfg.development().abandonDays() + 6, null);
        assertEquals(DevelopmentState.ABANDONED, r.state());
        run(r, 2, village);
        assertTrue(r.state().isSettlement(), "state=" + r.state());
        assertTrue(hasEvent(HistoryEventType.SETTLEMENT_REVIVED));
    }

    @Test
    void passerByDoesNotPreventAbandonment() {
        RegionState r = region(BiomeProfile.PLAINS);
        run(r, 6, s -> {
            s.accumulators().buildActions += 300.0 / 1440;
            s.accumulators().presenceSeconds += 3 * 3600.0 / 1440;
        });
        // someone walks through for 30 seconds every day
        long[] counter = {0};
        run(r, cfg.development().abandonDays() + 6, s -> {
            if (counter[0]++ % 1440 == 0) {
                s.accumulators().presenceSeconds += 30;
            }
        });
        assertEquals(DevelopmentState.ABANDONED, r.state());
    }

    @Test
    void overexploitationEntersAndRecovers() {
        RegionState r = region(BiomeProfile.FOREST);
        double herd = cfg.herdSize(BiomeProfile.FOREST, SpeciesGroup.GRAZERS);
        run(r, 2, s -> {
            s.accumulators().logsCut += 1800.0 / 1440;
            s.accumulators().kills[SpeciesGroup.GRAZERS.ordinal()] += herd / 1440;
        });
        assertEquals(DevelopmentState.OVEREXPLOITED, r.state(), "S=" + r.stability() + " X=" + r.exploitation());
        assertTrue(hasEvent(HistoryEventType.REGION_OVEREXPLOITED));
        run(r, 40, s -> s.accumulators().treeLogsGrown += 100.0 / 1440);
        assertTrue(r.state() != DevelopmentState.OVEREXPLOITED, "S=" + r.stability());
        assertTrue(hasEvent(HistoryEventType.ECOLOGY_RESTORED));
    }

    @Test
    void confirmationDelayPreventsFlapping() {
        RegionState r = region(BiomeProfile.PLAINS);
        r.development(9); // just above developing threshold
        StepOutput out = new StepOutput();
        now += MINUTE;
        sim.step(r, now, now, out);
        assertEquals(DevelopmentState.WILDERNESS, r.state(), "must wait for confirmation");
        assertEquals(DevelopmentState.DEVELOPING, r.candidateState());
        now += (long) (cfg.development().confirmMinutes() * MINUTE) + MINUTE;
        sim.step(r, now, now, out);
        assertEquals(DevelopmentState.DEVELOPING, r.state());
    }

    @Test
    void activeLongStepStillNeedsConfirmation() {
        RegionState r = region(BiomeProfile.PLAINS);
        r.development(9);
        r.accumulators().buildActions = 1; // activity happened => not a quiet catch-up
        StepOutput out = new StepOutput();
        now += 2 * DAY;
        sim.step(r, now, now, out);
        assertEquals(DevelopmentState.WILDERNESS, r.state());
        assertEquals(DevelopmentState.DEVELOPING, r.candidateState());
    }

    @Test
    void influenceDecaysDominatesAndStaysBounded() {
        RegionState r = region(BiomeProfile.PLAINS);
        r.accumulators().addInfluence("clan", "Clan", 500);
        for (int i = 0; i < 100; i++) {
            r.accumulators().addInfluence("g" + i, "G" + i, 1 + i);
        }
        StepOutput out = new StepOutput();
        now += MINUTE;
        sim.step(r, now, now, out);
        assertTrue(r.influenceMutable().size() <= RegionState.MAX_INFLUENCE_ENTRIES);
        assertEquals("clan", r.dominantGroup());
        assertTrue(out.events().stream().anyMatch(e -> e.type() == HistoryEventType.INFLUENCE_SHIFT));
        out.clear();
        double before = r.influenceOf("clan");
        now += 7 * DAY;
        sim.step(r, now, now, out);
        assertEquals(before / 2, r.influenceOf("clan"), before * 0.01);
    }

    @Test
    void influenceSpreadIsCapped() {
        RegionState src = region(BiomeProfile.PLAINS);
        src.state(DevelopmentState.ACTIVE);
        src.addInfluence("clan", "Clan", 200);
        RegionState n = new RegionState(new RegionKey("world", 1, 0), BiomeProfile.PLAINS, now, 0L, 1.0);
        for (int i = 0; i < 1000; i++) {
            sim.spreadInfluence(src, n, 1.0);
        }
        assertEquals(200 * cfg.influence().spreadCap(), n.influenceOf("clan"), 1e-6);
    }

    @Test
    void stewardMilestonesAreRecordedOnce() {
        RegionState r = region(BiomeProfile.PLAINS);
        UUID id = UUID.randomUUID();
        StepOutput out = new StepOutput();
        for (int i = 0; i < 30; i++) {
            r.accumulators().addSteward(id, "Alex", 1);
            now += MINUTE;
            sim.step(r, now, now, out);
        }
        long count = out.events().stream().filter(e -> e.type() == HistoryEventType.STEWARD_MILESTONE).count();
        assertEquals(1, count);
        assertEquals(30, r.stewardsSorted().get(0).treesGrown());
    }

    @Test
    void transitionMapping() {
        assertEquals(HistoryEventType.ECOLOGY_RESTORED,
                RegionSimulator.transitionEvent(DevelopmentState.OVEREXPLOITED, DevelopmentState.WILDERNESS));
        assertNull(RegionSimulator.transitionEvent(DevelopmentState.DEVELOPING, DevelopmentState.WILDERNESS));
        assertEquals(HistoryEventType.HUB_STATUS_LOST,
                RegionSimulator.transitionEvent(DevelopmentState.COMMERCIAL_HUB, DevelopmentState.ACTIVE));
    }
}
