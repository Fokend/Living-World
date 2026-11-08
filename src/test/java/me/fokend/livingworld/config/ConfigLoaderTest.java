package me.fokend.livingworld.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.SpeciesGroup;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    private static YamlConfiguration bundled() throws Exception {
        try (var in = ConfigLoaderTest.class.getResourceAsStream("/config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void bundledConfigMatchesDefaultsWithoutWarnings() throws Exception {
        ConfigLoader loader = new ConfigLoader(bundled());
        LivingWorldConfig cfg = loader.load();
        assertTrue(loader.warnings().isEmpty(), "warnings: " + loader.warnings());
        LivingWorldConfig d = Defaults.config();
        assertEquals(d.general(), cfg.general());
        assertEquals(d.simulation(), cfg.simulation());
        assertEquals(d.ecology(), cfg.ecology());
        assertEquals(d.wildlife(), cfg.wildlife());
        assertEquals(d.development(), cfg.development());
        assertEquals(d.influence(), cfg.influence());
        assertEquals(d.history(), cfg.history());
        assertEquals(d.cache(), cfg.cache());
        assertEquals(d.tracking(), cfg.tracking());
        assertEquals(d.ui(), cfg.ui());
        assertEquals(d.profiles(), cfg.profiles());
    }

    @Test
    void emptyConfigUsesDefaults() {
        ConfigLoader loader = new ConfigLoader(new YamlConfiguration());
        assertEquals(Defaults.config(), loader.load());
        assertTrue(loader.warnings().isEmpty());
    }

    @Test
    void invalidValuesAreClampedOrReplacedWithWarnings() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("general.region-size-chunks", 0);
        y.set("simulation.step-interval-seconds", "fast");
        y.set("simulation.time-scale", -5);
        y.set("ecology.enabled", "yes");
        y.set("ecology.stability-weights.forest", 0);
        y.set("ecology.stability-weights.wildlife", 0);
        y.set("ecology.stability-weights.exploitation", 0);
        y.set("ecology.events.critical-below", 0.9);
        y.set("development.thresholds.active", 1.0);
        y.set("development.overexploited.exit-stability-above", 0.1);
        y.set("wildlife.events.recovered-above", 0.1);
        y.set("cache.max-loaded-regions", 10);
        y.set("history.steward-milestones", java.util.List.of(100, -5, 25, 25));
        y.set("biome-profiles.forest.forest-capacity", -1);
        y.set("biome-profiles.atlantis.forest-capacity", 5);
        ConfigLoader loader = new ConfigLoader(y);
        LivingWorldConfig cfg = loader.load();
        assertEquals(2, cfg.general().regionSizeChunks());
        assertEquals(60, cfg.simulation().stepIntervalSeconds());
        assertEquals(0.01, cfg.simulation().timeScale());
        assertTrue(cfg.ecology().enabled());
        assertEquals(0.45, cfg.ecology().weightForest());
        assertTrue(cfg.ecology().criticalForestThreshold() < cfg.ecology().deforestationThreshold());
        assertEquals(35.0, cfg.development().activeThreshold());
        assertEquals(0.45, cfg.development().overexploitExitStability());
        assertTrue(cfg.wildlife().recoveredThreshold() > cfg.wildlife().declineThreshold());
        assertEquals(64, cfg.cache().maxLoadedRegions());
        assertEquals(java.util.List.of(25, 100), cfg.history().stewardMilestones());
        assertEquals(1.0, cfg.profile(BiomeProfile.FOREST).forestCapacity());
        assertTrue(loader.warnings().size() >= 12, "warnings: " + loader.warnings());
        assertTrue(loader.warnings().stream().anyMatch(w -> w.contains("atlantis")));
    }

    @Test
    void areaScalingAffectsCapacities() {
        LivingWorldConfig d = Defaults.config();
        LivingWorldConfig small = new LivingWorldConfig(new LivingWorldConfig.General(d.general().enabledWorlds(), 8,
                false), d.simulation(), d.ecology(), d.wildlife(), d.development(), d.influence(), d.history(),
                d.cache(), d.tracking(), d.ui(), d.profiles());
        assertEquals(d.forestCapacityLogs(BiomeProfile.FOREST) / 4, small.forestCapacityLogs(BiomeProfile.FOREST), 1e-9);
        assertEquals(1.0, d.herdSize(BiomeProfile.OCEAN, SpeciesGroup.GRAZERS), "absent group has minimal herd");
    }
}
