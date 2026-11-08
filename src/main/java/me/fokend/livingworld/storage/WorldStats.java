package me.fokend.livingworld.storage;

import java.util.Map;
import me.fokend.livingworld.model.DevelopmentState;

public record WorldStats(int regions, Map<DevelopmentState, Integer> byState, double avgStability, double avgForest,
                         long totalTreesGrown, long totalLogsCut, long historyEvents) {
}
