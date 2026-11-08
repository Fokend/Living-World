package me.fokend.livingworld.command;

import java.util.List;
import java.util.Locale;
import me.fokend.livingworld.LivingWorldPlugin;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.model.SpeciesGroup;
import me.fokend.livingworld.region.RegionManager;
import me.fokend.livingworld.ui.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /lwadmin} — administration and diagnostics. Requires {@code livingworld.admin}. */
public final class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "save", "stats", "inspect", "set", "simulate",
            "reset", "purge-history");

    private final LivingWorldPlugin plugin;

    public AdminCommand(LivingWorldPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.format().messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("livingworld.admin")) {
            sender.sendMessage(msg().prefixed("common.no-permission"));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "save" -> sender.sendMessage(msg().prefixed(plugin.regions().saveAsync() ? "admin.saved" : "admin.save-busy"));
            case "stats" -> stats(sender);
            case "inspect" -> withRegion(sender, r -> inspect(sender, r));
            case "set" -> set(sender, args);
            case "simulate" -> simulate(sender, args);
            case "reset" -> withRegion(sender, r -> {
                plugin.regions().reset(r.key());
                sender.sendMessage(msg().prefixed("admin.reset-done"));
            });
            case "purge-history" -> purge(sender);
            default -> sender.sendMessage(msg().prefixed("admin.usage"));
        }
        return true;
    }

    private void reload(CommandSender sender) {
        try {
            int warnings = plugin.reloadLivingWorld();
            sender.sendMessage(msg().prefixed("admin.reloaded", Placeholder.unparsed("warnings", String.valueOf(warnings))));
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Reload failed", ex);
            sender.sendMessage(msg().prefixed("admin.reload-failed",
                    Placeholder.unparsed("error", String.valueOf(ex.getMessage()))));
        }
    }

    private void stats(CommandSender sender) {
        RegionManager r = plugin.regions();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        line(sender, "Регионы в памяти", r.loadedCount() + " / " + r.config().cache().maxLoadedRegions()
                + " (изменённых: " + r.dirtyCount() + ")");
        line(sender, "Загружаются", r.pendingCount() + " / " + r.config().cache().maxPendingLoads()
                + ", отложенные ошибки: " + r.failedCount());
        line(sender, "Симуляция", "шагов: " + r.stepsTotal() + ", проходов: " + r.passesTotal()
                + ", последний проход: " + r.lastPassMicros() + " мкс, в очереди: " + r.passQueueSize());
        line(sender, "Выгружено / загружено", r.evictedTotal() + " / " + r.loadsTotal()
                + ", потеряно событий активности: " + r.droppedActivity());
        line(sender, "Хранилище", "очередь: " + plugin.storage().queueSize() + ", отклонено: "
                + plugin.storage().rejectedCount() + ", ошибок: " + plugin.storage().failureCount()
                + ", сохранение идёт: " + r.saveInFlight());
        line(sender, "Летопись", "в памяти: " + plugin.history().recentSize() + ", не сохранено: "
                + plugin.history().unsavedSize() + ", отброшено: " + plugin.history().droppedCount());
        line(sender, "Сессии игроков", String.valueOf(plugin.sessions().size()));
        line(sender, "Память брёвен / саженцев", plugin.placedLogs().size() + "/" + plugin.placedLogs().capacity()
                + " · " + plugin.saplings().size() + "/" + plugin.saplings().capacity());
        line(sender, "Задачи планировщика", plugin.tasks().activeCount() + ", резолверы влияния: "
                + plugin.resolvers().externalCount());
        line(sender, "Сим-время", String.format(Locale.ROOT, "%.2f дн", plugin.clock().now() / 86_400_000.0));
        line(sender, "JVM heap", usedMb + " МБ (общая память сервера, не только плагин)");
    }

    private static void line(CommandSender sender, String key, String value) {
        sender.sendMessage(Component.text(key + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE)));
    }

    private void inspect(CommandSender sender, RegionState r) {
        line(sender, "Регион", r.key() + " · " + r.profile() + " · " + r.state()
                + (r.candidateState() != null ? " → " + r.candidateState() : ""));
        line(sender, "Развитие", String.format(Locale.ROOT, "%.2f (пик %.2f)", r.development(), r.peakDevelopment()));
        line(sender, "Экология", String.format(Locale.ROOT, "лес %.3f, стабильность %.3f, эксплуатация %.3f, давление %.4f/сут",
                r.forest(), r.stability(), r.exploitation(), r.pressureEma()));
        StringBuilder pops = new StringBuilder();
        for (SpeciesGroup g : SpeciesGroup.all()) {
            pops.append(g.name().toLowerCase(Locale.ROOT)).append('=')
                    .append(String.format(Locale.ROOT, "%.3f", r.population(g))).append(' ');
        }
        line(sender, "Популяции", pops.toString().trim());
        line(sender, "Активность/сут", String.format(Locale.ROOT, "присутствие %.2f ч, стройка %.1f, торговля %.1f",
                r.presenceEma(), r.buildEma(), r.tradeEma()));
        line(sender, "Бездействие", String.format(Locale.ROOT, "%.2f дн",
                Math.min(9999, me.fokend.livingworld.sim.RegionSimulator.inactiveDays(r, plugin.clock().now()))));
        line(sender, "Итого", "срублено " + r.totalLogsCut() + ", выращено " + r.totalTreesGrown() + ", охота "
                + r.totalKills() + ", сделок " + r.totalTrades() + ", построек " + r.totalBuild());
        line(sender, "Влияние", r.influenceSorted().size() + " групп, доминирует: " + r.dominantGroup());
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg().prefixed("admin.set-usage"));
            return;
        }
        String field = args[1].toLowerCase(Locale.ROOT);
        String value = args[2];
        withRegion(sender, r -> {
            try {
                if (field.equals("state")) {
                    r.state(DevelopmentState.valueOf(value.toUpperCase(Locale.ROOT)));
                    r.candidateState(null);
                } else {
                    double v = Double.parseDouble(value);
                    if (!Double.isFinite(v)) {
                        throw new NumberFormatException();
                    }
                    switch (field) {
                        case "forest" -> r.forest(v);
                        case "stability" -> r.stability(v);
                        case "exploitation" -> r.exploitation(v);
                        case "development" -> r.development(v);
                        default -> {
                            if (!field.startsWith("pop.")) {
                                throw new IllegalArgumentException();
                            }
                            r.population(SpeciesGroup.valueOf(field.substring(4).toUpperCase(Locale.ROOT)), v);
                        }
                    }
                }
                r.markDirty();
                sender.sendMessage(msg().prefixed("admin.set-done"));
            } catch (IllegalArgumentException ex) {
                sender.sendMessage(msg().prefixed("admin.set-invalid"));
            }
        });
    }

    private void simulate(CommandSender sender, String[] args) {
        double hours;
        try {
            hours = args.length > 1 ? Double.parseDouble(args[1]) : -1;
        } catch (NumberFormatException ex) {
            hours = -1;
        }
        if (!(hours > 0 && hours <= 24 * 365)) {
            sender.sendMessage(msg().prefixed("admin.simulate-usage"));
            return;
        }
        final double h = hours;
        withRegion(sender, r -> {
            plugin.regions().fastForward(r, (long) (h * 3_600_000L));
            sender.sendMessage(msg().prefixed("admin.simulate-done",
                    Placeholder.unparsed("hours", me.fokend.livingworld.ui.Format.number(h))));
        });
    }

    private void purge(CommandSender sender) {
        SenderRef ref = SenderRef.of(sender);
        plugin.history().clearMemory();
        boolean ok = plugin.storage().submit(st -> st.clearHistory(), count -> {
            CommandSender to = ref.resolve();
            if (to != null) {
                to.sendMessage(msg().prefixed("admin.purge-done", Placeholder.unparsed("count", String.valueOf(count))));
            }
        }, error -> plugin.getLogger().log(java.util.logging.Level.WARNING, "Purge failed", error));
        if (!ok) {
            sender.sendMessage(msg().prefixed("common.storage-busy"));
        }
    }

    private void withRegion(CommandSender sender, java.util.function.Consumer<RegionState> action) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg().prefixed("common.players-only"));
            return;
        }
        if (!plugin.recorder().worldEnabled(player.getWorld())) {
            sender.sendMessage(msg().prefixed("common.world-disabled"));
            return;
        }
        RegionKey key = plugin.recorder().key(player.getWorld(), (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
        RegionState r = plugin.regions().getLoaded(key);
        if (r == null) {
            sender.sendMessage(msg().prefixed("common.loading"));
            return;
        }
        action.accept(r);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("livingworld.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return WorldCommand.filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return WorldCommand.filter(List.of("forest", "stability", "exploitation", "development", "state",
                    "pop.grazers", "pop.small_game", "pop.predators", "pop.aquatic"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("state")) {
            return WorldCommand.filter(java.util.Arrays.stream(DevelopmentState.values())
                    .map(s -> s.name().toLowerCase(Locale.ROOT)).toList(), args[2]);
        }
        return List.of();
    }
}
