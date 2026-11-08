package me.fokend.livingworld.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.SpeciesGroup;

/**
 * Immutable, validated configuration snapshot. Pure Java (no Bukkit types) so the simulation can be
 * unit-tested. A new instance replaces the old one on {@code /lwadmin reload}; nobody caches pieces of it.
 */
public record LivingWorldConfig(
        General general,
        Simulation simulation,
        Ecology ecology,
        Wildlife wildlife,
        Development development,
        Influence influence,
        History history,
        Cache cache,
        Tracking tracking,
        Interface ui,
        Map<BiomeProfile, ProfileSettings> profiles) {

    public record General(Set<String> enabledWorlds, int regionSizeChunks, boolean debug) {
        public int regionSizeBlocks() {
            return regionSizeChunks * 16;
        }

        /** Area relative to the reference region of 16x16 chunks (256x256 blocks). */
        public double areaScale() {
            double side = regionSizeChunks / 16.0;
            return side * side;
        }

        public boolean worldEnabled(String world) {
            return enabledWorlds.isEmpty() || enabledWorlds.contains(world);
        }
    }

    public record Simulation(int stepIntervalSeconds, int maxRegionsPerTick, double timeScale, double maxCatchupDays,
                             int autosaveSeconds, int presenceIntervalTicks) {
    }

    public record Ecology(boolean enabled, double forestRegenPerDay, double minRegenMultiplier,
                          double abandonedRegenBonus, double stabilityHalfLifeHours, double weightForest,
                          double weightWildlife, double weightExploitation, double pressureHalfSaturation,
                          double pressureTauDays, double huntPressureWeight, double orePressureWeight,
                          double oreCapacity, double deforestationThreshold, double criticalForestThreshold,
                          double forestRestoredThreshold, double minForestCapacityForEvents) {
    }

    public record Wildlife(boolean enabled, Map<SpeciesGroup, Double> baseHerd, Map<SpeciesGroup, Double> growthPerDay,
                           double immigrationPerDay, double minCarryingCapacity, double fishCatchWeight,
                           Set<String> countedSpawnReasons, Map<SpeciesGroup, Set<String>> groupEntities,
                           boolean spawnSuppression, double suppressionBelow, double maxSuppression,
                           double declineThreshold, double recoveredThreshold, double initialPopulation) {
    }

    public record Development(boolean enabled, double activityTauDays, double buildWeight, double presenceWeight,
                              double tradeWeight, double graceDays, double decayHalfLifeDays,
                              double developingThreshold, double activeThreshold, double activePresence,
                              double hubThreshold, double hubTrade, double keepRatio, double abandonPeakMin,
                              double abandonDays, double revivePresence, double reclaimDevelopment,
                              double reclaimForest, double overexploitEnterStability,
                              double overexploitEnterExploitation, double overexploitExitStability,
                              double overexploitExitExploitation, double confirmMinutes,
                              int maxBuildActionsPerMinute) {
    }

    public record Influence(boolean enabled, boolean useScoreboardTeams, double presencePerMinute, double perBuildAction,
                            double perTrade, double perTreeGrown, double halfLifeDays, double minScore,
                            double dominanceMin, double dominanceRatio, double spreadMin, double spreadPerDay,
                            double spreadCap) {
    }

    public record History(boolean enabled, int maxEvents, int maxAgeDays, int memoryRecent, int maxPerHour,
                          boolean notifyRegionPlayers, boolean broadcastMajor, List<Integer> stewardMilestones) {
    }

    public record Cache(int maxLoadedRegions, int idleEvictMinutes, int maxPendingLoads, int dbQueueCapacity,
                        int saplingMemory, int placedLogMemory, boolean dormantSweep, int dormantSweepMinutes,
                        int dormantSweepBatch, double dormantMinStaleHours) {
    }

    public record Tracking(boolean blocks, boolean hunting, boolean fishing, boolean trades, boolean presence,
                           boolean ignoreCreative) {
    }

    public record Interface(boolean actionBarOnEnter, boolean bossBarDefault, boolean particles, double particleChance) {
    }

    public record ProfileSettings(double forestCapacity, Map<SpeciesGroup, Double> wildlife) {
        public double wildlifeMultiplier(SpeciesGroup group) {
            Double v = wildlife.get(group);
            return v == null ? 0 : v;
        }
    }

    public ProfileSettings profile(BiomeProfile profile) {
        ProfileSettings p = profiles.get(profile);
        return p != null ? p : profiles.get(BiomeProfile.OTHER);
    }

    /** Forest capacity in logs for a region of the configured size. */
    public double forestCapacityLogs(BiomeProfile profile) {
        return Math.max(1.0, profile(profile).forestCapacity() * general.areaScale());
    }

    /** Effective herd size represented by population 1.0 of a group in a profile. */
    public double herdSize(BiomeProfile profile, SpeciesGroup group) {
        double base = wildlife.baseHerd().getOrDefault(group, 20.0);
        return Math.max(1.0, base * profile(profile).wildlifeMultiplier(group) * general.areaScale());
    }

    public boolean groupPresent(BiomeProfile profile, SpeciesGroup group) {
        return profile(profile).wildlifeMultiplier(group) > 0.0;
    }

    public static Map<SpeciesGroup, Double> groupMap(double grazers, double small, double predators, double aquatic) {
        Map<SpeciesGroup, Double> m = new EnumMap<>(SpeciesGroup.class);
        m.put(SpeciesGroup.GRAZERS, grazers);
        m.put(SpeciesGroup.SMALL_GAME, small);
        m.put(SpeciesGroup.PREDATORS, predators);
        m.put(SpeciesGroup.AQUATIC, aquatic);
        return m;
    }
}
