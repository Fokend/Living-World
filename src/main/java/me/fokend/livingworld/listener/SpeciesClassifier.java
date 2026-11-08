package me.fokend.livingworld.listener;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.model.SpeciesGroup;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;

/** Immutable lookup tables built from config: entity type -> species group, counted spawn reasons. */
public final class SpeciesClassifier {

    private final Map<EntityType, SpeciesGroup> groups = new EnumMap<>(EntityType.class);
    private final Set<CreatureSpawnEvent.SpawnReason> countedReasons;
    private final List<String> warnings = new ArrayList<>();

    public SpeciesClassifier(LivingWorldConfig.Wildlife cfg) {
        for (Map.Entry<SpeciesGroup, Set<String>> e : cfg.groupEntities().entrySet()) {
            for (String name : e.getValue()) {
                try {
                    EntityType type = EntityType.valueOf(name);
                    SpeciesGroup previous = groups.put(type, e.getKey());
                    if (previous != null && previous != e.getKey()) {
                        warnings.add("wildlife: " + name + " is listed in several groups, using " + e.getKey());
                    }
                } catch (IllegalArgumentException ex) {
                    warnings.add("wildlife: unknown entity type '" + name + "' ignored");
                }
            }
        }
        java.util.EnumSet<CreatureSpawnEvent.SpawnReason> reasons = java.util.EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
        for (String name : cfg.countedSpawnReasons()) {
            try {
                reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(name));
            } catch (IllegalArgumentException ex) {
                warnings.add("wildlife: unknown spawn reason '" + name + "' ignored");
            }
        }
        this.countedReasons = reasons;
    }

    public SpeciesGroup group(EntityType type) {
        return groups.get(type);
    }

    public boolean counted(CreatureSpawnEvent.SpawnReason reason) {
        return countedReasons.contains(reason);
    }

    public List<String> warnings() {
        return warnings;
    }
}
