package me.fokend.livingworld.ui;

import java.util.function.Supplier;
import me.fokend.livingworld.api.RegionView;
import me.fokend.livingworld.api.event.RegionStateChangeEvent;
import me.fokend.livingworld.api.event.WorldHistoryEvent;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.HistoryEventType;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.player.PlayerSession;
import me.fokend.livingworld.player.SessionRegistry;
import me.fokend.livingworld.region.RegionManager;
import me.fokend.livingworld.sim.StepOutput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Bridges simulation output to players and to other plugins: fires the public Bukkit events, tells
 * players inside the affected region, and announces only MAJOR events server-wide (to avoid spam).
 */
public final class Notifier implements RegionManager.TransitionListener {

    private final RegionManager regions;
    private final SessionRegistry sessions;
    private final Format format;
    private final HudService hud;
    private final Supplier<LivingWorldConfig> cfg;

    public Notifier(RegionManager regions, SessionRegistry sessions, Format format, HudService hud,
                    Supplier<LivingWorldConfig> cfg) {
        this.regions = regions;
        this.sessions = sessions;
        this.format = format;
        this.hud = hud;
        this.cfg = cfg;
    }

    public void onHistory(HistoryEvent event) {
        Bukkit.getPluginManager().callEvent(new WorldHistoryEvent(event));
        LivingWorldConfig.History h = cfg.get().history();
        RegionState r = regions.getLoaded(event.region());
        String name = r != null ? format.regionName(r) : format.regionName(event.region(), null, BiomeProfile.OTHER);
        Component text = format.historyText(event, name);
        HistoryEventType.Significance sig = event.type().significance();
        if (h.broadcastMajor() && sig == HistoryEventType.Significance.MAJOR) {
            Bukkit.getServer().sendMessage(format.messages().prefixed("history.broadcast",
                    Placeholder.component("text", text)));
            return;
        }
        if (!h.notifyRegionPlayers() || sig == HistoryEventType.Significance.MINOR) {
            return;
        }
        Component line = format.messages().prefixed("history.notify", Placeholder.component("text", text));
        for (PlayerSession s : sessions.all()) {
            if (event.region().equals(s.currentRegion())) {
                Player p = Bukkit.getPlayer(s.playerId());
                if (p != null) {
                    p.sendMessage(line);
                }
            }
        }
    }

    @Override
    public void onTransition(RegionState region, StepOutput.Transition t) {
        Bukkit.getPluginManager().callEvent(new RegionStateChangeEvent(
                RegionView.of(region, format.regionName(region)), t.from(), t.to()));
        for (PlayerSession s : sessions.all()) {
            if (region.key().equals(s.currentRegion())) {
                Player p = Bukkit.getPlayer(s.playerId());
                if (p != null) {
                    hud.updateBossBar(p, s, region);
                }
            }
        }
    }
}
