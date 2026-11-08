package me.fokend.livingworld.api;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Public integration surface, registered in Bukkit's {@code ServicesManager}:
 * <pre>{@code
 * LivingWorldApi api = Bukkit.getServicesManager().load(LivingWorldApi.class);
 * }</pre>
 * All methods must be called on the server main thread.
 */
public interface LivingWorldApi {

    /** Region at {@code location} if it is currently in memory (never loads anything). */
    Optional<RegionView> regionAt(Location location);

    /** Size of a region side in blocks. */
    int regionSizeBlocks();

    /**
     * Reports a completed player-to-player or shop trade (for economy / shop plugins, since Living World
     * only observes villager trades by itself).
     *
     * @param weight 1.0 = one ordinary trade; clamped to [0, 64]
     */
    void recordTrade(Player player, Location location, double weight);

    /** Reports construction work done outside of normal block placement (e.g. schematics), clamped to [0, 256]. */
    void recordConstruction(Player player, Location location, int actions);

    /**
     * Registers a resolver mapping players to clans/teams. Resolvers registered later take precedence.
     * They are removed automatically when {@code owner} is disabled.
     */
    void registerInfluenceResolver(Plugin owner, InfluenceGroupResolver resolver);

    void unregisterInfluenceResolvers(Plugin owner);
}
