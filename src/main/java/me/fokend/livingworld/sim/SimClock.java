package me.fokend.livingworld.sim;

/**
 * Simulation clock. Advances only while the server runs (scaled by {@code time-scale}), so a server that
 * is offline for a week does not suddenly mark every settlement as abandoned. The value is persisted.
 * Main thread only.
 */
public final class SimClock {

    private long simMillis;
    private long lastRealNanos;
    private double timeScale;

    public SimClock(long initialSimMillis, double timeScale) {
        this.simMillis = Math.max(0, initialSimMillis);
        this.timeScale = timeScale;
        this.lastRealNanos = System.nanoTime();
    }

    /** Advances the clock by the real time elapsed since the previous call and returns the new value. */
    public long tick() {
        long nowNanos = System.nanoTime();
        long deltaNanos = nowNanos - lastRealNanos;
        lastRealNanos = nowNanos;
        if (deltaNanos > 0) {
            simMillis += (long) (deltaNanos / 1_000_000.0 * timeScale);
        }
        return simMillis;
    }

    public long now() {
        return simMillis;
    }

    /** Test/admin hook: jumps the clock forward. */
    public void advance(long millis) {
        if (millis > 0) {
            simMillis += millis;
        }
    }

    public void timeScale(double timeScale) {
        tick();
        this.timeScale = timeScale;
    }
}
