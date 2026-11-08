package me.fokend.livingworld.ui;

import java.util.concurrent.ThreadLocalRandom;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.player.PlayerSession;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * ActionBar on region entry, optional BossBar, and sparse client-side ambient particles. Everything is
 * sent to a single player only (no world-wide effects, no entities, no block changes).
 */
public final class HudService {

    private final Format format;

    public HudService(Format format) {
        this.format = format;
    }

    public static boolean thriving(RegionState r) {
        return r.stability() >= 0.85 && r.forest() >= 0.8;
    }

    public static boolean damaged(RegionState r) {
        return r.stability() < 0.4;
    }

    public void announceEntry(Player player, RegionState r) {
        Component extra = damaged(r) ? format.messages().get("hud.actionbar-damaged")
                : thriving(r) ? format.messages().get("hud.actionbar-thriving") : Component.empty();
        player.sendActionBar(format.messages().get("hud.actionbar",
                Placeholder.unparsed("name", format.regionName(r)),
                Placeholder.component("state", format.state(r.state())),
                Placeholder.component("extra", extra)));
    }

    public void updateBossBar(Player player, PlayerSession session, RegionState r) {
        if (!session.hud() || r == null) {
            hideBossBar(player, session);
            return;
        }
        Component title = format.messages().get("hud.bossbar",
                Placeholder.unparsed("name", format.regionName(r)),
                Placeholder.component("state", format.state(r.state())),
                Placeholder.unparsed("stability", Format.percent(r.stability())));
        float progress = (float) Math.max(0, Math.min(1, r.stability()));
        BossBar.Color color = r.stability() >= 0.7 ? BossBar.Color.GREEN
                : r.stability() >= 0.4 ? BossBar.Color.YELLOW : BossBar.Color.RED;
        BossBar bar = session.bossBar();
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, color, BossBar.Overlay.NOTCHED_10);
            session.bossBar(bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
            bar.color(color);
        }
    }

    public void hideBossBar(Player player, PlayerSession session) {
        BossBar bar = session.bossBar();
        if (bar != null) {
            if (player != null) {
                player.hideBossBar(bar);
            }
            session.bossBar(null);
        }
    }

    /** Occasionally shows a few particles that hint at the region's condition. */
    public void ambient(Player player, RegionState r, LivingWorldConfig.Interface ui) {
        if (!ui.particles() || ThreadLocalRandom.current().nextDouble() >= ui.particleChance()) {
            return;
        }
        Particle particle;
        int count;
        if (r.state() == DevelopmentState.ABANDONED) {
            particle = Particle.WHITE_ASH;
            count = 12;
        } else if (r.state() == DevelopmentState.OVEREXPLOITED || damaged(r)) {
            particle = Particle.ASH;
            count = 16;
        } else if (r.state() == DevelopmentState.COMMERCIAL_HUB) {
            particle = Particle.HAPPY_VILLAGER;
            count = 3;
        } else if (thriving(r) && r.state() == DevelopmentState.WILDERNESS) {
            particle = Particle.FIREFLY;
            count = 4;
        } else {
            return;
        }
        player.spawnParticle(particle, player.getX(), player.getY() + 1.5, player.getZ(), count, 6, 2, 6, 0);
    }
}
