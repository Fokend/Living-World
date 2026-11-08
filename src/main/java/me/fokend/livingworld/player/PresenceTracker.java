package me.fokend.livingworld.player;

import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.listener.ActivityRecorder;
import me.fokend.livingworld.listener.BiomeSampler;
import me.fokend.livingworld.model.Accumulators;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.region.RegionManager;
import me.fokend.livingworld.ui.HudService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Periodic (default every 5 s) presence sampling: O(online players), one key computation and one map
 * lookup per player. Also drives region-entry notifications, the BossBar and ambient particles.
 */
public final class PresenceTracker implements Runnable {

    private final RegionManager regions;
    private final ActivityRecorder recorder;
    private final SessionRegistry sessions;
    private final HudService hud;

    public PresenceTracker(RegionManager regions, ActivityRecorder recorder, SessionRegistry sessions, HudService hud) {
        this.regions = regions;
        this.recorder = recorder;
        this.sessions = sessions;
        this.hud = hud;
    }

    @Override
    public void run() {
        LivingWorldConfig cfg = regions.config();
        long now = System.currentTimeMillis();
        double seconds = cfg.simulation().presenceIntervalTicks() / 20.0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerSession session = sessions.getOrOpen(player, cfg.ui().bossBarDefault(), cfg.ui().actionBarOnEnter());
            World world = player.getWorld();
            if (!recorder.worldEnabled(world)) {
                if (session.currentRegion() != null) {
                    session.currentRegion(null);
                    hud.hideBossBar(player, session);
                }
                continue;
            }
            int x = (int) Math.floor(player.getX());
            int y = (int) Math.floor(player.getY());
            int z = (int) Math.floor(player.getZ());
            RegionKey key = recorder.key(world, x, z);
            boolean counts = cfg.tracking().presence() && !recorder.ignored(player);
            if (counts) {
                Accumulators acc = regions.activity(key, () -> BiomeSampler.sample(world, x, y, z), now);
                if (acc != null) {
                    acc.presenceSeconds += seconds;
                    recorder.influence(player, acc, cfg.influence().presencePerMinute() * seconds / 60.0);
                }
            } else {
                regions.ensureLoaded(key, () -> BiomeSampler.sample(world, x, y, z), now);
            }
            RegionState state = regions.getLoaded(key);
            if (state == null) {
                continue; // still loading; handled on the next sample
            }
            state.lastPresenceWall(now);
            if (!key.equals(session.currentRegion())) {
                session.currentRegion(key);
                if (session.notifyOnEnter()) {
                    hud.announceEntry(player, state);
                }
            }
            hud.updateBossBar(player, session, state);
            hud.ambient(player, state, cfg.ui());
        }
    }
}
