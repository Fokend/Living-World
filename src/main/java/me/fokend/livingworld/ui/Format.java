package me.fokend.livingworld.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.HistoryEventType;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.model.SpeciesGroup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** Stateless presentation helpers (bars, names, chronicle lines). */
public final class Format {

    private final Messages messages;

    public Format(Messages messages) {
        this.messages = messages;
    }

    public Messages messages() {
        return messages;
    }

    // ------------------------------------------------------------------ names

    /** Custom name if set, otherwise a deterministic generated name. */
    public String regionName(RegionKey key, String customName, BiomeProfile profile) {
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        List<String> pool = messages.rawList("region-names." + (profile == null ? BiomeProfile.OTHER : profile).name());
        if (pool.isEmpty()) {
            pool = messages.rawList("region-names.OTHER");
        }
        if (pool.isEmpty()) {
            return "Регион " + key.x() + ":" + key.z();
        }
        return pool.get((int) Math.floorMod(key.stableHash(), (long) pool.size()));
    }

    public String regionName(RegionState r) {
        return regionName(r.key(), r.customName(), r.profile());
    }

    public Component state(DevelopmentState state) {
        return messages.get("names.state." + state.name());
    }

    public String profile(BiomeProfile profile) {
        return messages.raw("names.profile." + profile.name());
    }

    public String species(SpeciesGroup group) {
        return messages.raw("names.species." + group.name());
    }

    // ------------------------------------------------------------------ numbers

    public static String percent(double v) {
        return Math.round(Math.max(0, Math.min(1, v)) * 100) + "%";
    }

    public static String number(double v) {
        if (Math.abs(v) >= 100) {
            return String.valueOf(Math.round(v));
        }
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** A ten-segment bar coloured from red (0) to green (1); {@code inverted} for "lower is better". */
    public static Component bar(double v, boolean inverted) {
        double x = Math.max(0, Math.min(1, v));
        int filled = (int) Math.round(x * 10);
        double good = inverted ? 1 - x : x;
        TextColor color = good >= 0.75 ? NamedTextColor.GREEN : good >= 0.5 ? NamedTextColor.YELLOW
                : good >= 0.3 ? NamedTextColor.GOLD : NamedTextColor.RED;
        return Component.text("■".repeat(filled), color)
                .append(Component.text("■".repeat(10 - filled), NamedTextColor.DARK_GRAY));
    }

    public static Component trend(double perDay) {
        if (perDay > 0.005) {
            return Component.text("▲", NamedTextColor.GREEN);
        }
        if (perDay < -0.005) {
            return Component.text("▼", NamedTextColor.RED);
        }
        return Component.empty();
    }

    public static String timeAgo(long wallMillis, long now) {
        long s = Math.max(0, (now - wallMillis) / 1000);
        if (s < 60) return "только что";
        long m = s / 60;
        if (m < 60) return m + " мин назад";
        long h = m / 60;
        if (h < 48) return h + " ч назад";
        long d = h / 24;
        return d + " дн назад";
    }

    // ------------------------------------------------------------------ chronicle

    public Component historyText(HistoryEvent e, String regionName) {
        String subject = e.subject();
        HistoryEventType t = e.type();
        if (t == HistoryEventType.WILDLIFE_DECLINE || t == HistoryEventType.WILDLIFE_RECOVERED) {
            try {
                subject = species(SpeciesGroup.valueOf(subject));
            } catch (IllegalArgumentException ignored) {
                // keep raw subject
            }
        }
        String value = switch (t) {
            case DEFORESTATION, MASS_DEFORESTATION, FOREST_RESTORED, WILDLIFE_DECLINE, WILDLIFE_RECOVERED -> percent(e.value());
            case STEWARD_MILESTONE -> String.valueOf(Math.round(e.value()));
            default -> number(e.value());
        };
        return messages.get("history.events." + t.name(),
                Placeholder.unparsed("region", regionName),
                Placeholder.unparsed("subject", subject),
                Placeholder.unparsed("actors", e.actors()),
                Placeholder.unparsed("value", value));
    }

    // ------------------------------------------------------------------ identity

    /** Region "character" derived from its lifetime record — what makes each region recognisable. */
    public List<Component> traits(RegionState r, LivingWorldConfig cfg) {
        List<Component> out = new ArrayList<>();
        double forestCap = cfg.forestCapacityLogs(r.profile());
        double area = cfg.general().areaScale();
        if (r.totalLogsCut() >= Math.max(200, forestCap * 0.5)) out.add(messages.get("names.traits.lumber"));
        if (r.totalTreesGrown() >= 50 * area) out.add(messages.get("names.traits.green"));
        if (r.totalKills() >= 100 * area) out.add(messages.get("names.traits.hunting"));
        if (r.totalTrades() >= 500 * area) out.add(messages.get("names.traits.market"));
        if (r.totalBuild() >= 5000 * area) out.add(messages.get("names.traits.builders"));
        if (r.state() == DevelopmentState.WILDERNESS && r.totalBuild() < 50 && r.stability() > 0.9) {
            out.add(messages.get("names.traits.wild"));
        }
        if (r.state() == DevelopmentState.ABANDONED
                || (r.state() == DevelopmentState.WILDERNESS && r.totalBuild() >= 2000 * area)) {
            out.add(messages.get("names.traits.ruins"));
        }
        return out;
    }

    public static Component join(List<Component> parts, String separator) {
        Component out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out = out.append(Component.text(separator, NamedTextColor.DARK_GRAY));
            }
            out = out.append(parts.get(i));
        }
        return out;
    }
}
