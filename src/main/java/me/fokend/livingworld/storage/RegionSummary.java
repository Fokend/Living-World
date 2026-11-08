package me.fokend.livingworld.storage;

import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.RegionKey;

/** Lightweight row used by rankings; never kept beyond the command that requested it. */
public record RegionSummary(RegionKey key, String customName, BiomeProfile profile, DevelopmentState state,
                            double development, double stability, double forest, double exploitation,
                            double tradeEma, long treesGrown) {
}
