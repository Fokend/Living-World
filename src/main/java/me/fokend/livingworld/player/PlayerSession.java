package me.fokend.livingworld.player;

import java.util.UUID;
import me.fokend.livingworld.model.RegionKey;
import net.kyori.adventure.bossbar.BossBar;

/**
 * Per-online-player runtime data. Holds the player's UUID, never the {@code Player} object, and is
 * removed on quit. The Adventure {@link BossBar} is a plain value object not tied to the player.
 */
public final class PlayerSession {

    private final UUID playerId;
    private RegionKey currentRegion;
    private boolean hud;
    private boolean notify;
    private BossBar bossBar;
    private long buildWindowStart;
    private int buildWindowCount;
    private long lastRename;

    public PlayerSession(UUID playerId, boolean hud, boolean notify) {
        this.playerId = playerId;
        this.hud = hud;
        this.notify = notify;
    }

    public UUID playerId() { return playerId; }
    public RegionKey currentRegion() { return currentRegion; }
    public void currentRegion(RegionKey key) { this.currentRegion = key; }
    public boolean hud() { return hud; }
    public void hud(boolean hud) { this.hud = hud; }
    public boolean notifyOnEnter() { return notify; }
    public void notifyOnEnter(boolean notify) { this.notify = notify; }
    public BossBar bossBar() { return bossBar; }
    public void bossBar(BossBar bar) { this.bossBar = bar; }
    public long lastRename() { return lastRename; }
    public void lastRename(long t) { this.lastRename = t; }

    /** Sliding one-minute window rate limit for construction credit (anti place/break spam). */
    public boolean allowBuildAction(long nowWall, int maxPerMinute) {
        if (nowWall - buildWindowStart >= 60_000L || nowWall < buildWindowStart) {
            buildWindowStart = nowWall;
            buildWindowCount = 0;
        }
        if (buildWindowCount >= maxPerMinute) {
            return false;
        }
        buildWindowCount++;
        return true;
    }
}
