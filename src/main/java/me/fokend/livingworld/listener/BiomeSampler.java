package me.fokend.livingworld.listener;

import me.fokend.livingworld.model.BiomeProfile;
import org.bukkit.World;
import org.bukkit.block.Biome;

/**
 * Determines the profile of a brand-new region. Only reads already loaded chunks: if the requested
 * position's chunk is not loaded, {@link BiomeProfile#OTHER} is returned instead of loading it.
 */
public final class BiomeSampler {

    private BiomeSampler() {
    }

    public static BiomeProfile sample(World world, int x, int y, int z) {
        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return BiomeProfile.OTHER;
        }
        Biome biome = world.getBiome(x, y, z);
        return BiomeProfile.classify(biome.getKey().getKey());
    }
}
