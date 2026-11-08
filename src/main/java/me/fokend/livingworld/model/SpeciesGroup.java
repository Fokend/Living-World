package me.fokend.livingworld.model;

/** Abstract wildlife groups. Populations are tracked per region as a fraction of the biome capacity. */
public enum SpeciesGroup {
    GRAZERS,
    SMALL_GAME,
    PREDATORS,
    AQUATIC;

    private static final SpeciesGroup[] VALUES = values();

    public static SpeciesGroup[] all() {
        return VALUES;
    }

    public static int count() {
        return VALUES.length;
    }
}
