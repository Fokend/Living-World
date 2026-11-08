package me.fokend.livingworld.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.fokend.livingworld.api.InfluenceGroup;
import me.fokend.livingworld.api.InfluenceGroupResolver;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Ordered chain of {@link InfluenceGroupResolver}s: externally registered adapters first (newest first),
 * then the optional scoreboard-team adapter, then the always-available per-player fallback.
 *
 * <p>Holds plugin references only while those plugins are enabled: {@link #unregister(Plugin)} is called
 * from a {@code PluginDisableEvent} listener, so a disabled clan plugin cannot be retained in memory.</p>
 */
public final class InfluenceResolverChain {

    private record Registration(Plugin owner, InfluenceGroupResolver resolver) {
    }

    private static final int MAX_REGISTRATIONS = 16;

    private final List<Registration> external = new ArrayList<>();
    private final Logger logger;
    private InfluenceGroupResolver teamAdapter;
    private long lastErrorLog;

    public InfluenceResolverChain(Logger logger) {
        this.logger = logger;
    }

    public void register(Plugin owner, InfluenceGroupResolver resolver) {
        if (owner == null || resolver == null) {
            throw new IllegalArgumentException("owner and resolver are required");
        }
        if (external.size() >= MAX_REGISTRATIONS) {
            throw new IllegalStateException("too many influence resolvers registered");
        }
        external.add(0, new Registration(owner, resolver));
        logger.info("Influence resolver registered by " + owner.getName());
    }

    public void unregister(Plugin owner) {
        external.removeIf(r -> r.owner() == owner);
    }

    public void teamAdapter(InfluenceGroupResolver adapter) {
        this.teamAdapter = adapter;
    }

    public int externalCount() {
        return external.size();
    }

    public InfluenceGroup resolve(Player player) {
        for (Registration r : external) {
            InfluenceGroup g = safe(r.resolver(), player);
            if (g != null) {
                return g;
            }
        }
        if (teamAdapter != null) {
            InfluenceGroup g = safe(teamAdapter, player);
            if (g != null) {
                return g;
            }
        }
        return new InfluenceGroup("player:" + player.getUniqueId(), player.getName());
    }

    private InfluenceGroup safe(InfluenceGroupResolver resolver, Player player) {
        try {
            return resolver.resolve(player);
        } catch (RuntimeException ex) {
            long now = System.currentTimeMillis();
            if (now - lastErrorLog > 60_000L) {
                lastErrorLog = now;
                logger.log(Level.WARNING, "Influence resolver failed; falling back", ex);
            }
            return null;
        }
    }

    public void clear() {
        external.clear();
        teamAdapter = null;
    }
}
