package me.fokend.livingworld.listener;

import java.util.Locale;
import me.fokend.livingworld.model.Accumulators;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Observes forestry, mining and construction. Purely passive: listens at MONITOR priority, ignores
 * cancelled events, never cancels anything and never modifies blocks.
 */
public final class ActivityListener implements Listener {

    private final ActivityRecorder recorder;
    private final PlacementMemory<Boolean> placedLogs;
    private final PlacementMemory<PlacementMemory.Planter> saplings;

    public ActivityListener(ActivityRecorder recorder, PlacementMemory<Boolean> placedLogs,
                            PlacementMemory<PlacementMemory.Planter> saplings) {
        this.recorder = recorder;
        this.placedLogs = placedLogs;
        this.saplings = saplings;
    }

    private static long pos(Block block) {
        return PlacementMemory.key(block.getWorld().getUID().hashCode(), block.getX(), block.getY(), block.getZ());
    }

    private static long pos(World world, int x, int y, int z) {
        return PlacementMemory.key(world.getUID().hashCode(), x, y, z);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!recorder.cfg().tracking().blocks()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (recorder.ignored(player) || !recorder.worldEnabled(block.getWorld())) {
            return;
        }
        Material type = block.getType();
        boolean log = Tag.LOGS.isTagged(type);
        boolean ore = !log && isOre(type);
        boolean sapling = !log && !ore && Tag.SAPLINGS.isTagged(type);
        if (!log && !ore && !sapling) {
            return;
        }
        Accumulators acc = recorder.at(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (acc == null) {
            return;
        }
        if (sapling) {
            saplings.remove(pos(block));
        } else if (log) {
            if (placedLogs.remove(pos(block)) != null) {
                return; // a player-placed log: this is (de)construction, not deforestation
            }
            acc.logsCut += 1;
        } else {
            acc.oresMined += 1;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!recorder.cfg().tracking().blocks()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        if (recorder.ignored(player) || !recorder.worldEnabled(block.getWorld())) {
            return;
        }
        Material type = block.getType();
        if (Tag.LOGS.isTagged(type)) {
            placedLogs.put(pos(block), Boolean.TRUE);
        } else if (Tag.SAPLINGS.isTagged(type)) {
            saplings.put(pos(block), new PlacementMemory.Planter(player.getUniqueId(), player.getName()));
        }
        if (!recorder.allowBuild(player)) {
            return;
        }
        Accumulators acc = recorder.at(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (acc != null) {
            acc.buildActions += 1;
            recorder.influence(player, acc, recorder.cfg().influence().perBuildAction());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        if (!recorder.cfg().tracking().blocks() || !isTree(event.getSpecies())) {
            return;
        }
        Location loc = event.getLocation();
        World world = loc.getWorld();
        if (!recorder.worldEnabled(world)) {
            return;
        }
        int logs = 0;
        for (BlockState state : event.getBlocks()) {
            if (Tag.LOGS.isTagged(state.getType())) {
                logs++;
            }
        }
        if (logs == 0) {
            return;
        }
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        Accumulators acc = recorder.at(world, x, y, z);
        if (acc == null) {
            return;
        }
        acc.treeLogsGrown += logs;
        acc.treesGrown += 1;

        PlacementMemory.Planter planter = saplings.remove(pos(world, x, y, z));
        Player bonemealer = event.getPlayer();
        if (bonemealer != null && !recorder.ignored(bonemealer)) {
            planter = new PlacementMemory.Planter(bonemealer.getUniqueId(), bonemealer.getName());
        }
        if (planter == null) {
            return; // natural growth: helps the forest, credits nobody
        }
        acc.addSteward(planter.playerId(), planter.name(), 1);
        double gain = recorder.cfg().influence().perTreeGrown();
        Player online = bonemealer != null ? bonemealer : org.bukkit.Bukkit.getPlayer(planter.playerId());
        if (online != null) {
            recorder.influence(online, acc, gain);
        } else if (recorder.cfg().influence().enabled()) {
            acc.addInfluence("player:" + planter.playerId(), planter.name(), gain);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player) || recorder.ignored(player) || !recorder.allowBuild(player)) {
            return;
        }
        Location loc = event.getEntity().getLocation();
        Accumulators acc = recorder.at(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (acc != null) {
            acc.buildActions += 1; // animal husbandry counts as settlement life
            recorder.influence(player, acc, recorder.cfg().influence().perBuildAction());
        }
    }

    private static boolean isOre(Material m) {
        return Tag.COAL_ORES.isTagged(m) || Tag.IRON_ORES.isTagged(m) || Tag.COPPER_ORES.isTagged(m)
                || Tag.GOLD_ORES.isTagged(m) || Tag.REDSTONE_ORES.isTagged(m) || Tag.LAPIS_ORES.isTagged(m)
                || Tag.DIAMOND_ORES.isTagged(m) || Tag.EMERALD_ORES.isTagged(m);
    }

    static boolean isTree(TreeType type) {
        if (type == null) {
            return false;
        }
        String n = type.name().toUpperCase(Locale.ROOT);
        return !n.contains("MUSHROOM") && !n.contains("CHORUS") && !n.contains("FUNGUS");
    }
}
