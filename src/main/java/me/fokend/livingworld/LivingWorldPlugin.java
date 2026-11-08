package me.fokend.livingworld;

import java.io.File;
import java.util.List;
import java.util.logging.Level;
import me.fokend.livingworld.api.LivingWorldApi;
import me.fokend.livingworld.api.LivingWorldApiImpl;
import me.fokend.livingworld.command.AdminCommand;
import me.fokend.livingworld.command.WorldCommand;
import me.fokend.livingworld.config.ConfigLoader;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.history.HistoryService;
import me.fokend.livingworld.integration.InfluenceResolverChain;
import me.fokend.livingworld.integration.ScoreboardTeamResolver;
import me.fokend.livingworld.listener.ActivityListener;
import me.fokend.livingworld.listener.ActivityRecorder;
import me.fokend.livingworld.listener.PlacementMemory;
import me.fokend.livingworld.listener.PlayerListener;
import me.fokend.livingworld.listener.SpeciesClassifier;
import me.fokend.livingworld.listener.WildlifeListener;
import me.fokend.livingworld.player.PlayerSession;
import me.fokend.livingworld.player.PresenceTracker;
import me.fokend.livingworld.player.SessionRegistry;
import me.fokend.livingworld.region.RegionManager;
import me.fokend.livingworld.sim.SimClock;
import me.fokend.livingworld.storage.SqliteStorage;
import me.fokend.livingworld.storage.StorageException;
import me.fokend.livingworld.storage.StorageService;
import me.fokend.livingworld.task.MainThread;
import me.fokend.livingworld.task.TaskManager;
import me.fokend.livingworld.ui.Format;
import me.fokend.livingworld.ui.HudService;
import me.fokend.livingworld.ui.Messages;
import me.fokend.livingworld.ui.Notifier;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Living World entry point and composition root. There are no static references to the plugin or to
 * any service: everything is created in {@link #onEnable()} and released in {@link #onDisable()}.
 */
public final class LivingWorldPlugin extends JavaPlugin {

    private static final String DB_FILE = "living-world.db";
    private static final String META_REGION_SIZE = "region_size_chunks";
    private static final long DAY_TICKS = 20L * 60 * 60 * 24;

    private volatile boolean acceptingMainThreadWork;
    private LivingWorldConfig config;
    private int lastWarnings;
    private int storedRegionSize;

    private Messages messages;
    private Format format;
    private SqliteStorage sqlite;
    private StorageService storage;
    private HistoryService history;
    private SimClock clock;
    private RegionManager regions;
    private SessionRegistry sessions;
    private InfluenceResolverChain resolvers;
    private ActivityRecorder recorder;
    private PlacementMemory<Boolean> placedLogs;
    private PlacementMemory<PlacementMemory.Planter> saplings;
    private WildlifeListener wildlifeListener;
    private HudService hud;
    private PresenceTracker presence;
    private TaskManager tasks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }
        LivingWorldConfig loaded = loadConfigFile();
        messages = new Messages();
        messages.load(new File(getDataFolder(), "messages.yml"), getResource("messages.yml"), getLogger());
        format = new Format(messages);

        // Storage is opened synchronously once at startup (no gameplay is running yet).
        sqlite = new SqliteStorage(new File(getDataFolder(), DB_FILE), getLogger());
        long simClock;
        try {
            sqlite.open();
            storedRegionSize = (int) sqlite.metaLong(META_REGION_SIZE, -1);
            if (storedRegionSize <= 0) {
                storedRegionSize = loaded.general().regionSizeChunks();
                sqlite.putMeta(META_REGION_SIZE, String.valueOf(storedRegionSize));
            }
            simClock = sqlite.metaLong("sim_clock", 0);
            long newestRegion = sqlite.maxLastSimulated();
            if (newestRegion > simClock) {
                getLogger().warning("Simulation clock in the database (" + simClock + ") is behind the newest region ("
                        + newestRegion + "); the clock entry was probably lost. Resuming from the region data.");
                simClock = newestRegion;
            }
        } catch (StorageException | java.sql.SQLException ex) {
            getLogger().log(Level.SEVERE, "Cannot open the Living World database; disabling the plugin", ex);
            sqlite.close();
            sqlite = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        config = enforceRegionSize(loaded);

        acceptingMainThreadWork = true;
        MainThread mainThread = task -> {
            if (!acceptingMainThreadWork) {
                return;
            }
            try {
                getServer().getScheduler().runTask(this, () -> {
                    if (acceptingMainThreadWork) {
                        task.run();
                    }
                });
            } catch (IllegalPluginAccessException ignored) {
                // plugin is being disabled; the result is no longer needed
            }
        };
        storage = new StorageService(sqlite, mainThread, getLogger(), config.cache().dbQueueCapacity());
        history = new HistoryService(config.history());
        clock = new SimClock(simClock, config.simulation().timeScale());
        regions = new RegionManager(config, storage, history, clock, getLogger());
        sessions = new SessionRegistry(this);
        resolvers = new InfluenceResolverChain(getLogger());
        resolvers.teamAdapter(config.influence().useScoreboardTeams() ? new ScoreboardTeamResolver() : null);
        recorder = new ActivityRecorder(regions, sessions, resolvers);
        placedLogs = new PlacementMemory<>(config.cache().placedLogMemory());
        saplings = new PlacementMemory<>(config.cache().saplingMemory());
        hud = new HudService(format);
        Notifier notifier = new Notifier(regions, sessions, format, hud, this::cfg);
        history.publisher(notifier::onHistory);
        regions.transitionListener(notifier);
        presence = new PresenceTracker(regions, recorder, sessions, hud);

        SpeciesClassifier classifier = buildClassifier();
        wildlifeListener = new WildlifeListener(recorder, regions, classifier);
        var pm = getServer().getPluginManager();
        pm.registerEvents(new ActivityListener(recorder, placedLogs, saplings), this);
        pm.registerEvents(wildlifeListener, this);
        pm.registerEvents(new PlayerListener(sessions, hud, resolvers, this::cfg, this), this);

        registerCommand("world", new WorldCommand(this));
        registerCommand("lwadmin", new AdminCommand(this));

        getServer().getServicesManager().register(LivingWorldApi.class, new LivingWorldApiImpl(this), this,
                ServicePriority.Normal);

        // Players already online (e.g. after /reload) get sessions immediately.
        for (Player p : getServer().getOnlinePlayers()) {
            sessions.open(p, config.ui().bossBarDefault(), config.ui().actionBarOnEnter());
        }

        tasks = new TaskManager(this);
        startTasks();
        getLogger().info("Living World enabled: region " + config.general().regionSizeBlocks() + "x"
                + config.general().regionSizeBlocks() + " blocks, worlds "
                + (config.general().enabledWorlds().isEmpty() ? "ALL" : config.general().enabledWorlds())
                + ", sim-time " + String.format(java.util.Locale.ROOT, "%.2f", clock.now() / 86_400_000.0) + " days");
    }

    private void registerCommand(String name, org.bukkit.command.TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void startTasks() {
        tasks.stopAll();
        LivingWorldConfig c = config;
        tasks.repeat("simulation", () -> regions.tickSimulation(System.currentTimeMillis()), 20L, 1L);
        tasks.repeat("presence", presence, 40L, c.simulation().presenceIntervalTicks());
        long autosave = c.simulation().autosaveSeconds() * 20L;
        tasks.repeat("autosave", regions::saveAsync, autosave, autosave);
        tasks.repeat("maintenance", this::maintenance, 1200L, 1200L);
        long sweep = c.cache().dormantSweepMinutes() * 1200L;
        tasks.repeat("dormant-sweep", () -> regions.dormantSweep(System.currentTimeMillis()), sweep, sweep);
        tasks.repeat("history-retention", this::pruneHistory, 2400L, DAY_TICKS / 4);
    }

    private void maintenance() {
        regions.evict(System.currentTimeMillis(), true);
        // Defensive: drop sessions of players that are no longer online.
        sessions.all().removeIf(s -> getServer().getPlayer(s.playerId()) == null);
    }

    private void pruneHistory() {
        LivingWorldConfig.History h = config.history();
        long cutoff = System.currentTimeMillis() - h.maxAgeDays() * 86_400_000L;
        int max = h.maxEvents();
        storage.submit(st -> st.pruneHistory(cutoff, max), removed -> {
            if (removed > 0 && config.general().debug()) {
                getLogger().info("History retention removed " + removed + " entries");
            }
        }, error -> getLogger().log(Level.WARNING, "History retention failed", error));
    }

    // ===================================================================== configuration

    private LivingWorldConfig loadConfigFile() {
        reloadConfig();
        ConfigLoader loader = new ConfigLoader(getConfig());
        LivingWorldConfig c = loader.load();
        for (String w : loader.warnings()) {
            getLogger().warning("config.yml " + w);
        }
        lastWarnings = loader.warnings().size();
        return c;
    }

    private LivingWorldConfig enforceRegionSize(LivingWorldConfig c) {
        if (c.general().regionSizeChunks() == storedRegionSize) {
            return c;
        }
        getLogger().warning("general.region-size-chunks is " + c.general().regionSizeChunks() + " but the database "
                + "was created with " + storedRegionSize + ". Keeping " + storedRegionSize + " so existing regions stay "
                + "consistent. Delete " + DB_FILE + " to start over with a new size.");
        lastWarnings++;
        LivingWorldConfig.General g = new LivingWorldConfig.General(c.general().enabledWorlds(), storedRegionSize,
                c.general().debug());
        return new LivingWorldConfig(g, c.simulation(), c.ecology(), c.wildlife(), c.development(), c.influence(),
                c.history(), c.cache(), c.tracking(), c.ui(), c.profiles());
    }

    private SpeciesClassifier buildClassifier() {
        SpeciesClassifier classifier = new SpeciesClassifier(config.wildlife());
        for (String w : classifier.warnings()) {
            getLogger().warning("config.yml " + w);
        }
        lastWarnings += classifier.warnings().size();
        return classifier;
    }

    /**
     * Applies a new config.yml/messages.yml at runtime. Listeners stay registered (they read the current
     * config on every event); tasks are stopped and restarted exactly once.
     *
     * @return number of configuration warnings
     */
    public int reloadLivingWorld() {
        LivingWorldConfig fresh = enforceRegionSize(loadConfigFile());
        messages.load(new File(getDataFolder(), "messages.yml"), getResource("messages.yml"), getLogger());
        config = fresh;
        regions.config(fresh);
        history.config(fresh.history());
        clock.timeScale(fresh.simulation().timeScale());
        resolvers.teamAdapter(fresh.influence().useScoreboardTeams() ? new ScoreboardTeamResolver() : null);
        wildlifeListener.classifier(buildClassifier());
        if (placedLogs.capacity() != fresh.cache().placedLogMemory()
                || saplings.capacity() != fresh.cache().saplingMemory()) {
            // The memories are held by the registered listener; they stay bounded by their old capacity.
            getLogger().info("cache.placed-log-memory / cache.sapling-memory changes take effect after a restart");
        }
        startTasks();
        return lastWarnings;
    }

    // ===================================================================== shutdown

    @Override
    public void onDisable() {
        acceptingMainThreadWork = false;
        if (tasks != null) {
            tasks.stopAll();
        }
        HandlerList.unregisterAll(this);
        getServer().getServicesManager().unregisterAll(this);
        if (sessions != null) {
            if (hud != null) {
                for (PlayerSession s : List.copyOf(sessions.all())) {
                    hud.hideBossBar(getServer().getPlayer(s.playerId()), s);
                }
            }
            sessions.clear();
        }
        if (storage != null) {
            // Final save of every resident region (not only dirty ones), so an autosave that was still
            // running when the server stopped cannot lose data.
            storage.shutdown(st -> {
                st.saveBatch(regions.snapshotAll(), history.drainUnsaved(), clock.tick());
                return null;
            }, 15);
        } else if (sqlite != null) {
            sqlite.close();
        }
        if (regions != null) {
            regions.clear();
            regions.transitionListener(null);
        }
        if (history != null) {
            history.publisher(null);
            history.clearMemory();
        }
        if (resolvers != null) {
            resolvers.clear();
        }
        if (placedLogs != null) {
            placedLogs.clear();
            saplings.clear();
        }
        storage = null;
        sqlite = null;
        regions = null;
        history = null;
        sessions = null;
        resolvers = null;
        recorder = null;
        presence = null;
        wildlifeListener = null;
        hud = null;
        tasks = null;
        clock = null;
        // Paper may keep a reference to the last disabled plugin instance; keep that object empty.
        messages = null;
        format = null;
        config = null;
        getLogger().info("Living World disabled, data saved.");
    }

    // ===================================================================== accessors (main thread)

    public LivingWorldConfig cfg() {
        return config;
    }

    public Format format() { return format; }
    public StorageService storage() { return storage; }
    public HistoryService history() { return history; }
    public SimClock clock() { return clock; }
    public RegionManager regions() { return regions; }
    public SessionRegistry sessions() { return sessions; }
    public InfluenceResolverChain resolvers() { return resolvers; }
    public ActivityRecorder recorder() { return recorder; }
    public HudService hud() { return hud; }
    public TaskManager tasks() { return tasks; }
    public PlacementMemory<Boolean> placedLogs() { return placedLogs; }
    public PlacementMemory<PlacementMemory.Planter> saplings() { return saplings; }
}
