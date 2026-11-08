package me.fokend.livingworld.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Mutable live state of one region. Confined to the server main thread: listeners, the simulation
 * task and commands touch it only from there. The storage thread only ever sees {@link RegionSnapshot}s.
 *
 * <p>Holds no Bukkit objects. All timestamps named {@code *Sim} are in simulation-clock millis
 * (server uptime scaled by {@code time-scale}), {@code createdAt} is wall-clock.</p>
 */
public final class RegionState {

    // Latch flags preventing duplicate chronicle entries (hysteresis).
    public static final int FLAG_FOREST_LOW = 1;
    public static final int FLAG_FOREST_CRITICAL = 1 << 1;
    private static final int FLAG_WILDLIFE_LOW_BASE = 1 << 2; // one bit per species group

    public static final int MAX_INFLUENCE_ENTRIES = 16;
    public static final int MAX_STEWARD_ENTRIES = 16;

    private final RegionKey key;
    private BiomeProfile profile;
    private String customName;

    private DevelopmentState state = DevelopmentState.WILDERNESS;
    private DevelopmentState candidateState;
    private long candidateSince;

    private double development;
    private double peakDevelopment;

    private double forest = 1.0;
    private double stability = 1.0;
    private double exploitation;
    private final double[] populations = new double[SpeciesGroup.count()];

    private double presenceEma;
    private double buildEma;
    private double tradeEma;
    private double pressureEma;

    private long lastActivitySim;
    private long lastSimulatedSim;
    private long createdAt;

    private long totalLogsCut;
    private long totalTreesGrown;
    private long totalKills;
    private long totalTrades;
    private long totalBuild;

    private int flags;
    private String dominantGroup;

    private final List<InfluenceEntry> influence = new ArrayList<>(4);
    private final List<StewardEntry> stewards = new ArrayList<>(2);

    // ---- transient runtime fields (not persisted) ----
    private final Accumulators accumulators = new Accumulators();
    private boolean dirty;
    private long lastAccessWall;
    private long lastPresenceWall;
    private double lastStepDays;
    private double forestTrend;
    private double stabilityTrend;

    public RegionState(RegionKey key, BiomeProfile profile, long nowSim, long nowWall, double initialPopulation) {
        this.key = key;
        this.profile = profile;
        this.lastSimulatedSim = nowSim;
        this.lastActivitySim = 0L;
        this.createdAt = nowWall;
        java.util.Arrays.fill(populations, initialPopulation);
        this.dirty = true;
        this.lastAccessWall = nowWall;
    }

    public static RegionState fromSnapshot(RegionSnapshot s, long nowWall) {
        RegionState r = new RegionState(s.key(), s.profile(), s.lastSimulated(), s.createdAt(), 1.0);
        r.customName = s.customName();
        r.state = s.state();
        r.candidateState = s.candidateState();
        r.candidateSince = s.candidateSince();
        r.development = clamp(s.development(), 0, 100);
        r.peakDevelopment = clamp(s.peakDevelopment(), r.development, 100);
        r.forest = clamp01(s.forest());
        r.stability = clamp01(s.stability());
        r.exploitation = clamp01(s.exploitation());
        double[] pops = s.populations();
        for (int i = 0; i < r.populations.length; i++) {
            r.populations[i] = i < pops.length ? clamp01(pops[i]) : 1.0;
        }
        r.presenceEma = nonNegative(s.presenceEma());
        r.buildEma = nonNegative(s.buildEma());
        r.tradeEma = nonNegative(s.tradeEma());
        r.pressureEma = nonNegative(s.pressureEma());
        r.lastActivitySim = Math.max(0, s.lastActivity());
        r.lastSimulatedSim = Math.max(0, s.lastSimulated());
        r.totalLogsCut = Math.max(0, s.totalLogsCut());
        r.totalTreesGrown = Math.max(0, s.totalTreesGrown());
        r.totalKills = Math.max(0, s.totalKills());
        r.totalTrades = Math.max(0, s.totalTrades());
        r.totalBuild = Math.max(0, s.totalBuild());
        r.flags = s.flags();
        r.dominantGroup = s.dominantGroup();
        for (RegionSnapshot.Influence i : s.influence()) {
            if (r.influence.size() < MAX_INFLUENCE_ENTRIES && Double.isFinite(i.score()) && i.score() > 0) {
                r.influence.add(new InfluenceEntry(i.groupId(), i.displayName(), i.score()));
            }
        }
        for (RegionSnapshot.Steward st : s.stewards()) {
            if (r.stewards.size() < MAX_STEWARD_ENTRIES) {
                r.stewards.add(new StewardEntry(st.playerId(), st.playerName(), Math.max(0, st.treesGrown()),
                        Math.max(0, st.lastMilestone())));
            }
        }
        r.dirty = false;
        r.lastAccessWall = nowWall;
        return r;
    }

    public RegionSnapshot toSnapshot() {
        List<RegionSnapshot.Influence> inf = new ArrayList<>(influence.size());
        for (InfluenceEntry e : influence) {
            inf.add(new RegionSnapshot.Influence(e.groupId(), e.displayName(), e.score()));
        }
        List<RegionSnapshot.Steward> st = new ArrayList<>(stewards.size());
        for (StewardEntry e : stewards) {
            st.add(new RegionSnapshot.Steward(e.playerId(), e.playerName(), e.treesGrown(), e.lastMilestone()));
        }
        return new RegionSnapshot(key, profile, customName, state, candidateState, candidateSince, development,
                peakDevelopment, forest, stability, exploitation, populations, presenceEma, buildEma, tradeEma,
                pressureEma, lastActivitySim, lastSimulatedSim, createdAt, totalLogsCut, totalTreesGrown, totalKills,
                totalTrades, totalBuild, flags, dominantGroup, inf, st);
    }

    // ------------------------------------------------------------------ influence helpers

    /** Adds influence; keeps the list bounded by dropping the weakest entry. */
    public void addInfluence(String groupId, String displayName, double amount) {
        if (amount <= 0 || !Double.isFinite(amount)) {
            return;
        }
        for (InfluenceEntry e : influence) {
            if (e.groupId().equals(groupId)) {
                e.score(e.score() + amount);
                e.displayName(displayName);
                return;
            }
        }
        if (influence.size() >= MAX_INFLUENCE_ENTRIES) {
            InfluenceEntry weakest = influence.stream().min(Comparator.comparingDouble((InfluenceEntry e) -> e.score())).orElseThrow();
            if (weakest.score() >= amount) {
                return;
            }
            influence.remove(weakest);
        }
        influence.add(new InfluenceEntry(groupId, displayName == null ? groupId : displayName, amount));
    }

    public double influenceOf(String groupId) {
        for (InfluenceEntry e : influence) {
            if (e.groupId().equals(groupId)) {
                return e.score();
            }
        }
        return 0;
    }

    public List<InfluenceEntry> influenceSorted() {
        List<InfluenceEntry> copy = new ArrayList<>(influence);
        copy.sort(Comparator.comparingDouble((InfluenceEntry e) -> e.score()).reversed());
        return copy;
    }

    public List<InfluenceEntry> influenceMutable() {
        return influence;
    }

    public StewardEntry steward(UUID playerId, String name) {
        for (StewardEntry e : stewards) {
            if (e.playerId().equals(playerId)) {
                e.playerName(name);
                return e;
            }
        }
        if (stewards.size() >= MAX_STEWARD_ENTRIES) {
            StewardEntry weakest = stewards.stream().min(Comparator.comparingInt((StewardEntry e) -> e.treesGrown())).orElseThrow();
            stewards.remove(weakest);
        }
        StewardEntry created = new StewardEntry(playerId, name, 0, 0);
        stewards.add(created);
        return created;
    }

    public List<StewardEntry> stewardsSorted() {
        List<StewardEntry> copy = new ArrayList<>(stewards);
        copy.sort(Comparator.comparingInt((StewardEntry e) -> e.treesGrown()).reversed());
        return copy;
    }

    // ------------------------------------------------------------------ flags

    public boolean flag(int flag) {
        return (flags & flag) != 0;
    }

    public void flag(int flag, boolean value) {
        flags = value ? (flags | flag) : (flags & ~flag);
    }

    public static int wildlifeLowFlag(SpeciesGroup group) {
        return FLAG_WILDLIFE_LOW_BASE << group.ordinal();
    }

    // ------------------------------------------------------------------ utils

    public static double clamp01(double v) {
        return clamp(v, 0, 1);
    }

    public static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) {
            return min;
        }
        return v < min ? min : (v > max ? max : v);
    }

    private static double nonNegative(double v) {
        return Double.isFinite(v) && v > 0 ? v : 0;
    }

    // ------------------------------------------------------------------ accessors

    public RegionKey key() { return key; }
    public BiomeProfile profile() { return profile; }
    public void profile(BiomeProfile profile) { this.profile = profile; }
    public String customName() { return customName; }
    public void customName(String customName) { this.customName = customName; }
    public DevelopmentState state() { return state; }
    public void state(DevelopmentState state) { this.state = state; }
    public DevelopmentState candidateState() { return candidateState; }
    public void candidateState(DevelopmentState s) { this.candidateState = s; }
    public long candidateSince() { return candidateSince; }
    public void candidateSince(long t) { this.candidateSince = t; }
    public double development() { return development; }
    public void development(double v) { this.development = clamp(v, 0, 100); this.peakDevelopment = Math.max(peakDevelopment, this.development); }
    public double peakDevelopment() { return peakDevelopment; }
    public void peakDevelopment(double v) { this.peakDevelopment = clamp(v, 0, 100); }
    public double forest() { return forest; }
    public void forest(double v) { this.forest = clamp01(v); }
    public double stability() { return stability; }
    public void stability(double v) { this.stability = clamp01(v); }
    public double exploitation() { return exploitation; }
    public void exploitation(double v) { this.exploitation = clamp01(v); }
    public double population(SpeciesGroup g) { return populations[g.ordinal()]; }
    public void population(SpeciesGroup g, double v) { populations[g.ordinal()] = clamp01(v); }
    public double presenceEma() { return presenceEma; }
    public void presenceEma(double v) { this.presenceEma = Math.max(0, v); }
    public double buildEma() { return buildEma; }
    public void buildEma(double v) { this.buildEma = Math.max(0, v); }
    public double tradeEma() { return tradeEma; }
    public void tradeEma(double v) { this.tradeEma = Math.max(0, v); }
    public double pressureEma() { return pressureEma; }
    public void pressureEma(double v) { this.pressureEma = Math.max(0, v); }
    public long lastActivitySim() { return lastActivitySim; }
    public void lastActivitySim(long t) { this.lastActivitySim = t; }
    public long lastSimulatedSim() { return lastSimulatedSim; }
    public void lastSimulatedSim(long t) { this.lastSimulatedSim = t; }
    public long createdAt() { return createdAt; }
    public long totalLogsCut() { return totalLogsCut; }
    public long totalTreesGrown() { return totalTreesGrown; }
    public long totalKills() { return totalKills; }
    public long totalTrades() { return totalTrades; }
    public long totalBuild() { return totalBuild; }
    public void addTotals(long logs, long trees, long kills, long trades, long build) {
        totalLogsCut += logs; totalTreesGrown += trees; totalKills += kills; totalTrades += trades; totalBuild += build;
    }
    public int flags() { return flags; }
    public String dominantGroup() { return dominantGroup; }
    public void dominantGroup(String g) { this.dominantGroup = g; }
    public Accumulators accumulators() { return accumulators; }
    public boolean dirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
    public void clearDirty() { this.dirty = false; }
    public long lastAccessWall() { return lastAccessWall; }
    public void touch(long nowWall) { this.lastAccessWall = nowWall; }
    public void forceIdle() { this.lastAccessWall = 0L; }
    public long lastPresenceWall() { return lastPresenceWall; }
    public void lastPresenceWall(long t) { this.lastPresenceWall = t; }
    public double lastStepDays() { return lastStepDays; }
    public void lastStepDays(double d) { this.lastStepDays = d; }
    public double forestTrend() { return forestTrend; }
    public void forestTrend(double v) { this.forestTrend = v; }
    public double stabilityTrend() { return stabilityTrend; }
    public void stabilityTrend(double v) { this.stabilityTrend = v; }
}
