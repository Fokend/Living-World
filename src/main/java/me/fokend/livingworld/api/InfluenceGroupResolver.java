package me.fokend.livingworld.api;

import org.bukkit.entity.Player;

/**
 * Adapter between Living World and a clan / team / town system. Living World does not depend on any
 * particular clan plugin: such a plugin (or a small bridge plugin) registers an implementation through
 * {@link LivingWorldApi#registerInfluenceResolver(org.bukkit.plugin.Plugin, InfluenceGroupResolver)}.
 *
 * <p>Called on the main thread, potentially often (presence ticks): implementations must be cheap and
 * must not block (no database or network calls). Return {@code null} when the player belongs to no
 * group; the next resolver in the chain (ultimately "the player himself") is then used.</p>
 */
@FunctionalInterface
public interface InfluenceGroupResolver {
    InfluenceGroup resolve(Player player);
}
