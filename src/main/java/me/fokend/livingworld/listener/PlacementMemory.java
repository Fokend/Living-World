package me.fokend.livingworld.listener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fixed-capacity LRU memory of block positions:
 * <ul>
 *   <li>logs placed by players (breaking them again is construction, not deforestation);</li>
 *   <li>saplings planted by players (so a tree that later grows naturally is credited to its planter).</li>
 * </ul>
 * Keys are packed longs (block key mixed with a world hash) — no Bukkit objects are retained.
 * Capacity 0 disables the memory. Main thread only.
 */
public final class PlacementMemory<V> {

    /** Planter of a sapling. */
    public record Planter(UUID playerId, String name) {
    }

    private final int capacity;
    private final LinkedHashMap<Long, V> map;

    public PlacementMemory(int capacity) {
        this.capacity = Math.max(0, capacity);
        this.map = new LinkedHashMap<>(Math.min(this.capacity, 1024) + 16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > PlacementMemory.this.capacity;
            }
        };
    }

    /** Packs block coordinates (x/z: 26 bits, y: 12 bits) and mixes in the world so worlds do not collide. */
    public static long key(int worldHash, int x, int y, int z) {
        long packed = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
        return packed ^ ((long) worldHash * 0x9E3779B97F4A7C15L);
    }

    public void put(long key, V value) {
        if (capacity > 0) {
            map.put(key, value);
        }
    }

    public V remove(long key) {
        return capacity == 0 ? null : map.remove(key);
    }

    public int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        map.clear();
    }
}
