package me.fokend.livingworld.storage;

import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.HistoryEvent;

/**
 * Chronicle entry joined with the current name/profile of its region (both may be null when the region
 * row does not exist any more).
 */
public record HistoryRow(HistoryEvent event, String customName, BiomeProfile profile) {
}
