package me.fokend.livingworld.task;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns every repeating task of the plugin. {@link #stopAll()} is called before tasks are (re)started and
 * on disable, so a reload can never leave a duplicate task running.
 */
public final class TaskManager {

    private final Plugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public TaskManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void repeat(String name, Runnable body, long delayTicks, long periodTicks) {
        Runnable guarded = () -> {
            try {
                body.run();
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, "Task '" + name + "' failed", ex);
            }
        };
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, guarded, Math.max(1, delayTicks),
                Math.max(1, periodTicks)));
    }

    public void stopAll() {
        for (BukkitTask t : tasks) {
            t.cancel();
        }
        tasks.clear();
    }

    public int activeCount() {
        int n = 0;
        for (BukkitTask t : tasks) {
            if (!t.isCancelled()) {
                n++;
            }
        }
        return n;
    }
}
