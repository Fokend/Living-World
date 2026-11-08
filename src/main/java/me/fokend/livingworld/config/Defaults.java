package me.fokend.livingworld.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.SpeciesGroup;

/**
 * Built-in defaults. They mirror {@code config.yml}; a unit test asserts that the bundled config file
 * parses into exactly these values, so the two cannot silently drift apart.
 */
public final class Defaults {

    private Defaults() {
    }

    public static LivingWorldConfig.General general() {
        return new LivingWorldConfig.General(Set.of("world"), 16, false);
    }

    public static LivingWorldConfig.Simulation simulation() {
        return new LivingWorldConfig.Simulation(60, 200, 1.0, 30.0, 120, 100);
    }

    public static LivingWorldConfig.Ecology ecology() {
        return new LivingWorldConfig.Ecology(true, 0.02, 0.25, 1.5, 24.0, 0.45, 0.35, 0.20, 0.10, 1.0, 1.0,
                0.15, 400.0, 0.5, 0.2, 0.8, 300.0);
    }

    public static LivingWorldConfig.Wildlife wildlife() {
        Map<SpeciesGroup, Set<String>> entities = new EnumMap<>(SpeciesGroup.class);
        entities.put(SpeciesGroup.GRAZERS, Set.of("COW", "MOOSHROOM", "SHEEP", "PIG", "HORSE", "DONKEY", "MULE",
                "GOAT", "LLAMA", "CAMEL", "PANDA", "SNIFFER"));
        entities.put(SpeciesGroup.SMALL_GAME, Set.of("CHICKEN", "RABBIT", "FROG", "ARMADILLO", "PARROT"));
        entities.put(SpeciesGroup.PREDATORS, Set.of("WOLF", "FOX", "POLAR_BEAR", "OCELOT"));
        entities.put(SpeciesGroup.AQUATIC, Set.of("COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH", "SQUID",
                "GLOW_SQUID", "TURTLE", "DOLPHIN", "AXOLOTL"));
        return new LivingWorldConfig.Wildlife(true,
                LivingWorldConfig.groupMap(40, 40, 10, 80),
                LivingWorldConfig.groupMap(0.35, 0.6, 0.2, 0.5),
                0.03, 0.35, 0.35, Set.of("NATURAL", "CHUNK_GEN"), entities,
                true, 0.4, 0.6, 0.3, 0.7, 1.0);
    }

    public static LivingWorldConfig.Development development() {
        return new LivingWorldConfig.Development(true, 2.0, 1.5, 3.0, 1.5, 3.0, 10.0,
                8.0, 35.0, 1.0, 50.0, 40.0, 0.6, 20.0, 14.0, 0.5, 3.0, 0.6,
                0.30, 0.75, 0.45, 0.50, 30.0, 120);
    }

    public static LivingWorldConfig.Influence influence() {
        return new LivingWorldConfig.Influence(true, false, 0.1, 0.02, 0.25, 0.5, 7.0, 0.5, 25.0, 1.5, 60.0, 0.02, 0.5);
    }

    public static LivingWorldConfig.History history() {
        return new LivingWorldConfig.History(true, 5000, 180, 100, 120, true, true, List.of(25, 100, 250));
    }

    public static LivingWorldConfig.Cache cache() {
        return new LivingWorldConfig.Cache(2048, 10, 256, 512, 8192, 4096, true, 10, 32, 6.0);
    }

    public static LivingWorldConfig.Tracking tracking() {
        return new LivingWorldConfig.Tracking(true, true, true, true, true, true);
    }

    public static LivingWorldConfig.Interface ui() {
        return new LivingWorldConfig.Interface(true, false, true, 0.15);
    }

    public static Map<BiomeProfile, LivingWorldConfig.ProfileSettings> profiles() {
        Map<BiomeProfile, LivingWorldConfig.ProfileSettings> m = new EnumMap<>(BiomeProfile.class);
        // forest capacity = approx. number of natural logs in a 256x256 region of that biome.
        m.put(BiomeProfile.FOREST, p(4000, 1.0, 1.0, 0.8, 0.2));
        m.put(BiomeProfile.TAIGA, p(4000, 0.8, 1.0, 1.0, 0.2));
        m.put(BiomeProfile.JUNGLE, p(6000, 0.6, 1.0, 0.6, 0.3));
        m.put(BiomeProfile.SWAMP, p(2000, 0.4, 1.0, 0.3, 0.6));
        m.put(BiomeProfile.PLAINS, p(600, 1.0, 1.0, 0.4, 0.2));
        m.put(BiomeProfile.SAVANNA, p(1200, 1.0, 0.6, 0.5, 0.1));
        m.put(BiomeProfile.DESERT, p(100, 0.3, 0.8, 0.1, 0.0));
        m.put(BiomeProfile.BADLANDS, p(150, 0.2, 0.5, 0.1, 0.0));
        m.put(BiomeProfile.MOUNTAINS, p(800, 0.8, 0.6, 0.5, 0.1));
        m.put(BiomeProfile.SNOWY, p(800, 0.5, 0.8, 0.8, 0.3));
        m.put(BiomeProfile.BEACH, p(200, 0.2, 0.6, 0.1, 0.8));
        m.put(BiomeProfile.RIVER, p(600, 0.6, 0.8, 0.3, 1.0));
        m.put(BiomeProfile.OCEAN, p(200, 0.0, 0.0, 0.0, 1.0));
        m.put(BiomeProfile.MUSHROOM, p(300, 1.0, 0.0, 0.0, 0.3));
        m.put(BiomeProfile.CAVE, p(300, 0.3, 0.3, 0.1, 0.6));
        m.put(BiomeProfile.OTHER, p(1000, 0.6, 0.6, 0.4, 0.3));
        return m;
    }

    private static LivingWorldConfig.ProfileSettings p(double forest, double grazers, double small, double predators,
                                                       double aquatic) {
        return new LivingWorldConfig.ProfileSettings(forest,
                LivingWorldConfig.groupMap(grazers, small, predators, aquatic));
    }

    public static LivingWorldConfig config() {
        return new LivingWorldConfig(general(), simulation(), ecology(), wildlife(), development(), influence(),
                history(), cache(), tracking(), ui(), profiles());
    }
}
