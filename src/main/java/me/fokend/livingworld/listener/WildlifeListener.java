package me.fokend.livingworld.listener;

import java.util.concurrent.ThreadLocalRandom;
import me.fokend.livingworld.config.LivingWorldConfig;
import me.fokend.livingworld.model.Accumulators;
import me.fokend.livingworld.model.RegionState;
import me.fokend.livingworld.model.SpeciesGroup;
import me.fokend.livingworld.region.RegionManager;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

/**
 * Hunting, fishing and trading observation plus the only gameplay-affecting mechanic of the wildlife
 * simulation: when a region's abstract population of a group is depleted, a share of that group's
 * NATURAL spawns is cancelled. No entities are ever created, scanned or removed.
 */
public final class WildlifeListener implements Listener {

    private final ActivityRecorder recorder;
    private final RegionManager regions;
    private volatile SpeciesClassifier classifier;

    public WildlifeListener(ActivityRecorder recorder, RegionManager regions, SpeciesClassifier classifier) {
        this.recorder = recorder;
        this.regions = regions;
        this.classifier = classifier;
    }

    public void classifier(SpeciesClassifier classifier) {
        this.classifier = classifier;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingWorldConfig cfg = recorder.cfg();
        if (!cfg.tracking().hunting() || !cfg.wildlife().enabled()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || recorder.ignored(killer)) {
            return; // natural deaths are part of the abstract population model already
        }
        SpeciesGroup group = classifier.group(entity.getType());
        if (group == null) {
            return;
        }
        if (entity instanceof Tameable t && t.isTamed()) {
            return;
        }
        if (entity.customName() != null || !classifier.counted(entity.getEntitySpawnReason())) {
            return; // farm animals, pets and named mobs are not wildlife
        }
        Location loc = entity.getLocation();
        Accumulators acc = recorder.at(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (acc != null) {
            acc.kills[group.ordinal()] += 1;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        LivingWorldConfig cfg = recorder.cfg();
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !cfg.tracking().fishing()
                || !cfg.wildlife().enabled() || recorder.ignored(event.getPlayer())) {
            return;
        }
        Location loc = event.getHook().getLocation();
        Accumulators acc = recorder.at(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (acc != null) {
            acc.kills[SpeciesGroup.AQUATIC.ordinal()] += cfg.wildlife().fishCatchWeight();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        Player player = event.getPlayer();
        if (!recorder.cfg().tracking().trades() || recorder.ignored(player)) {
            return;
        }
        Location loc = event.getVillager().getLocation();
        Accumulators acc = recorder.at(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (acc != null) {
            acc.trades += 1;
            recorder.influence(player, acc, recorder.cfg().influence().perTrade());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        LivingWorldConfig.Wildlife wl = recorder.cfg().wildlife();
        if (!wl.enabled() || !wl.spawnSuppression()) {
            return;
        }
        SpeciesGroup group = classifier.group(event.getEntityType());
        if (group == null) {
            return;
        }
        Location loc = event.getLocation();
        if (!recorder.worldEnabled(loc.getWorld())) {
            return;
        }
        // Only regions already in memory are consulted; a spawn never causes a region load.
        RegionState state = regions.getLoaded(regions.keyAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ()));
        if (state == null) {
            return;
        }
        double pop = state.population(group);
        if (pop >= wl.suppressionBelow()) {
            return;
        }
        double chance = wl.maxSuppression() * (1 - pop / wl.suppressionBelow());
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            event.setCancelled(true);
        }
    }
}
