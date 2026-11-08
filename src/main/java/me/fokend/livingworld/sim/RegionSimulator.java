package me.fokend.livingworld.sim;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.model.Accumulators;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.HistoryEventType;
import me.fokend.livingworld.model.InfluenceEntry;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.model.SpeciesGroup;
import me.fokend.livingworld.model.StewardEntry;

/**
 * Pure simulation of one region over an arbitrary time span. All formulas are exponential/analytic in
 * {@code dt}, so the result of one long step equals (closely) the result of many short ones. This is
 * what allows regions that were not in memory to "catch up" when they are loaded again, without the
 * plugin ever having to load chunks or keep every region resident.
 *
 * <p>No Bukkit API is used here; the class is fully unit-testable.</p>
 */
public final class RegionSimulator {

    public static final double DAY_MS = 86_400_000.0;
    private static final double LN2 = Math.log(2);
    private static final long MIN_STEP_MS = 1_000L;

    private final LivingWorldConfig cfg;

    public RegionSimulator(LivingWorldConfig cfg) {
        this.cfg = cfg;
    }

    public LivingWorldConfig config() {
        return cfg;
    }

    /**
     * Advances {@code r} to {@code nowSim}, folding the accumulated activity into the region metrics.
     *
     * @return true if the region was actually stepped
     */
    public boolean step(RegionState r, long nowSim, long nowWall, StepOutput out) {
        long dtMs = nowSim - r.lastSimulatedSim();
        if (dtMs < MIN_STEP_MS) {
            return false;
        }
        double dt = Math.min(dtMs / DAY_MS, cfg.simulation().maxCatchupDays());
        Accumulators a = r.accumulators();
        LivingWorldConfig.Ecology eco = cfg.ecology();
        LivingWorldConfig.Wildlife wl = cfg.wildlife();

        double prevForest = r.forest();
        double prevStability = r.stability();

        // ---------------------------------------------------------------- 1. fold raw activity
        double forestCap = cfg.forestCapacityLogs(r.profile());
        if (eco.enabled()) {
            r.forest(r.forest() - a.logsCut / forestCap + a.treeLogsGrown / forestCap);
        }
        double huntFraction = 0;
        long killsTotal = 0;
        for (SpeciesGroup g : SpeciesGroup.all()) {
            double kills = a.kills[g.ordinal()];
            killsTotal += Math.round(kills);
            if (!wl.enabled() || kills <= 0 || !cfg.groupPresent(r.profile(), g)) {
                continue;
            }
            double fraction = kills / cfg.herdSize(r.profile(), g);
            r.population(g, r.population(g) - fraction);
            huntFraction += fraction;
        }
        double oreCap = Math.max(1.0, eco.oreCapacity() * cfg.general().areaScale());
        double pressureSample = (eco.enabled() ? a.logsCut / forestCap : 0)
                + huntFraction * eco.huntPressureWeight()
                + a.oresMined / oreCap * eco.orePressureWeight();
        r.pressureEma(ema(r.pressureEma(), pressureSample / dt, dt, eco.pressureTauDays()));
        double p = r.pressureEma();
        r.exploitation(p / (p + eco.pressureHalfSaturation()));

        LivingWorldConfig.Development dev = cfg.development();
        r.presenceEma(ema(r.presenceEma(), a.presenceSeconds / 3600.0 / dt, dt, dev.activityTauDays()));
        r.buildEma(ema(r.buildEma(), a.buildActions / dt, dt, dev.activityTauDays()));
        r.tradeEma(ema(r.tradeEma(), a.trades / dt, dt, dev.activityTauDays()));
        // A passer-by does not count as "living here": only building, trading or sustained presence do.
        if (a.buildActions > 0 || a.trades > 0 || r.presenceEma() >= dev.revivePresence()) {
            r.lastActivitySim(nowSim);
        }
        r.addTotals(Math.round(a.logsCut), a.treesGrown, killsTotal, Math.round(a.trades), Math.round(a.buildActions));

        // ---------------------------------------------------------------- 2. natural recovery
        double regen = eco.minRegenMultiplier() + (1 - eco.minRegenMultiplier()) * r.stability();
        if (r.state() == DevelopmentState.ABANDONED) {
            regen *= eco.abandonedRegenBonus();
        }
        if (eco.enabled()) {
            r.forest(r.forest() + (1 - r.forest()) * (1 - Math.exp(-eco.forestRegenPerDay() * regen * dt)));
        }
        double wildlifeSum = 0;
        int wildlifeGroups = 0;
        if (wl.enabled()) {
            double carrying = wl.minCarryingCapacity() + (1 - wl.minCarryingCapacity()) * r.stability();
            for (SpeciesGroup g : SpeciesGroup.all()) {
                if (!cfg.groupPresent(r.profile(), g)) {
                    continue;
                }
                double growth = wl.growthPerDay().getOrDefault(g, 0.3) * regen;
                double pop = logistic(Math.max(0.01, r.population(g)), carrying, growth, dt);
                if (pop < carrying) {
                    pop += (carrying - pop) * (1 - Math.exp(-wl.immigrationPerDay() * dt));
                }
                r.population(g, pop);
                wildlifeSum += r.population(g);
                wildlifeGroups++;
            }
        }

        // ---------------------------------------------------------------- 3. stability
        if (eco.enabled()) {
            double avgWildlife = wildlifeGroups == 0 ? 1.0 : wildlifeSum / wildlifeGroups;
            double wSum = eco.weightForest() + eco.weightWildlife() + eco.weightExploitation();
            double target = (eco.weightForest() * r.forest() + eco.weightWildlife() * avgWildlife
                    + eco.weightExploitation() * (1 - r.exploitation())) / wSum;
            double halfLifeDays = eco.stabilityHalfLifeHours() / 24.0;
            r.stability(r.stability() + (target - r.stability()) * (1 - Math.exp(-LN2 / halfLifeDays * dt)));
        }
        r.forestTrend(ema(r.forestTrend(), (r.forest() - prevForest) / dt, dt, 0.5));
        r.stabilityTrend(ema(r.stabilityTrend(), (r.stability() - prevStability) / dt, dt, 0.5));

        // ---------------------------------------------------------------- 4. development
        if (dev.enabled()) {
            double gain = dev.buildWeight() * Math.log1p(r.buildEma())
                    + dev.presenceWeight() * Math.log1p(r.presenceEma())
                    + dev.tradeWeight() * Math.log1p(r.tradeEma());
            double d = r.development();
            d += gain * (1 - d / 100.0) * dt;
            if (inactiveDays(r, nowSim) > dev.graceDays()) {
                d *= Math.exp(-LN2 / dev.decayHalfLifeDays() * dt);
            }
            r.development(d);
            updateState(r, nowSim, nowWall, out);
        }

        // ---------------------------------------------------------------- 5. influence & stewards
        foldInfluence(r, a, dt, nowWall, out);
        foldStewards(r, a, nowWall, out);

        // ---------------------------------------------------------------- 6. chronicle latches
        detectEcologyEvents(r, forestCap, nowWall, out);

        a.clear();
        r.lastSimulatedSim(nowSim);
        r.lastStepDays(dt);
        r.markDirty();
        return true;
    }

    // ==================================================================== development state machine

    public static double inactiveDays(RegionState r, long nowSim) {
        if (r.lastActivitySim() <= 0) {
            return Double.MAX_VALUE;
        }
        return Math.max(0, nowSim - r.lastActivitySim()) / DAY_MS;
    }

    /** Computes the state the region "wants" to be in, ignoring the confirmation delay. */
    public DevelopmentState desiredState(RegionState r, long nowSim) {
        LivingWorldConfig.Development d = cfg.development();
        DevelopmentState cur = r.state();

        if (cfg.ecology().enabled()) {
            if (cur == DevelopmentState.OVEREXPLOITED) {
                if (r.stability() < d.overexploitExitStability() || r.exploitation() > d.overexploitExitExploitation()) {
                    return DevelopmentState.OVEREXPLOITED;
                }
            } else if (r.stability() < d.overexploitEnterStability()
                    || r.exploitation() >= d.overexploitEnterExploitation()) {
                return DevelopmentState.OVEREXPLOITED;
            }
        }

        double dev = r.development();
        double inactive = inactiveDays(r, nowSim);
        boolean wasSettled = r.peakDevelopment() >= d.abandonPeakMin();

        if (cur == DevelopmentState.ABANDONED) {
            if (inactive < 1.0 && r.presenceEma() >= d.revivePresence()) {
                return DevelopmentState.DEVELOPING;
            }
            if (dev < d.reclaimDevelopment() && r.forest() >= d.reclaimForest()) {
                return DevelopmentState.WILDERNESS;
            }
            return DevelopmentState.ABANDONED;
        }
        if (wasSettled && inactive >= d.abandonDays()
                && (cur.isSettlement() || cur == DevelopmentState.OVEREXPLOITED)) {
            return DevelopmentState.ABANDONED;
        }

        double keep = d.keepRatio();
        boolean hub = cur == DevelopmentState.COMMERCIAL_HUB
                ? dev >= d.hubThreshold() * keep && r.tradeEma() >= d.hubTrade() * keep
                : dev >= d.hubThreshold() && r.tradeEma() >= d.hubTrade();
        if (hub) {
            return DevelopmentState.COMMERCIAL_HUB;
        }
        boolean wasActive = cur == DevelopmentState.ACTIVE || cur == DevelopmentState.COMMERCIAL_HUB;
        boolean active = wasActive
                ? dev >= d.activeThreshold() * keep && r.presenceEma() >= d.activePresence() * keep
                : dev >= d.activeThreshold() && r.presenceEma() >= d.activePresence();
        if (active) {
            return DevelopmentState.ACTIVE;
        }
        boolean developing = cur.isSettlement()
                ? dev >= d.developingThreshold() * keep
                : dev >= d.developingThreshold();
        return developing ? DevelopmentState.DEVELOPING : DevelopmentState.WILDERNESS;
    }

    private void updateState(RegionState r, long nowSim, long nowWall, StepOutput out) {
        DevelopmentState desired = desiredState(r, nowSim);
        if (desired == r.state()) {
            r.candidateState(null);
            return;
        }
        long confirmMs = (long) (cfg.development().confirmMinutes() * 60_000L);
        if (r.candidateState() != desired) {
            r.candidateState(desired);
            r.candidateSince(nowSim);
            if (confirmMs > 0) {
                return;
            }
        }
        if (nowSim - r.candidateSince() < confirmMs) {
            return;
        }
        DevelopmentState from = r.state();
        r.state(desired);
        r.candidateState(null);
        if (desired == DevelopmentState.WILDERNESS && from == DevelopmentState.ABANDONED) {
            r.peakDevelopment(r.development()); // the old settlement is gone from the region's memory
        }
        out.transition(r.key(), from, desired);
        HistoryEventType type = transitionEvent(from, desired);
        if (type != null) {
            out.event(HistoryEvent.of(nowWall, type, r.key(), r.development(), desired.name(), ""));
        }
    }

    /** Maps a state transition to the chronicle entry it produces (or null for silent transitions). */
    public static HistoryEventType transitionEvent(DevelopmentState from, DevelopmentState to) {
        if (from == DevelopmentState.OVEREXPLOITED && to != DevelopmentState.ABANDONED) {
            return HistoryEventType.ECOLOGY_RESTORED;
        }
        return switch (to) {
            case OVEREXPLOITED -> HistoryEventType.REGION_OVEREXPLOITED;
            case ABANDONED -> HistoryEventType.SETTLEMENT_ABANDONED;
            case COMMERCIAL_HUB -> HistoryEventType.BECAME_COMMERCIAL_HUB;
            case ACTIVE -> from == DevelopmentState.COMMERCIAL_HUB ? HistoryEventType.HUB_STATUS_LOST
                    : HistoryEventType.SETTLEMENT_GREW;
            case DEVELOPING -> switch (from) {
                case WILDERNESS -> HistoryEventType.SETTLEMENT_FOUNDED;
                case ABANDONED -> HistoryEventType.SETTLEMENT_REVIVED;
                case COMMERCIAL_HUB -> HistoryEventType.HUB_STATUS_LOST;
                default -> HistoryEventType.SETTLEMENT_DECLINED;
            };
            case WILDERNESS -> from == DevelopmentState.ABANDONED ? HistoryEventType.RECLAIMED_BY_NATURE : null;
        };
    }

    // ==================================================================== influence

    private void foldInfluence(RegionState r, Accumulators a, double dt, long nowWall, StepOutput out) {
        LivingWorldConfig.Influence inf = cfg.influence();
        if (!inf.enabled()) {
            return;
        }
        for (Map.Entry<String, Accumulators.InfluenceContribution> e : a.influence().entrySet()) {
            r.addInfluence(e.getKey(), e.getValue().displayName, e.getValue().amount);
        }
        double decay = Math.exp(-LN2 / inf.halfLifeDays() * dt);
        List<InfluenceEntry> list = r.influenceMutable();
        for (Iterator<InfluenceEntry> it = list.iterator(); it.hasNext(); ) {
            InfluenceEntry e = it.next();
            e.score(e.score() * decay);
            if (e.score() < inf.minScore()) {
                it.remove();
            }
        }
        InfluenceEntry dominant = dominant(r);
        String newId = dominant == null ? null : dominant.groupId();
        String oldId = r.dominantGroup();
        if (newId != null && !newId.equals(oldId)) {
            out.event(HistoryEvent.of(nowWall, HistoryEventType.INFLUENCE_SHIFT, r.key(), dominant.score(),
                    dominant.displayName(), dominant.displayName()));
        }
        if (newId != null || (oldId != null && r.influenceOf(oldId) < inf.dominanceMin() * 0.5)) {
            r.dominantGroup(newId);
        }
    }

    /** The group that clearly dominates the region, or null. */
    public InfluenceEntry dominant(RegionState r) {
        LivingWorldConfig.Influence inf = cfg.influence();
        InfluenceEntry best = null;
        InfluenceEntry second = null;
        for (InfluenceEntry e : r.influenceMutable()) {
            if (best == null || e.score() > best.score()) {
                second = best;
                best = e;
            } else if (second == null || e.score() > second.score()) {
                second = e;
            }
        }
        if (best == null || best.score() < inf.dominanceMin()) {
            return null;
        }
        if (second != null && best.score() < second.score() * inf.dominanceRatio()) {
            return null;
        }
        return best;
    }

    /**
     * Lets the dominant group of a developed region slowly spread influence into a neighbour. Called by the
     * region manager only for neighbours that are already loaded (never loads anything).
     */
    public void spreadInfluence(RegionState source, RegionState neighbour, double dt) {
        LivingWorldConfig.Influence inf = cfg.influence();
        if (!inf.enabled() || !source.state().isSettlement() || dt <= 0) {
            return;
        }
        InfluenceEntry dom = dominant(source);
        if (dom == null || dom.score() < inf.spreadMin()) {
            return;
        }
        double cap = dom.score() * inf.spreadCap();
        double current = neighbour.influenceOf(dom.groupId());
        if (current >= cap) {
            return;
        }
        double amount = Math.min(cap - current, dom.score() * inf.spreadPerDay() * dt);
        neighbour.addInfluence(dom.groupId(), dom.displayName(), amount);
        neighbour.markDirty();
    }

    // ==================================================================== stewards

    private void foldStewards(RegionState r, Accumulators a, long nowWall, StepOutput out) {
        List<Integer> milestones = cfg.history().stewardMilestones();
        for (Map.Entry<UUID, Accumulators.StewardContribution> e : a.stewards().entrySet()) {
            StewardEntry s = r.steward(e.getKey(), e.getValue().playerName);
            s.addTrees(e.getValue().trees);
            int reached = 0;
            for (int m : milestones) {
                if (s.treesGrown() >= m) {
                    reached = Math.max(reached, m);
                }
            }
            if (reached > s.lastMilestone()) {
                s.lastMilestone(reached);
                out.event(HistoryEvent.of(nowWall, HistoryEventType.STEWARD_MILESTONE, r.key(), reached,
                        s.playerName(), s.playerName()));
            }
        }
    }

    // ==================================================================== ecology chronicle

    private void detectEcologyEvents(RegionState r, double forestCap, long nowWall, StepOutput out) {
        LivingWorldConfig.Ecology eco = cfg.ecology();
        if (eco.enabled() && forestCap >= eco.minForestCapacityForEvents()) {
            double f = r.forest();
            if (!r.flag(RegionState.FLAG_FOREST_LOW) && f < eco.deforestationThreshold()) {
                r.flag(RegionState.FLAG_FOREST_LOW, true);
                out.event(HistoryEvent.of(nowWall, HistoryEventType.DEFORESTATION, r.key(), f, "", ""));
            }
            if (!r.flag(RegionState.FLAG_FOREST_CRITICAL) && f < eco.criticalForestThreshold()) {
                r.flag(RegionState.FLAG_FOREST_CRITICAL, true);
                out.event(HistoryEvent.of(nowWall, HistoryEventType.MASS_DEFORESTATION, r.key(), f, "", ""));
            }
            if (r.flag(RegionState.FLAG_FOREST_LOW) && f >= eco.forestRestoredThreshold()) {
                r.flag(RegionState.FLAG_FOREST_LOW, false);
                r.flag(RegionState.FLAG_FOREST_CRITICAL, false);
                out.event(HistoryEvent.of(nowWall, HistoryEventType.FOREST_RESTORED, r.key(), f, "", ""));
            }
        }
        LivingWorldConfig.Wildlife wl = cfg.wildlife();
        if (!wl.enabled()) {
            return;
        }
        for (SpeciesGroup g : SpeciesGroup.all()) {
            if (!cfg.groupPresent(r.profile(), g)) {
                continue;
            }
            int flag = RegionState.wildlifeLowFlag(g);
            double pop = r.population(g);
            if (!r.flag(flag) && pop < wl.declineThreshold()) {
                r.flag(flag, true);
                out.event(HistoryEvent.of(nowWall, HistoryEventType.WILDLIFE_DECLINE, r.key(), pop, g.name(), ""));
            } else if (r.flag(flag) && pop >= wl.recoveredThreshold()) {
                r.flag(flag, false);
                out.event(HistoryEvent.of(nowWall, HistoryEventType.WILDLIFE_RECOVERED, r.key(), pop, g.name(), ""));
            }
        }
    }

    // ==================================================================== math helpers

    /** Exponential moving average over a time constant of {@code tauDays}. */
    static double ema(double current, double sample, double dtDays, double tauDays) {
        double alpha = 1 - Math.exp(-dtDays / Math.max(1e-6, tauDays));
        double v = current + (sample - current) * alpha;
        return Double.isFinite(v) ? v : 0;
    }

    /** Analytic solution of the logistic equation after {@code dt}. */
    static double logistic(double p, double capacity, double rate, double dt) {
        if (capacity <= 0) {
            return 0;
        }
        if (p <= 0) {
            return 0;
        }
        double v = capacity / (1 + ((capacity - p) / p) * Math.exp(-rate * dt));
        return Double.isFinite(v) ? v : capacity;
    }
}
