package me.fokend.livingworld.model;

import java.util.Objects;

/**
 * Compact, immutable identifier of a logical region. Holds only the world name and region
 * grid coordinates; never a reference to a Bukkit {@code World}, so it is safe to keep in caches
 * and to pass to the storage thread.
 */
public record RegionKey(String world, int x, int z) {

    public RegionKey {
        Objects.requireNonNull(world, "world");
        world = world.intern();
    }

    public static RegionKey ofBlock(String world, int blockX, int blockZ, int regionSizeBlocks) {
        return new RegionKey(world, Math.floorDiv(blockX, regionSizeBlocks), Math.floorDiv(blockZ, regionSizeBlocks));
    }

    public RegionKey offset(int dx, int dz) {
        return new RegionKey(world, x + dx, z + dz);
    }

    public int centerBlockX(int regionSizeBlocks) {
        return x * regionSizeBlocks + regionSizeBlocks / 2;
    }

    public int centerBlockZ(int regionSizeBlocks) {
        return z * regionSizeBlocks + regionSizeBlocks / 2;
    }

    /** Stable hash used for deterministic name generation (independent of JVM string hashing changes). */
    public long stableHash() {
        long h = 1125899906842597L;
        for (int i = 0; i < world.length(); i++) {
            h = 31 * h + world.charAt(i);
        }
        h = h * 0x9E3779B97F4A7C15L + x;
        h = h * 0xBF58476D1CE4E5B9L + z;
        h ^= (h >>> 31);
        return h;
    }

    @Override
    public String toString() {
        return world + ":" + x + ":" + z;
    }
}
