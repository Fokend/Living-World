package me.fokend.livingworld.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Raw activity collected between two simulation steps. Events only increment counters here; all
 * heavier math happens once per step. Instances are reused (cleared after each step) to avoid
 * per-step allocations. Main thread only.
 *
 * <p>The influence and steward maps are bounded: a new key is only accepted while the map is smaller
 * than {@link #MAX_CONTRIBUTORS}. In practice they only ever contain players that were active in the
 * region during one step.</p>
 */
public final class Accumulators {

    public static final int MAX_CONTRIBUTORS = 64;

    public double logsCut;
    public double treeLogsGrown;
    public int treesGrown;
    public double oresMined;
    public final double[] kills = new double[SpeciesGroup.count()];
    public double buildActions;
    public double trades;
    public double presenceSeconds;

    /** groupId -> [displayName, amount]. */
    private final Map<String, InfluenceContribution> influence = new HashMap<>();
    /** player -> trees grown from their saplings. */
    private final Map<UUID, StewardContribution> stewards = new HashMap<>();

    public static final class InfluenceContribution {
        public String displayName;
        public double amount;
    }

    public static final class StewardContribution {
        public String playerName;
        public int trees;
    }

    public void addInfluence(String groupId, String displayName, double amount) {
        if (groupId == null || amount <= 0) {
            return;
        }
        InfluenceContribution c = influence.get(groupId);
        if (c == null) {
            if (influence.size() >= MAX_CONTRIBUTORS) {
                return;
            }
            c = new InfluenceContribution();
            influence.put(groupId, c);
        }
        c.displayName = displayName;
        c.amount += amount;
    }

    public void addSteward(UUID playerId, String playerName, int trees) {
        if (playerId == null || trees <= 0) {
            return;
        }
        StewardContribution c = stewards.get(playerId);
        if (c == null) {
            if (stewards.size() >= MAX_CONTRIBUTORS) {
                return;
            }
            c = new StewardContribution();
            stewards.put(playerId, c);
        }
        c.playerName = playerName;
        c.trees += trees;
    }

    public Map<String, InfluenceContribution> influence() {
        return influence;
    }

    public Map<UUID, StewardContribution> stewards() {
        return stewards;
    }

    public boolean hasHumanActivity() {
        return presenceSeconds > 0 || buildActions > 0 || trades > 0;
    }

    public boolean isEmpty() {
        if (logsCut != 0 || treeLogsGrown != 0 || treesGrown != 0 || oresMined != 0 || buildActions != 0
                || trades != 0 || presenceSeconds != 0 || !influence.isEmpty() || !stewards.isEmpty()) {
            return false;
        }
        for (double k : kills) {
            if (k != 0) {
                return false;
            }
        }
        return true;
    }

    /** Adds everything from {@code other} into this instance (used when a pending region finishes loading). */
    public void mergeFrom(Accumulators other) {
        logsCut += other.logsCut;
        treeLogsGrown += other.treeLogsGrown;
        treesGrown += other.treesGrown;
        oresMined += other.oresMined;
        for (int i = 0; i < kills.length; i++) {
            kills[i] += other.kills[i];
        }
        buildActions += other.buildActions;
        trades += other.trades;
        presenceSeconds += other.presenceSeconds;
        other.influence.forEach((id, c) -> addInfluence(id, c.displayName, c.amount));
        other.stewards.forEach((id, c) -> addSteward(id, c.playerName, c.trees));
    }

    public void clear() {
        logsCut = 0;
        treeLogsGrown = 0;
        treesGrown = 0;
        oresMined = 0;
        java.util.Arrays.fill(kills, 0);
        buildActions = 0;
        trades = 0;
        presenceSeconds = 0;
        influence.clear();
        stewards.clear();
    }
}
