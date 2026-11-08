package me.fokend.livingworld.listener;

import me.fokend.livingworld.api.InfluenceGroup;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.integration.InfluenceResolverChain;
import me.fokend.livingworld.model.Accumulators;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.player.PlayerSession;
import me.fokend.livingworld.player.SessionRegistry;
import me.fokend.livingworld.region.RegionManager;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Single entry point that turns gameplay events into region accumulators. Only does O(1) work per event:
 * a key computation, a map lookup and a counter increment. Main thread only.
 */
public final class ActivityRecorder {

    private final RegionManager regions;
    private final SessionRegistry sessions;
    private final InfluenceResolverChain resolvers;

    public ActivityRecorder(RegionManager regions, SessionRegistry sessions, InfluenceResolverChain resolvers) {
        this.regions = regions;
        this.sessions = sessions;
        this.resolvers = resolvers;
    }

    public LivingWorldConfig cfg() {
        return regions.config();
    }

    public boolean worldEnabled(World world) {
        return world != null && cfg().general().worldEnabled(world.getName());
    }

    /** Players in creative/spectator do not shape the world's ecology (configurable). */
    public boolean ignored(Player player) {
        if (player == null) {
            return true;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.SPECTATOR || (cfg().tracking().ignoreCreative() && mode == GameMode.CREATIVE);
    }

    public RegionKey key(World world, int x, int z) {
        return regions.keyAt(world.getName(), x, z);
    }

    /**
     * Accumulator for the region containing the given block, or null if the world is disabled or the
     * activity had to be dropped. The biome is sampled only for brand-new regions and only from the
     * already loaded chunk the event happened in.
     */
    public Accumulators at(World world, int x, int y, int z) {
        if (!worldEnabled(world)) {
            return null;
        }
        return regions.activity(key(world, x, z), () -> BiomeSampler.sample(world, x, y, z), System.currentTimeMillis());
    }

    public void influence(Player player, Accumulators acc, double amount) {
        if (acc == null || amount <= 0 || !cfg().influence().enabled()) {
            return;
        }
        InfluenceGroup group = resolvers.resolve(player);
        acc.addInfluence(group.id(), group.displayName(), amount);
    }

    public InfluenceGroup group(Player player) {
        return resolvers.resolve(player);
    }

    /** Rate-limited construction credit (prevents place/break spam from inflating development). */
    public boolean allowBuild(Player player) {
        PlayerSession s = sessions.get(player.getUniqueId());
        return s != null && s.allowBuildAction(System.currentTimeMillis(), cfg().development().maxBuildActionsPerMinute());
    }
}
