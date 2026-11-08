package me.fokend.livingworld.api;

import java.util.Optional;
import me.fokend.livingworld.LivingWorldPlugin;
import me.fokend.livingworld.model.Accumulators;
import me.fokend.livingworld.model.RegionState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Implementation registered in the ServicesManager; unregistered on disable. */
public final class LivingWorldApiImpl implements LivingWorldApi {

    private final LivingWorldPlugin plugin;

    public LivingWorldApiImpl(LivingWorldPlugin plugin) {
        this.plugin = plugin;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("LivingWorldApi must be used from the main thread");
        }
    }

    @Override
    public Optional<RegionView> regionAt(Location location) {
        requireMainThread();
        if (location == null || location.getWorld() == null || !plugin.recorder().worldEnabled(location.getWorld())) {
            return Optional.empty();
        }
        RegionState r = plugin.regions().getLoaded(plugin.recorder().key(location.getWorld(),
                location.getBlockX(), location.getBlockZ()));
        return r == null ? Optional.empty() : Optional.of(RegionView.of(r, plugin.format().regionName(r)));
    }

    @Override
    public int regionSizeBlocks() {
        return plugin.regions().config().general().regionSizeBlocks();
    }

    @Override
    public void recordTrade(Player player, Location location, double weight) {
        requireMainThread();
        if (!Double.isFinite(weight) || weight <= 0 || location == null || location.getWorld() == null) {
            return;
        }
        double w = Math.min(64, weight);
        Accumulators acc = plugin.recorder().at(location.getWorld(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
        if (acc != null) {
            acc.trades += w;
            if (player != null) {
                plugin.recorder().influence(player, acc, plugin.regions().config().influence().perTrade() * w);
            }
        }
    }

    @Override
    public void recordConstruction(Player player, Location location, int actions) {
        requireMainThread();
        if (actions <= 0 || location == null || location.getWorld() == null) {
            return;
        }
        int a = Math.min(256, actions);
        Accumulators acc = plugin.recorder().at(location.getWorld(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
        if (acc != null) {
            acc.buildActions += a;
            if (player != null) {
                plugin.recorder().influence(player, acc, plugin.regions().config().influence().perBuildAction() * a);
            }
        }
    }

    @Override
    public void registerInfluenceResolver(Plugin owner, InfluenceGroupResolver resolver) {
        requireMainThread();
        plugin.resolvers().register(owner, resolver);
    }

    @Override
    public void unregisterInfluenceResolvers(Plugin owner) {
        requireMainThread();
        plugin.resolvers().unregister(owner);
    }
}
