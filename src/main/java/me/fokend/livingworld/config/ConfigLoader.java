package me.fokend.livingworld.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.SpeciesGroup;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Reads {@code config.yml} into an immutable {@link LivingWorldConfig}. Every value is range-checked:
 * invalid or out-of-range values fall back to the default (or are clamped) and produce a warning instead
 * of crashing the plugin or silently producing absurd simulation behaviour.
 */
public final class ConfigLoader {

    private final ConfigurationSection root;
    private final List<String> warnings = new ArrayList<>();

    public ConfigLoader(ConfigurationSection root) {
        this.root = root;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public LivingWorldConfig load() {
        return new LivingWorldConfig(general(), simulation(), ecology(), wildlife(), development(), influence(),
                history(), cache(), tracking(), ui(), profiles());
    }

    // ------------------------------------------------------------------ sections

    private LivingWorldConfig.General general() {
        LivingWorldConfig.General d = Defaults.general();
        Set<String> worlds = new LinkedHashSet<>(root.isList("general.enabled-worlds")
                ? root.getStringList("general.enabled-worlds") : d.enabledWorlds());
        worlds.removeIf(w -> w == null || w.isBlank());
        return new LivingWorldConfig.General(Set.copyOf(worlds),
                i("general.region-size-chunks", d.regionSizeChunks(), 2, 64),
                b("general.debug", d.debug()));
    }

    private LivingWorldConfig.Simulation simulation() {
        LivingWorldConfig.Simulation d = Defaults.simulation();
        return new LivingWorldConfig.Simulation(
                i("simulation.step-interval-seconds", d.stepIntervalSeconds(), 10, 3600),
                i("simulation.max-regions-per-tick", d.maxRegionsPerTick(), 10, 5000),
                dbl("simulation.time-scale", d.timeScale(), 0.01, 1000),
                dbl("simulation.max-catchup-days", d.maxCatchupDays(), 1, 365),
                i("simulation.autosave-seconds", d.autosaveSeconds(), 15, 3600),
                i("simulation.presence-interval-ticks", d.presenceIntervalTicks(), 20, 1200));
    }

    private LivingWorldConfig.Ecology ecology() {
        LivingWorldConfig.Ecology d = Defaults.ecology();
        String p = "ecology.";
        double wF = dbl(p + "stability-weights.forest", d.weightForest(), 0, 10);
        double wW = dbl(p + "stability-weights.wildlife", d.weightWildlife(), 0, 10);
        double wX = dbl(p + "stability-weights.exploitation", d.weightExploitation(), 0, 10);
        if (wF + wW + wX <= 0) {
            warn(p + "stability-weights", "all weights are zero, using defaults");
            wF = d.weightForest();
            wW = d.weightWildlife();
            wX = d.weightExploitation();
        }
        double deforest = dbl(p + "events.deforestation-below", d.deforestationThreshold(), 0.05, 0.95);
        double critical = dbl(p + "events.critical-below", d.criticalForestThreshold(), 0.01, 0.9);
        double restored = dbl(p + "events.restored-above", d.forestRestoredThreshold(), 0.1, 1.0);
        if (critical >= deforest) {
            warn(p + "events.critical-below", "must be lower than deforestation-below, using defaults");
            critical = Math.min(d.criticalForestThreshold(), deforest * 0.5);
        }
        if (restored <= deforest) {
            warn(p + "events.restored-above", "must be higher than deforestation-below (hysteresis), adjusted");
            restored = Math.min(1.0, deforest + 0.2);
        }
        return new LivingWorldConfig.Ecology(
                b(p + "enabled", d.enabled()),
                dbl(p + "forest-regen-per-day", d.forestRegenPerDay(), 0, 1),
                dbl(p + "min-regen-multiplier", d.minRegenMultiplier(), 0, 1),
                dbl(p + "abandoned-regen-bonus", d.abandonedRegenBonus(), 1, 10),
                dbl(p + "stability-half-life-hours", d.stabilityHalfLifeHours(), 0.1, 24 * 60),
                wF, wW, wX,
                dbl(p + "pressure-half-saturation", d.pressureHalfSaturation(), 0.001, 10),
                dbl(p + "pressure-memory-days", d.pressureTauDays(), 0.05, 60),
                dbl(p + "hunt-pressure-weight", d.huntPressureWeight(), 0, 10),
                dbl(p + "ore-pressure-weight", d.orePressureWeight(), 0, 10),
                dbl(p + "ore-capacity", d.oreCapacity(), 1, 1_000_000),
                deforest, critical, restored,
                dbl(p + "events.min-forest-capacity", d.minForestCapacityForEvents(), 0, 1_000_000));
    }

    private LivingWorldConfig.Wildlife wildlife() {
        LivingWorldConfig.Wildlife d = Defaults.wildlife();
        String p = "wildlife.";
        Map<SpeciesGroup, Double> herd = new EnumMap<>(SpeciesGroup.class);
        Map<SpeciesGroup, Double> growth = new EnumMap<>(SpeciesGroup.class);
        Map<SpeciesGroup, Set<String>> entities = new EnumMap<>(SpeciesGroup.class);
        for (SpeciesGroup g : SpeciesGroup.all()) {
            String gp = p + "groups." + g.name().toLowerCase(Locale.ROOT) + ".";
            herd.put(g, dbl(gp + "herd-size", d.baseHerd().get(g), 1, 100_000));
            growth.put(g, dbl(gp + "growth-per-day", d.growthPerDay().get(g), 0, 20));
            Set<String> list = new LinkedHashSet<>();
            List<String> raw = root.isList(gp + "entities") ? root.getStringList(gp + "entities") : List.copyOf(d.groupEntities().get(g));
            for (String e : raw) {
                if (e != null && !e.isBlank()) {
                    list.add(e.trim().toUpperCase(Locale.ROOT));
                }
            }
            entities.put(g, Set.copyOf(list));
        }
        Set<String> reasons = new LinkedHashSet<>();
        List<String> rawReasons = root.isList(p + "counted-spawn-reasons")
                ? root.getStringList(p + "counted-spawn-reasons") : List.copyOf(d.countedSpawnReasons());
        for (String r : rawReasons) {
            if (r != null && !r.isBlank()) {
                reasons.add(r.trim().toUpperCase(Locale.ROOT));
            }
        }
        double decline = dbl(p + "events.decline-below", d.declineThreshold(), 0.01, 0.9);
        double recovered = dbl(p + "events.recovered-above", d.recoveredThreshold(), 0.05, 1.0);
        if (recovered <= decline) {
            warn(p + "events.recovered-above", "must be higher than decline-below (hysteresis), adjusted");
            recovered = Math.min(1.0, decline + 0.3);
        }
        return new LivingWorldConfig.Wildlife(
                b(p + "enabled", d.enabled()),
                Map.copyOf(herd), Map.copyOf(growth),
                dbl(p + "immigration-per-day", d.immigrationPerDay(), 0, 5),
                dbl(p + "min-carrying-capacity", d.minCarryingCapacity(), 0.05, 1),
                dbl(p + "fish-catch-weight", d.fishCatchWeight(), 0, 10),
                Set.copyOf(reasons), Map.copyOf(entities),
                b(p + "spawn-suppression.enabled", d.spawnSuppression()),
                dbl(p + "spawn-suppression.below-population", d.suppressionBelow(), 0.01, 1),
                dbl(p + "spawn-suppression.max-chance", d.maxSuppression(), 0, 0.95),
                decline, recovered,
                dbl(p + "initial-population", d.initialPopulation(), 0.05, 1));
    }

    private LivingWorldConfig.Development development() {
        LivingWorldConfig.Development d = Defaults.development();
        String p = "development.";
        double developing = dbl(p + "thresholds.developing", d.developingThreshold(), 0.5, 100);
        double active = dbl(p + "thresholds.active", d.activeThreshold(), 0.5, 100);
        double hub = dbl(p + "thresholds.commercial-hub", d.hubThreshold(), 0.5, 100);
        if (!(developing < active && active <= hub)) {
            warn(p + "thresholds", "must satisfy developing < active <= commercial-hub, using defaults");
            developing = d.developingThreshold();
            active = d.activeThreshold();
            hub = d.hubThreshold();
        }
        double enterS = dbl(p + "overexploited.enter-stability-below", d.overexploitEnterStability(), 0, 1);
        double exitS = dbl(p + "overexploited.exit-stability-above", d.overexploitExitStability(), 0, 1);
        double enterX = dbl(p + "overexploited.enter-exploitation-above", d.overexploitEnterExploitation(), 0, 1);
        double exitX = dbl(p + "overexploited.exit-exploitation-below", d.overexploitExitExploitation(), 0, 1);
        if (exitS <= enterS || exitX >= enterX) {
            warn(p + "overexploited", "exit thresholds must be stricter than enter thresholds, using defaults");
            enterS = d.overexploitEnterStability();
            exitS = d.overexploitExitStability();
            enterX = d.overexploitEnterExploitation();
            exitX = d.overexploitExitExploitation();
        }
        return new LivingWorldConfig.Development(
                b(p + "enabled", d.enabled()),
                dbl(p + "activity-memory-days", d.activityTauDays(), 0.1, 60),
                dbl(p + "weights.build", d.buildWeight(), 0, 100),
                dbl(p + "weights.presence", d.presenceWeight(), 0, 100),
                dbl(p + "weights.trade", d.tradeWeight(), 0, 100),
                dbl(p + "decay.grace-days", d.graceDays(), 0, 365),
                dbl(p + "decay.half-life-days", d.decayHalfLifeDays(), 0.5, 3650),
                developing, active,
                dbl(p + "thresholds.active-presence-hours", d.activePresence(), 0, 1000),
                hub,
                dbl(p + "thresholds.hub-trades-per-day", d.hubTrade(), 0, 1_000_000),
                dbl(p + "thresholds.keep-ratio", d.keepRatio(), 0.1, 1),
                dbl(p + "abandonment.min-peak-development", d.abandonPeakMin(), 0, 100),
                dbl(p + "abandonment.inactive-days", d.abandonDays(), 0.5, 3650),
                dbl(p + "abandonment.revive-presence-hours", d.revivePresence(), 0.01, 1000),
                dbl(p + "abandonment.reclaim-development-below", d.reclaimDevelopment(), 0, 100),
                dbl(p + "abandonment.reclaim-forest-above", d.reclaimForest(), 0, 1),
                enterS, enterX, exitS, exitX,
                dbl(p + "transition-confirm-minutes", d.confirmMinutes(), 0, 60 * 24 * 7),
                i(p + "max-build-actions-per-minute", d.maxBuildActionsPerMinute(), 1, 10_000));
    }

    private LivingWorldConfig.Influence influence() {
        LivingWorldConfig.Influence d = Defaults.influence();
        String p = "influence.";
        return new LivingWorldConfig.Influence(
                b(p + "enabled", d.enabled()),
                b(p + "scoreboard-team-adapter", d.useScoreboardTeams()),
                dbl(p + "gain.presence-per-minute", d.presencePerMinute(), 0, 100),
                dbl(p + "gain.per-build-action", d.perBuildAction(), 0, 100),
                dbl(p + "gain.per-trade", d.perTrade(), 0, 100),
                dbl(p + "gain.per-tree-grown", d.perTreeGrown(), 0, 100),
                dbl(p + "half-life-days", d.halfLifeDays(), 0.1, 3650),
                dbl(p + "min-score", d.minScore(), 0, 1000),
                dbl(p + "dominance.min-score", d.dominanceMin(), 0, 1_000_000),
                dbl(p + "dominance.ratio", d.dominanceRatio(), 1, 100),
                dbl(p + "spread.min-score", d.spreadMin(), 0, 1_000_000),
                dbl(p + "spread.per-day", d.spreadPerDay(), 0, 1),
                dbl(p + "spread.cap", d.spreadCap(), 0, 1));
    }

    private LivingWorldConfig.History history() {
        LivingWorldConfig.History d = Defaults.history();
        String p = "history.";
        List<Integer> milestones = new ArrayList<>();
        List<Integer> raw = root.isList(p + "steward-milestones") ? root.getIntegerList(p + "steward-milestones")
                : d.stewardMilestones();
        for (Integer m : raw) {
            if (m != null && m > 0 && !milestones.contains(m)) {
                milestones.add(m);
            }
        }
        Collections.sort(milestones);
        return new LivingWorldConfig.History(
                b(p + "enabled", d.enabled()),
                i(p + "max-events", d.maxEvents(), 100, 1_000_000),
                i(p + "max-age-days", d.maxAgeDays(), 1, 36500),
                i(p + "memory-recent", d.memoryRecent(), 10, 1000),
                i(p + "max-events-per-hour", d.maxPerHour(), 1, 10_000),
                b(p + "notify-region-players", d.notifyRegionPlayers()),
                b(p + "broadcast-major", d.broadcastMajor()),
                List.copyOf(milestones));
    }

    private LivingWorldConfig.Cache cache() {
        LivingWorldConfig.Cache d = Defaults.cache();
        String p = "cache.";
        return new LivingWorldConfig.Cache(
                i(p + "max-loaded-regions", d.maxLoadedRegions(), 64, 100_000),
                i(p + "idle-evict-minutes", d.idleEvictMinutes(), 1, 1440),
                i(p + "max-pending-loads", d.maxPendingLoads(), 8, 10_000),
                i(p + "storage-queue-capacity", d.dbQueueCapacity(), 16, 100_000),
                i(p + "sapling-memory", d.saplingMemory(), 0, 1_000_000),
                i(p + "placed-log-memory", d.placedLogMemory(), 0, 1_000_000),
                b(p + "dormant-sweep.enabled", d.dormantSweep()),
                i(p + "dormant-sweep.interval-minutes", d.dormantSweepMinutes(), 1, 1440),
                i(p + "dormant-sweep.batch-size", d.dormantSweepBatch(), 1, 1000),
                dbl(p + "dormant-sweep.min-stale-hours", d.dormantMinStaleHours(), 0.1, 24 * 365));
    }

    private LivingWorldConfig.Tracking tracking() {
        LivingWorldConfig.Tracking d = Defaults.tracking();
        String p = "tracking.";
        return new LivingWorldConfig.Tracking(b(p + "blocks", d.blocks()), b(p + "hunting", d.hunting()),
                b(p + "fishing", d.fishing()), b(p + "trades", d.trades()), b(p + "presence", d.presence()),
                b(p + "ignore-creative", d.ignoreCreative()));
    }

    private LivingWorldConfig.Interface ui() {
        LivingWorldConfig.Interface d = Defaults.ui();
        String p = "interface.";
        return new LivingWorldConfig.Interface(b(p + "actionbar-on-enter", d.actionBarOnEnter()),
                b(p + "bossbar-default", d.bossBarDefault()), b(p + "ambient-particles", d.particles()),
                dbl(p + "particle-chance", d.particleChance(), 0, 1));
    }

    private Map<BiomeProfile, LivingWorldConfig.ProfileSettings> profiles() {
        Map<BiomeProfile, LivingWorldConfig.ProfileSettings> defaults = Defaults.profiles();
        Map<BiomeProfile, LivingWorldConfig.ProfileSettings> out = new EnumMap<>(BiomeProfile.class);
        ConfigurationSection section = root.getConfigurationSection("biome-profiles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (BiomeProfile.parse(key.toUpperCase(Locale.ROOT), null) == null) {
                    warn("biome-profiles." + key, "unknown profile, ignored");
                }
            }
        }
        for (BiomeProfile profile : BiomeProfile.values()) {
            LivingWorldConfig.ProfileSettings d = defaults.get(profile);
            String p = "biome-profiles." + profile.name().toLowerCase(Locale.ROOT) + ".";
            Map<SpeciesGroup, Double> wl = new EnumMap<>(SpeciesGroup.class);
            for (SpeciesGroup g : SpeciesGroup.all()) {
                wl.put(g, dbl(p + "wildlife." + g.name().toLowerCase(Locale.ROOT), d.wildlifeMultiplier(g), 0, 100));
            }
            out.put(profile, new LivingWorldConfig.ProfileSettings(
                    dbl(p + "forest-capacity", d.forestCapacity(), 1, 1_000_000), Map.copyOf(wl)));
        }
        return Map.copyOf(out);
    }

    // ------------------------------------------------------------------ primitives

    private void warn(String path, String message) {
        warnings.add(path + ": " + message);
    }

    private boolean b(String path, boolean def) {
        if (!root.contains(path)) {
            return def;
        }
        if (!root.isBoolean(path)) {
            warn(path, "expected true/false, got '" + root.get(path) + "', using " + def);
            return def;
        }
        return root.getBoolean(path);
    }

    private int i(String path, int def, int min, int max) {
        if (!root.contains(path)) {
            return def;
        }
        Object raw = root.get(path);
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            warn(path, "expected an integer, got '" + raw + "', using " + def);
            return def;
        }
        long v = n.longValue();
        if (v < min || v > max) {
            int clamped = (int) Math.max(min, Math.min(max, v));
            warn(path, "value " + v + " outside [" + min + ", " + max + "], clamped to " + clamped);
            return clamped;
        }
        return (int) v;
    }

    private double dbl(String path, double def, double min, double max) {
        if (!root.contains(path)) {
            return def;
        }
        Object raw = root.get(path);
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue())) {
            warn(path, "expected a number, got '" + raw + "', using " + def);
            return def;
        }
        double v = n.doubleValue();
        if (v < min || v > max) {
            double clamped = Math.max(min, Math.min(max, v));
            warn(path, "value " + v + " outside [" + min + ", " + max + "], clamped to " + clamped);
            return clamped;
        }
        return v;
    }
}
