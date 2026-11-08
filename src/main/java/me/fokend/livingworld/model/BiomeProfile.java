package me.fokend.livingworld.model;

import java.util.Locale;

/**
 * Coarse ecological profile of a region, derived from a biome key sampled once when the region
 * is first created. Living World never re-scans terrain afterwards.
 */
public enum BiomeProfile {
    FOREST,
    TAIGA,
    JUNGLE,
    SWAMP,
    PLAINS,
    SAVANNA,
    DESERT,
    BADLANDS,
    MOUNTAINS,
    SNOWY,
    BEACH,
    RIVER,
    OCEAN,
    MUSHROOM,
    CAVE,
    OTHER;

    /** Classifies a namespaced biome key path such as {@code dark_forest} or {@code minecraft:snowy_taiga}. */
    public static BiomeProfile classify(String biomeKey) {
        if (biomeKey == null || biomeKey.isEmpty()) {
            return OTHER;
        }
        String k = biomeKey.toLowerCase(Locale.ROOT);
        int colon = k.indexOf(':');
        if (colon >= 0) {
            k = k.substring(colon + 1);
        }
        if (k.contains("mushroom")) return MUSHROOM;
        if (k.contains("ocean")) return OCEAN;
        if (k.contains("river")) return RIVER;
        if (k.contains("beach") || k.contains("shore")) return BEACH;
        if (k.contains("jungle") || k.contains("bamboo")) return JUNGLE;
        if (k.contains("swamp") || k.contains("mangrove")) return SWAMP;
        if (k.contains("badlands")) return BADLANDS;
        if (k.contains("desert")) return DESERT;
        if (k.contains("savanna")) return SAVANNA;
        if (k.contains("taiga") || k.equals("grove")) return TAIGA;
        if (k.contains("forest") || k.contains("birch") || k.contains("cherry") || k.contains("pale_garden")) return FOREST;
        if (k.contains("cave") || k.contains("deep_dark") || k.contains("dripstone")) return CAVE;
        if (k.contains("snowy") || k.contains("frozen") || k.contains("ice")) return SNOWY;
        if (k.contains("peak") || k.contains("slope") || k.contains("windswept") || k.contains("stony")
                || k.contains("hills") || k.contains("mountain")) return MOUNTAINS;
        if (k.contains("plains") || k.contains("meadow")) return PLAINS;
        return OTHER;
    }

    public static BiomeProfile parse(String raw, BiomeProfile fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
