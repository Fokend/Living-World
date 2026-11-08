package me.fokend.livingworld.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import me.fokend.livingworld.LivingWorldPlugin;
import me.fokend.livingworld.api.InfluenceGroup;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.listener.ActivityRecorder;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.HistoryEventType;
import me.fokend.livingworld.model.InfluenceEntry;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.model.SpeciesGroup;
import me.fokend.livingworld.model.StewardEntry;
import me.fokend.livingworld.player.PlayerSession;
import me.fokend.livingworld.region.RegionManager;
import me.fokend.livingworld.storage.HistoryRow;
import me.fokend.livingworld.storage.RegionSummary;
import me.fokend.livingworld.storage.TopMetric;
import me.fokend.livingworld.storage.WorldStats;
import me.fokend.livingworld.ui.Format;
import me.fokend.livingworld.ui.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /world} — the player-facing window into the living world. */
public final class WorldCommand implements TabExecutor {

    private static final int PAGE_SIZE = 8;
    private static final int TOP_SIZE = 10;
    private static final long RENAME_COOLDOWN_MS = 10 * 60_000L;
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} '\\-]{1,30}[\\p{L}\\p{N}]$");
    private static final List<String> SUBCOMMANDS = List.of("region", "ecology", "history", "top", "name", "hud",
            "notify", "help");

    private final LivingWorldPlugin plugin;

    public WorldCommand(LivingWorldPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.format().messages();
    }

    private Format fmt() {
        return plugin.format();
    }

    private RegionManager regions() {
        return plugin.regions();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> overview(sender);
            case "help", "?" -> msg().list("help").forEach(sender::sendMessage);
            case "region", "регион" -> withRegion(sender, this::region);
            case "ecology", "эко" -> withRegion(sender, this::ecology);
            case "history", "история" -> history(sender, args);
            case "top", "топ" -> top(sender, args);
            case "name" -> name(sender, args);
            case "hud" -> toggle(sender, true);
            case "notify" -> toggle(sender, false);
            default -> sender.sendMessage(msg().prefixed("common.unknown-subcommand"));
        }
        return true;
    }

    // ================================================================== helpers

    private interface RegionAction {
        void run(Player player, RegionState region);
    }

    private void withRegion(CommandSender sender, RegionAction action) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg().prefixed("common.players-only"));
            return;
        }
        ActivityRecorder rec = plugin.recorder();
        if (!rec.worldEnabled(player.getWorld())) {
            sender.sendMessage(msg().prefixed("common.world-disabled"));
            return;
        }
        RegionKey key = rec.key(player.getWorld(), (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
        RegionState state = regions().getLoaded(key);
        if (state == null) {
            int x = (int) Math.floor(player.getX());
            int y = (int) Math.floor(player.getY());
            int z = (int) Math.floor(player.getZ());
            regions().ensureLoaded(key, () -> me.fokend.livingworld.listener.BiomeSampler.sample(player.getWorld(), x, y, z),
                    System.currentTimeMillis());
            sender.sendMessage(msg().prefixed("common.loading"));
            return;
        }
        state.touch(System.currentTimeMillis());
        action.run(player, state);
    }

    // ================================================================== /world

    private void overview(CommandSender sender) {
        SenderRef ref = SenderRef.of(sender);
        boolean ok = plugin.storage().submit(st -> st.stats(), stats -> {
            CommandSender to = ref.resolve();
            if (to != null) {
                sendOverview(to, stats);
            }
        }, error -> storageError(ref, error));
        if (!ok) {
            sender.sendMessage(msg().prefixed("common.storage-busy"));
        }
    }

    private void sendOverview(CommandSender to, WorldStats s) {
        int settlements = s.byState().getOrDefault(DevelopmentState.DEVELOPING, 0)
                + s.byState().getOrDefault(DevelopmentState.ACTIVE, 0)
                + s.byState().getOrDefault(DevelopmentState.COMMERCIAL_HUB, 0);
        to.sendMessage(msg().get("overview.header", Placeholder.unparsed("regions", String.valueOf(s.regions()))));
        to.sendMessage(msg().get("overview.states",
                Placeholder.unparsed("settlements", String.valueOf(settlements)),
                Placeholder.unparsed("hubs", String.valueOf(s.byState().getOrDefault(DevelopmentState.COMMERCIAL_HUB, 0))),
                Placeholder.unparsed("abandoned", String.valueOf(s.byState().getOrDefault(DevelopmentState.ABANDONED, 0))),
                Placeholder.unparsed("overexploited", String.valueOf(s.byState().getOrDefault(DevelopmentState.OVEREXPLOITED, 0)))));
        to.sendMessage(msg().get("overview.ecology",
                Placeholder.component("stability", Format.bar(s.avgStability(), false)
                        .append(Component.text(" " + Format.percent(s.avgStability())))),
                Placeholder.unparsed("forest", Format.percent(s.avgForest()))));
        to.sendMessage(msg().get("overview.totals",
                Placeholder.unparsed("logs", String.valueOf(s.totalLogsCut())),
                Placeholder.unparsed("trees", String.valueOf(s.totalTreesGrown()))));
        List<HistoryEvent> recent = plugin.history().recent(3);
        to.sendMessage(msg().get("overview.recent-header"));
        if (recent.isEmpty()) {
            to.sendMessage(msg().get("overview.recent-empty"));
        }
        long now = System.currentTimeMillis();
        for (HistoryEvent e : recent) {
            RegionState r = regions().getLoaded(e.region());
            String name = r != null ? fmt().regionName(r) : fmt().regionName(e.region(), null, null);
            to.sendMessage(msg().get("history.line", Placeholder.unparsed("time", Format.timeAgo(e.timestamp(), now)),
                    Placeholder.component("text", fmt().historyText(e, name))));
        }
    }

    // ================================================================== /world region

    private void region(Player player, RegionState r) {
        LivingWorldConfig cfg = regions().config();
        player.sendMessage(msg().get("region.header",
                Placeholder.unparsed("name", fmt().regionName(r)),
                Placeholder.unparsed("x", String.valueOf(r.key().x())),
                Placeholder.unparsed("z", String.valueOf(r.key().z())),
                Placeholder.unparsed("profile", fmt().profile(r.profile()))));
        player.sendMessage(msg().get("region.state",
                Placeholder.component("state", fmt().state(r.state())),
                Placeholder.component("development", Format.bar(r.development() / 100.0, false)
                        .append(Component.text(" " + Math.round(r.development()) + "/100")))));
        List<Component> traits = fmt().traits(r, cfg);
        player.sendMessage(msg().get("region.traits", Placeholder.component("traits",
                traits.isEmpty() ? msg().get("region.no-traits") : Format.join(traits, ", "))));

        if (cfg.influence().enabled()) {
            player.sendMessage(msg().get("region.influence-header"));
            List<InfluenceEntry> top = r.influenceSorted();
            InfluenceEntry dominant = regions().simulator().dominant(r);
            if (top.isEmpty()) {
                player.sendMessage(msg().get("region.influence-none"));
            }
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                InfluenceEntry e = top.get(i);
                player.sendMessage(msg().get("region.influence-line",
                        Placeholder.unparsed("group", e.displayName()),
                        Placeholder.unparsed("score", Format.number(e.score())),
                        Placeholder.component("dominant", e == dominant ? msg().get("region.influence-dominant")
                                : Component.empty())));
            }
        }
        List<StewardEntry> stewards = r.stewardsSorted();
        if (!stewards.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(3, stewards.size()); i++) {
                names.add(stewards.get(i).playerName() + " (" + stewards.get(i).treesGrown() + ")");
            }
            player.sendMessage(msg().get("region.stewards", Placeholder.unparsed("names", String.join(", ", names))));
        }
        player.sendMessage(msg().get("region.hint-header").append(Component.space())
                .append(msg().get("region.hints." + r.state().name())));
    }

    // ================================================================== /world ecology

    private void ecology(Player player, RegionState r) {
        LivingWorldConfig cfg = regions().config();
        player.sendMessage(msg().get("ecology.header", Placeholder.unparsed("name", fmt().regionName(r)),
                Placeholder.unparsed("profile", fmt().profile(r.profile()))));
        player.sendMessage(msg().get("ecology.forest", Placeholder.component("bar", Format.bar(r.forest(), false)),
                Placeholder.unparsed("value", Format.percent(r.forest())),
                Placeholder.component("trend", Format.trend(r.forestTrend()))));
        player.sendMessage(msg().get("ecology.stability", Placeholder.component("bar", Format.bar(r.stability(), false)),
                Placeholder.unparsed("value", Format.percent(r.stability())),
                Placeholder.component("trend", Format.trend(r.stabilityTrend()))));
        player.sendMessage(msg().get("ecology.exploitation", Placeholder.component("bar", Format.bar(r.exploitation(), true)),
                Placeholder.unparsed("value", Format.percent(r.exploitation()))));
        double regen = cfg.ecology().minRegenMultiplier() + (1 - cfg.ecology().minRegenMultiplier()) * r.stability();
        if (r.state() == DevelopmentState.ABANDONED) {
            regen *= cfg.ecology().abandonedRegenBonus();
        }
        player.sendMessage(msg().get("ecology.recovery", Placeholder.unparsed("value", "×" + Format.number(regen))));
        if (cfg.wildlife().enabled()) {
            player.sendMessage(msg().get("ecology.wildlife-header"));
            boolean any = false;
            for (SpeciesGroup g : SpeciesGroup.all()) {
                if (!cfg.groupPresent(r.profile(), g)) {
                    continue;
                }
                any = true;
                double pop = r.population(g);
                Component line = msg().get("ecology.wildlife-line", Placeholder.unparsed("group", fmt().species(g)),
                        Placeholder.component("bar", Format.bar(pop, false)),
                        Placeholder.unparsed("value", Format.percent(pop)));
                if (cfg.wildlife().spawnSuppression() && pop < cfg.wildlife().suppressionBelow()) {
                    line = line.append(msg().get("ecology.suppressed"));
                }
                player.sendMessage(line);
            }
            if (!any) {
                player.sendMessage(msg().get("ecology.wildlife-none"));
            }
        }
        String verdict = r.stability() >= 0.85 ? "thriving" : r.stability() >= 0.65 ? "healthy"
                : r.stability() >= 0.45 ? "strained" : r.stability() >= 0.3 ? "damaged" : "critical";
        player.sendMessage(msg().get("ecology.verdict." + verdict));
    }

    // ================================================================== /world history [all] [page]

    private void history(CommandSender sender, String[] args) {
        boolean world = false;
        int page = 1;
        for (int i = 1; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            if (a.equals("all") || a.equals("world") || a.equals("мир")) {
                world = true;
            } else {
                try {
                    page = Math.max(1, Math.min(1000, Integer.parseInt(a)));
                } catch (NumberFormatException ignored) {
                    // ignore garbage
                }
            }
        }
        RegionKey key = null;
        String regionName = null;
        if (!world) {
            if (!(sender instanceof Player player)) {
                world = true;
            } else if (!plugin.recorder().worldEnabled(player.getWorld())) {
                sender.sendMessage(msg().prefixed("common.world-disabled"));
                return;
            } else {
                key = plugin.recorder().key(player.getWorld(), (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
                RegionState r = regions().getLoaded(key);
                regionName = r != null ? fmt().regionName(r) : fmt().regionName(key, null, null);
            }
        }
        final RegionKey queryKey = key;
        final String header = regionName;
        final int p = page;
        final boolean worldScope = world;
        SenderRef ref = SenderRef.of(sender);
        boolean ok = plugin.storage().submit(st -> st.history(queryKey, (p - 1) * PAGE_SIZE, PAGE_SIZE + 1), rows -> {
            CommandSender to = ref.resolve();
            if (to == null) {
                return;
            }
            to.sendMessage(worldScope
                    ? msg().get("history.header-world", Placeholder.unparsed("page", String.valueOf(p)))
                    : msg().get("history.header-region", Placeholder.unparsed("name", header),
                    Placeholder.unparsed("page", String.valueOf(p))));
            if (rows.isEmpty()) {
                to.sendMessage(msg().get("history.empty"));
                return;
            }
            long now = System.currentTimeMillis();
            for (int i = 0; i < Math.min(PAGE_SIZE, rows.size()); i++) {
                HistoryRow row = rows.get(i);
                RegionState live = regions().getLoaded(row.event().region());
                String name = live != null ? fmt().regionName(live)
                        : fmt().regionName(row.event().region(), row.customName(), row.profile());
                to.sendMessage(msg().get("history.line",
                        Placeholder.unparsed("time", Format.timeAgo(row.event().timestamp(), now)),
                        Placeholder.component("text", fmt().historyText(row.event(), name))));
            }
            if (rows.size() > PAGE_SIZE) {
                to.sendMessage(msg().get("history.more", Placeholder.unparsed("scope", worldScope ? "all " : ""),
                        Placeholder.unparsed("next", String.valueOf(p + 1))));
            }
        }, error -> storageError(ref, error));
        if (!ok) {
            sender.sendMessage(msg().prefixed("common.storage-busy"));
        }
    }

    // ================================================================== /world top

    private void top(CommandSender sender, String[] args) {
        TopMetric metric = TopMetric.parse(args.length > 1 ? args[1] : null);
        if (metric == null) {
            sender.sendMessage(msg().prefixed("top.unknown"));
            return;
        }
        SenderRef ref = SenderRef.of(sender);
        boolean ok = plugin.storage().submit(st -> st.top(metric, null, TOP_SIZE), rows -> {
            CommandSender to = ref.resolve();
            if (to == null) {
                return;
            }
            to.sendMessage(msg().get("top.header", Placeholder.unparsed("metric", msg().raw("top.metrics." + metric.name()))));
            if (rows.isEmpty()) {
                to.sendMessage(msg().get("top.empty"));
            }
            int rank = 1;
            for (RegionSummary s : rows) {
                String value = switch (metric) {
                    case DEVELOPED -> Math.round(s.development()) + "/100";
                    case STABLE, DAMAGED -> Format.percent(s.stability());
                    case TRADE -> Format.number(s.tradeEma()) + "/сут";
                    case GREEN -> s.treesGrown() + " 🌳";
                };
                to.sendMessage(msg().get("top.line", Placeholder.unparsed("rank", String.valueOf(rank++)),
                        Placeholder.unparsed("name", fmt().regionName(s.key(), s.customName(), s.profile())),
                        Placeholder.unparsed("x", String.valueOf(s.key().x())),
                        Placeholder.unparsed("z", String.valueOf(s.key().z())),
                        Placeholder.component("state", fmt().state(s.state())),
                        Placeholder.unparsed("value", value)));
            }
        }, error -> storageError(ref, error));
        if (!ok) {
            sender.sendMessage(msg().prefixed("common.storage-busy"));
        }
    }

    // ================================================================== /world name

    private void name(CommandSender sender, String[] args) {
        if (!sender.hasPermission("livingworld.name")) {
            sender.sendMessage(msg().prefixed("common.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(msg().prefixed("name.usage"));
            return;
        }
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim().replaceAll("\\s+", " ");
        if (!NAME.matcher(name).matches()) {
            sender.sendMessage(msg().prefixed("name.invalid"));
            return;
        }
        withRegion(sender, (player, r) -> {
            if (!r.state().isSettlement()) {
                player.sendMessage(msg().prefixed("name.not-settlement"));
                return;
            }
            InfluenceEntry dominant = regions().simulator().dominant(r);
            InfluenceGroup group = plugin.recorder().group(player);
            boolean admin = player.hasPermission("livingworld.admin");
            if (!admin && (dominant == null || !dominant.groupId().equals(group.id()))) {
                player.sendMessage(msg().prefixed("name.not-dominant"));
                return;
            }
            PlayerSession session = plugin.sessions().get(player.getUniqueId());
            long now = System.currentTimeMillis();
            if (!admin && session != null && now - session.lastRename() < RENAME_COOLDOWN_MS) {
                player.sendMessage(msg().prefixed("name.cooldown"));
                return;
            }
            if (session != null) {
                session.lastRename(now);
            }
            String old = fmt().regionName(r);
            r.customName(name);
            r.markDirty();
            plugin.history().record(HistoryEvent.of(now, HistoryEventType.REGION_NAMED, r.key(), r.development(),
                    old, player.getName()));
            player.sendMessage(msg().prefixed("name.success", Placeholder.unparsed("name", name)));
        });
    }

    // ================================================================== toggles

    private void toggle(CommandSender sender, boolean hud) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg().prefixed("common.players-only"));
            return;
        }
        LivingWorldConfig cfg = regions().config();
        PlayerSession s = plugin.sessions().getOrOpen(player, cfg.ui().bossBarDefault(), cfg.ui().actionBarOnEnter());
        if (hud) {
            s.hud(!s.hud());
            if (s.hud()) {
                RegionState r = s.currentRegion() == null ? null : regions().getLoaded(s.currentRegion());
                plugin.hud().updateBossBar(player, s, r);
            } else {
                plugin.hud().hideBossBar(player, s);
            }
            player.sendMessage(msg().prefixed(s.hud() ? "hud.enabled" : "hud.disabled"));
        } else {
            s.notifyOnEnter(!s.notifyOnEnter());
            player.sendMessage(msg().prefixed(s.notifyOnEnter() ? "hud.notify-enabled" : "hud.notify-disabled"));
        }
        plugin.sessions().savePreferences(player, s);
    }

    private void storageError(SenderRef ref, Throwable error) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, "Storage query failed", error);
        CommandSender to = ref.resolve();
        if (to != null) {
            to.sendMessage(msg().prefixed("common.storage-error"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            List<String> metrics = new ArrayList<>();
            for (TopMetric m : TopMetric.values()) {
                metrics.add(m.name().toLowerCase(Locale.ROOT));
            }
            return filter(metrics, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            return filter(List.of("all", "2", "3"), args[1]);
        }
        return List.of();
    }

    static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
