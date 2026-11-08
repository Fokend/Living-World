package me.fokend.livingworld.api.event;

import me.fokend.livingworld.api.RegionView;
import me.fokend.livingworld.model.DevelopmentState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the main thread after a region changed its development state. Informational only. */
public final class RegionStateChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final RegionView region;
    private final DevelopmentState from;
    private final DevelopmentState to;

    public RegionStateChangeEvent(RegionView region, DevelopmentState from, DevelopmentState to) {
        this.region = region;
        this.from = from;
        this.to = to;
    }

    public RegionView region() {
        return region;
    }

    public DevelopmentState from() {
        return from;
    }

    public DevelopmentState to() {
        return to;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
