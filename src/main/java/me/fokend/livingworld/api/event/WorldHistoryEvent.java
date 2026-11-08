package me.fokend.livingworld.api.event;

import me.fokend.livingworld.model.HistoryEvent;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the main thread when a new entry is written into the world chronicle. */
public final class WorldHistoryEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HistoryEvent entry;

    public WorldHistoryEvent(HistoryEvent entry) {
        this.entry = entry;
    }

    public HistoryEvent entry() {
        return entry;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
