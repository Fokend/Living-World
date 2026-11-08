package me.fokend.livingworld.history;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.model.HistoryEvent;

/**
 * World chronicle front-end. Main thread only.
 *
 * <ul>
 *   <li>{@code recent} — bounded ring buffer for instant display;</li>
 *   <li>{@code unsaved} — bounded buffer drained by every autosave (oldest entries dropped on overflow);</li>
 *   <li>a per-hour rate limit protects the database from event floods;</li>
 *   <li>long-term retention (age and count) is enforced in the database by a maintenance job.</li>
 * </ul>
 */
public final class HistoryService {

    public static final int MAX_UNSAVED = 2000;

    private LivingWorldConfig.History cfg;
    private final ArrayDeque<HistoryEvent> recent = new ArrayDeque<>();
    private final ArrayDeque<HistoryEvent> unsaved = new ArrayDeque<>();
    private Consumer<HistoryEvent> publisher;
    private long windowStart;
    private int windowCount;
    private long dropped;

    public HistoryService(LivingWorldConfig.History cfg) {
        this.cfg = cfg;
    }

    public void config(LivingWorldConfig.History cfg) {
        this.cfg = cfg;
        trim(recent, cfg.memoryRecent());
    }

    /** Receives every accepted event (e.g. to notify players / fire API events). */
    public void publisher(Consumer<HistoryEvent> publisher) {
        this.publisher = publisher;
    }

    /** @return true if the event was accepted into the chronicle */
    public boolean record(HistoryEvent event) {
        if (!cfg.enabled() || event == null) {
            return false;
        }
        long now = event.timestamp();
        if (now - windowStart >= 3_600_000L || now < windowStart) {
            windowStart = now;
            windowCount = 0;
        }
        if (windowCount >= cfg.maxPerHour()) {
            dropped++;
            return false;
        }
        windowCount++;
        recent.addFirst(event);
        trim(recent, cfg.memoryRecent());
        unsaved.addLast(event);
        if (unsaved.size() > MAX_UNSAVED) {
            unsaved.pollFirst();
            dropped++;
        }
        if (publisher != null) {
            publisher.accept(event);
        }
        return true;
    }

    /** Removes and returns everything not yet persisted. */
    public List<HistoryEvent> drainUnsaved() {
        List<HistoryEvent> out = new ArrayList<>(unsaved);
        unsaved.clear();
        return out;
    }

    /** Puts events back after a failed save (still bounded). */
    public void requeue(Collection<HistoryEvent> events) {
        List<HistoryEvent> merged = new ArrayList<>(events);
        merged.addAll(unsaved);
        unsaved.clear();
        int skip = Math.max(0, merged.size() - MAX_UNSAVED);
        dropped += skip;
        for (int i = skip; i < merged.size(); i++) {
            unsaved.addLast(merged.get(i));
        }
    }

    public List<HistoryEvent> recent(int limit) {
        List<HistoryEvent> out = new ArrayList<>(Math.min(limit, recent.size()));
        for (HistoryEvent e : recent) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e);
        }
        return out;
    }

    public int recentSize() {
        return recent.size();
    }

    public int unsavedSize() {
        return unsaved.size();
    }

    public long droppedCount() {
        return dropped;
    }

    public void clearMemory() {
        recent.clear();
        unsaved.clear();
    }

    private static void trim(ArrayDeque<HistoryEvent> deque, int max) {
        while (deque.size() > max) {
            deque.pollLast();
        }
    }
}
