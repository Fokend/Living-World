package me.fokend.livingworld.listener;

import java.util.function.Supplier;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.integration.InfluenceResolverChain;
import me.fokend.livingworld.player.PlayerSession;
import me.fokend.livingworld.player.SessionRegistry;
import me.fokend.livingworld.ui.HudService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/** Session lifecycle: created on join, destroyed on quit; resolvers of disabled plugins are released. */
public final class PlayerListener implements Listener {

    private final SessionRegistry sessions;
    private final HudService hud;
    private final InfluenceResolverChain resolvers;
    private final Supplier<LivingWorldConfig> cfg;
    private final Plugin self;

    public PlayerListener(SessionRegistry sessions, HudService hud, InfluenceResolverChain resolvers,
                          Supplier<LivingWorldConfig> cfg, Plugin self) {
        this.sessions = sessions;
        this.hud = hud;
        this.resolvers = resolvers;
        this.cfg = cfg;
        this.self = self;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        LivingWorldConfig c = cfg.get();
        sessions.open(event.getPlayer(), c.ui().bossBarDefault(), c.ui().actionBarOnEnter());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerSession s = sessions.close(event.getPlayer().getUniqueId());
        if (s != null) {
            hud.hideBossBar(event.getPlayer(), s);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != self) {
            resolvers.unregister(event.getPlugin());
        }
    }
}
