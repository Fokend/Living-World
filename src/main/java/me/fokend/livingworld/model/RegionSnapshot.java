package me.fokend.livingworld.model;

import java.util.List;
import java.util.UUID;

/**
 * Immutable copy of all persistent region data. Created on the main thread and handed to the
 * storage thread; never shares mutable state with {@link RegionState}.
 */
public record RegionSnapshot(
        RegionKey key,
        BiomeProfile profile,
        String customName,
        DevelopmentState state,
        DevelopmentState candidateState,
        long candidateSince,
        double development,
        double peakDevelopment,
        double forest,
        double stability,
        double exploitation,
        double[] populations,
        double presenceEma,
        double buildEma,
        double tradeEma,
        double pressureEma,
        long lastActivity,
        long lastSimulated,
        long createdAt,
        long totalLogsCut,
        long totalTreesGrown,
        long totalKills,
        long totalTrades,
        long totalBuild,
        int flags,
        String dominantGroup,
        List<Influence> influence,
        List<Steward> stewards) {

    public record Influence(String groupId, String displayName, double score) {
    }

    public record Steward(UUID playerId, String playerName, int treesGrown, int lastMilestone) {
    }

    public RegionSnapshot {
        populations = populations.clone();
        influence = List.copyOf(influence);
        stewards = List.copyOf(stewards);
    }
}
