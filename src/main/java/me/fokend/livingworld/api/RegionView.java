package me.fokend.livingworld.api;

import java.util.Map;
import me.fokend.livingworld.model.BiomeProfile;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.RegionKey;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.model.SpeciesGroup;

/**
 * Immutable, read-only view of a region for other plugins (e.g. a future Territory Eco-Anomalies module
 * that wants to scale anomalies with ecological stability). Values are in [0, 1] unless noted.
 *
 * @param development 0..100
 * @param dominantGroupId id of the clearly dominant influence group, or null
 */
public record RegionView(RegionKey key, String name, BiomeProfile profile, DevelopmentState state, double development,
                         double forest, double stability, double exploitation,
                         Map<SpeciesGroup, Double> populations, String dominantGroupId) {

    public static RegionView of(RegionState r, String name) {
        Map<SpeciesGroup, Double> pops = new java.util.EnumMap<>(SpeciesGroup.class);
        for (SpeciesGroup g : SpeciesGroup.all()) {
            pops.put(g, r.population(g));
        }
        return new RegionView(r.key(), name, r.profile(), r.state(), r.development(), r.forest(), r.stability(),
                r.exploitation(), Map.copyOf(pops), r.dominantGroup());
    }
}
