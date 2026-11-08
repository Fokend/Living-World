package me.fokend.livingworld.task;

/**
 * Hands work back to the server main thread. Implementations must silently drop work once the plugin
 * is shutting down (instead of throwing {@code IllegalPluginAccessException}).
 */
@FunctionalInterface
public interface MainThread {
    void execute(Runnable task);
}
