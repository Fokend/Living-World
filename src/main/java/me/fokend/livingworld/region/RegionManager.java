package me.fokend.livingworld.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.history.HistoryService;
import me.fokend.livingworld.model.Accumulators;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionSnapshot;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.sim.RegionSimulator;
import me.fokend.livingworld.sim.SimClock;
import me.fokend.livingworld.sim.StepOutput;
import me.fokend.livingworld.storage.StorageService;

/**
 * Owns the in-memory region cache and its lifecycle: lazy asynchronous loading, catch-up simulation,
 * sliced simulation passes, batched saving and eviction. Main thread only (results from the storage
 * thread are delivered back to the main thread by {@link StorageService}).
 *
 * <h2>Memory bounds</h2>
 * <ul>
 *   <li>{@code loaded} — at most {@code cache.max-loaded-regions} (+ regions that are dirty and waiting
 *       for the next save); idle clean regions are evicted;</li>
 *   <li>{@code pending} — at most {@code cache.max-pending-loads}; activity for regions beyond that is dropped;</li>
 *   <li>{@code failed} — retry back-off entries, pruned on every maintenance run and bounded;</li>
 *   <li>{@code passQueue} — one entry per loaded region during a pass.</li>
 * </ul>
 * Nothing here ever touches chunks, blocks or entities.
 */
public final class RegionManager {

    /** Notified on the main thread for every development transition. */
    public interface TransitionListener {
        void onTransition(RegionState region, StepOutput.Transition transition);
    }

    private static final int MAX_FAILED_ENTRIES = 1024;
    private static final long FAILED_RETRY_MS = 60_000L;
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final class PendingLoad {
        final Accumulators accumulators = new Accumulators();
        final BiomeProfile hint;
        final boolean sweep;

        PendingLoad(BiomeProfile hint, boolean sweep) {
            this.hint = hint;
            this.sweep = sweep;
        }
    }

    private final Map<RegionKey, RegionState> loaded = new HashMap<>();
    private final Map<RegionKey, PendingLoad> pending = new HashMap<>();
    private final LinkedHashMap<RegionKey, Long> failed = new LinkedHashMap<>();
    private final ArrayDeque<RegionKey> passQueue = new ArrayDeque<>();
    private final StepOutput output = new StepOutput();

    private final StorageService storage;
    private final HistoryService history;
    private final SimClock clock;
    private final Logger logger;
    private LivingWorldConfig cfg;
    private RegionSimulator simulator;
    private TransitionListener transitionListener;

    private boolean saveInFlight;
    private long lastSaveRequest;
    private boolean sweepInFlight;
    private long nextPassAt;
    private long lastLoadErrorLog;

    // diagnostics
    private long stepsTotal;
    private long passesTotal;
    private long lastPassNanos;
    private long passNanosAccum;
    private long droppedActivity;
    private long evictedTotal;
    private long loadsTotal;

    public RegionManager(LivingWorldConfig cfg, StorageService storage, HistoryService history, SimClock clock,
                         Logger logger) {
        this.cfg = cfg;
        this.simulator = new RegionSimulator(cfg);
        this.storage = storage;
        this.history = history;
        this.clock = clock;
        this.logger = logger;
    }

    public void config(LivingWorldConfig cfg) {
        this.cfg = cfg;
        this.simulator = new RegionSimulator(cfg);
    }

    public LivingWorldConfig config() {
        return cfg;
    }

    public RegionSimulator simulator() {
        return simulator;
    }

    public void transitionListener(TransitionListener listener) {
        this.transitionListener = listener;
    }

    // ===================================================================== access

    public RegionKey keyAt(String world, int blockX, int blockZ) {
        return RegionKey.ofBlock(world, blockX, blockZ, cfg.general().regionSizeBlocks());
    }

    /** The region if it is resident in memory; never triggers a load. */
    public RegionState getLoaded(RegionKey key) {
        return loaded.get(key);
    }

    public boolean isPending(RegionKey key) {
        return pending.containsKey(key);
    }

    /**
     * Returns the accumulator that activity for {@code key} should be written to. If the region is not in
     * memory, an asynchronous load is started and activity is buffered until it completes.
     *
     * @param hint lazily evaluated biome profile, only used if the region turns out to be brand new
     * @return the accumulator, or null if the activity has to be dropped (too many pending loads)
     */
    public Accumulators activity(RegionKey key, Supplier<BiomeProfile> hint, long nowWall) {
        RegionState state = loaded.get(key);
        if (state != null) {
            state.touch(nowWall);
            return state.accumulators();
        }
        PendingLoad p = pending.get(key);
        if (p == null) {
            p = requestLoad(key, hint, false, nowWall);
            if (p == null) {
                droppedActivity++;
                return null;
            }
        }
        return p.accumulators;
    }

    /** Starts loading {@code key} if it is not resident or loading already. */
    public void ensureLoaded(RegionKey key, Supplier<BiomeProfile> hint, long nowWall) {
        RegionState state = loaded.get(key);
        if (state != null) {
            state.touch(nowWall);
        } else if (!pending.containsKey(key)) {
            requestLoad(key, hint, false, nowWall);
        }
    }

    private PendingLoad requestLoad(RegionKey key, Supplier<BiomeProfile> hint, boolean sweep, long nowWall) {
        if (pending.size() >= cfg.cache().maxPendingLoads()) {
            return null;
        }
        Long retryAt = failed.get(key);
        if (retryAt != null) {
            if (nowWall < retryAt) {
                return null;
            }
            failed.remove(key);
        }
        PendingLoad p = new PendingLoad(sweep ? BiomeProfile.OTHER : hint.get(), sweep);
        boolean accepted = storage.submit(st -> st.loadRegion(key),
                snapshot -> onLoaded(key, snapshot),
                error -> onLoadFailed(key, error));
        if (!accepted) {
            return null;
        }
        pending.put(key, p);
        return p;
    }

    private void onLoaded(RegionKey key, RegionSnapshot snapshot) {
        PendingLoad p = pending.remove(key);
        if (p == null || loaded.containsKey(key)) {
            return; // cancelled (e.g. config reload cleared state) or duplicate
        }
        if (snapshot == null && p.sweep) {
            return; // region was deleted meanwhile
        }
        long nowWall = System.currentTimeMillis();
        long nowSim = clock.now();
        RegionState state;
        if (snapshot != null) {
            state = RegionState.fromSnapshot(snapshot, nowWall);
            // Catch up on the time the region spent outside of memory (pure math, no world access).
            stepRegion(state, nowSim, nowWall);
        } else {
            state = new RegionState(key, p.hint, nowSim, nowWall, cfg.wildlife().initialPopulation());
        }
        state.accumulators().mergeFrom(p.accumulators);
        if (p.sweep && p.accumulators.isEmpty()) {
            state.forceIdle(); // evict right after the next save
        }
        loaded.put(key, state);
        loadsTotal++;
        if (loaded.size() > cfg.cache().maxLoadedRegions()) {
            evict(nowWall, true);
        }
    }

    private void onLoadFailed(RegionKey key, Throwable error) {
        pending.remove(key);
        if (failed.size() >= MAX_FAILED_ENTRIES) {
            Iterator<RegionKey> it = failed.keySet().iterator();
            it.next();
            it.remove();
        }
        long now = System.currentTimeMillis();
        failed.put(key, now + FAILED_RETRY_MS);
        if (now - lastLoadErrorLog > 30_000L) {
            lastLoadErrorLog = now;
            logger.log(Level.WARNING, "Failed to load region " + key + " (will retry later)", error);
        }
    }

    // ===================================================================== simulation

    /**
     * Called every tick by the scheduler. Cheap when idle; when a pass is due it snapshots the resident
     * keys and processes at most {@code max-regions-per-tick} of them per call.
     */
    public void tickSimulation(long nowWall) {
        if (passQueue.isEmpty()) {
            if (nowWall < nextPassAt) {
                return;
            }
            nextPassAt = nowWall + cfg.simulation().stepIntervalSeconds() * 1000L;
            clock.tick();
            passQueue.addAll(loaded.keySet());
            passesTotal++;
            lastPassNanos = passNanosAccum;
            passNanosAccum = 0;
            if (passQueue.isEmpty()) {
                return;
            }
        }
        long start = System.nanoTime();
        long nowSim = clock.now();
        int budget = cfg.simulation().maxRegionsPerTick();
        while (budget-- > 0 && !passQueue.isEmpty()) {
            RegionState r = loaded.get(passQueue.pollFirst());
            if (r != null) {
                stepRegion(r, nowSim, nowWall);
            }
        }
        passNanosAccum += System.nanoTime() - start;
    }

    /** Steps one region and dispatches its chronicle events and transitions. */
    public void stepRegion(RegionState r, long nowSim, long nowWall) {
        if (!simulator.step(r, nowSim, nowWall, output)) {
            return;
        }
        stepsTotal++;
        for (int[] d : NEIGHBOURS) {
            RegionState n = loaded.get(r.key().offset(d[0], d[1]));
            if (n != null) {
                simulator.spreadInfluence(r, n, r.lastStepDays());
            }
        }
        if (output.isEmpty()) {
            return;
        }
        // copy before dispatching: listeners may trigger further steps
        List<HistoryEvent> events = new ArrayList<>(output.events());
        List<StepOutput.Transition> transitions = new ArrayList<>(output.transitions());
        output.clear();
        for (StepOutput.Transition t : transitions) {
            if (transitionListener != null) {
                try {
                    transitionListener.onTransition(r, t);
                } catch (RuntimeException ex) {
                    logger.log(Level.WARNING, "Transition listener failed", ex);
                }
            }
        }
        for (HistoryEvent e : events) {
            history.record(e);
        }
    }

    /**
     * Admin/test hook: simulates {@code millis} of extra time for one resident region only. All of the
     * region's simulation timestamps are shifted into the past, which is equivalent to that much time
     * having passed for this region (confirmation timers and inactivity included).
     */
    public void fastForward(RegionState r, long millis) {
        long shift = Math.max(0, millis);
        r.lastSimulatedSim(r.lastSimulatedSim() - shift);
        if (r.candidateState() != null) {
            r.candidateSince(r.candidateSince() - shift);
        }
        if (r.lastActivitySim() > 0) {
            r.lastActivitySim(Math.max(1, r.lastActivitySim() - shift));
        }
        stepRegion(r, clock.now(), System.currentTimeMillis());
    }

    /**
     * Persists pending chronicle entries right away (without waiting for the autosave), so that a history
     * query submitted afterwards sees them: the storage thread executes work strictly in order.
     */
    public void flushHistory() {
        List<HistoryEvent> events = history.drainUnsaved();
        if (events.isEmpty()) {
            return;
        }
        long simNow = clock.tick();
        boolean accepted = storage.submit(st -> {
            st.saveBatch(List.of(), events, simNow);
            return null;
        }, null, error -> {
            history.requeue(events);
            logger.log(Level.WARNING, "Saving chronicle entries failed; they will be retried", error);
        });
        if (!accepted) {
            history.requeue(events);
        }
    }

    // ===================================================================== persistence

    /** Starts an asynchronous save of all dirty regions plus unsaved chronicle entries. */
    public boolean saveAsync() {
        if (saveInFlight) {
            return false;
        }
        List<RegionSnapshot> snapshots = new ArrayList<>();
        Set<RegionKey> keys = new HashSet<>();
        for (RegionState r : loaded.values()) {
            if (r.dirty()) {
                snapshots.add(r.toSnapshot());
                keys.add(r.key());
                r.clearDirty();
            }
        }
        List<HistoryEvent> events = history.drainUnsaved();
        long simNow = clock.tick();
        if (snapshots.isEmpty() && events.isEmpty()) {
            return true;
        }
        saveInFlight = true;
        boolean accepted = storage.submit(st -> {
            st.saveBatch(snapshots, events, simNow);
            return snapshots.size();
        }, count -> saveInFlight = false, error -> {
            saveInFlight = false;
            restoreDirty(keys, events);
            logger.log(Level.SEVERE, "Autosave failed; data stays in memory and will be retried", error);
        });
        if (!accepted) {
            saveInFlight = false;
            restoreDirty(keys, events);
            return false;
        }
        return true;
    }

    /**
     * Saves dirty regions before a ranking/overview query, so that it reflects the current state. Rate
     * limited, so spamming commands cannot turn into a stream of database writes.
     */
    public void saveSoon(long nowWall) {
        if (nowWall - lastSaveRequest >= 5_000L) {
            lastSaveRequest = nowWall;
            saveAsync();
        }
    }

    private void restoreDirty(Collection<RegionKey> keys, List<HistoryEvent> events) {
        for (RegionKey k : keys) {
            RegionState r = loaded.get(k);
            if (r != null) {
                r.markDirty();
            }
        }
        history.requeue(events);
    }

    /** Snapshots every dirty region synchronously (used for the final save on shutdown). */
    public List<RegionSnapshot> snapshotDirty() {
        List<RegionSnapshot> out = new ArrayList<>();
        for (RegionState r : loaded.values()) {
            if (r.dirty()) {
                out.add(r.toSnapshot());
                r.clearDirty();
            }
        }
        return out;
    }

    /**
     * Snapshots every resident region, dirty or not. Used by the final save on shutdown: if an autosave
     * was still running (and could fail with nobody left to re-mark its regions dirty), nothing is lost.
     */
    public List<RegionSnapshot> snapshotAll() {
        List<RegionSnapshot> out = new ArrayList<>(loaded.size());
        for (RegionState r : loaded.values()) {
            out.add(r.toSnapshot());
            r.clearDirty();
        }
        return out;
    }

    public boolean saveInFlight() {
        return saveInFlight;
    }

    // ===================================================================== eviction & sweeping

    /**
     * Unloads clean, idle regions. With {@code force} the least recently used clean regions are evicted
     * until the cache is back under its limit, even if they are not idle yet.
     */
    public int evict(long nowWall, boolean force) {
        if (saveInFlight) {
            return 0; // regions in the save batch must stay resident until we know whether it succeeded
        }
        pruneFailed(nowWall);
        long idleMs = cfg.cache().idleEvictMinutes() * 60_000L;
        int removed = 0;
        for (Iterator<RegionState> it = loaded.values().iterator(); it.hasNext(); ) {
            RegionState r = it.next();
            if (!r.dirty() && r.accumulators().isEmpty() && nowWall - r.lastAccessWall() >= idleMs
                    && nowWall - r.lastPresenceWall() >= idleMs) {
                it.remove();
                removed++;
            }
        }
        int max = cfg.cache().maxLoadedRegions();
        if (force && loaded.size() > max) {
            List<RegionState> candidates = new ArrayList<>();
            for (RegionState r : loaded.values()) {
                if (!r.dirty() && r.accumulators().isEmpty()) {
                    candidates.add(r);
                }
            }
            candidates.sort(Comparator.comparingLong(RegionState::lastAccessWall));
            for (RegionState r : candidates) {
                if (loaded.size() <= max) {
                    break;
                }
                loaded.remove(r.key());
                removed++;
            }
        }
        evictedTotal += removed;
        return removed;
    }

    private void pruneFailed(long nowWall) {
        failed.values().removeIf(retryAt -> retryAt <= nowWall);
    }

    /**
     * Loads a small batch of regions that have not been simulated for a long time, so that settlements
     * nobody visits still decay and forests nobody visits still recover. Only database rows are touched.
     */
    public void dormantSweep(long nowWall) {
        LivingWorldConfig.Cache c = cfg.cache();
        if (!c.dormantSweep() || sweepInFlight) {
            return;
        }
        int headroom = c.maxLoadedRegions() - loaded.size() - pending.size();
        int batch = Math.min(c.dormantSweepBatch(), Math.max(0, headroom - c.maxLoadedRegions() / 5));
        if (batch <= 0) {
            return;
        }
        long staleBefore = clock.tick() - (long) (c.dormantMinStaleHours() * 3_600_000L);
        sweepInFlight = true;
        boolean accepted = storage.submit(st -> st.staleRegions(staleBefore, batch), keys -> {
            sweepInFlight = false;
            long now = System.currentTimeMillis();
            for (RegionKey k : keys) {
                if (!loaded.containsKey(k) && !pending.containsKey(k)) {
                    requestLoad(k, () -> BiomeProfile.OTHER, true, now);
                }
            }
        }, error -> {
            sweepInFlight = false;
            logger.log(Level.WARNING, "Dormant sweep query failed", error);
        });
        if (!accepted) {
            sweepInFlight = false;
        }
    }

    /** Removes a region from memory and storage (admin reset). */
    public void reset(RegionKey key) {
        loaded.remove(key);
        pending.remove(key);
        storage.submit(st -> {
            st.deleteRegion(key);
            return null;
        }, null, null);
    }

    /** Drops all in-memory state without saving (used after the final save on disable). */
    public void clear() {
        loaded.clear();
        pending.clear();
        failed.clear();
        passQueue.clear();
        output.clear();
    }

    // ===================================================================== diagnostics

    public Collection<RegionState> loadedRegions() {
        return Collections.unmodifiableCollection(loaded.values());
    }

    public int loadedCount() {
        return loaded.size();
    }

    public int pendingCount() {
        return pending.size();
    }

    public int failedCount() {
        return failed.size();
    }

    public int passQueueSize() {
        return passQueue.size();
    }

    public int dirtyCount() {
        int n = 0;
        for (RegionState r : loaded.values()) {
            if (r.dirty()) {
                n++;
            }
        }
        return n;
    }

    public long stepsTotal() {
        return stepsTotal;
    }

    public long passesTotal() {
        return passesTotal;
    }

    public long lastPassMicros() {
        return lastPassNanos / 1000;
    }

    public long droppedActivity() {
        return droppedActivity;
    }

    public long evictedTotal() {
        return evictedTotal;
    }

    public long loadsTotal() {
        return loadsTotal;
    }
}
