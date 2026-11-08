package me.fokend.livingworld.sim;

import java.util.ArrayList;
import java.util.List;
import me.fokend.livingworld.model.DevelopmentState;
import me.fokend.livingworld.model.HistoryEvent;
import me.fokend.livingworld.model.RegionKey;

/** Reusable collector for the results of simulation steps. Cleared by the caller after consumption. */
public final class StepOutput {

    public record Transition(RegionKey region, DevelopmentState from, DevelopmentState to) {
    }

    private final List<HistoryEvent> events = new ArrayList<>();
    private final List<Transition> transitions = new ArrayList<>();

    public void event(HistoryEvent event) {
        events.add(event);
    }

    public void transition(RegionKey key, DevelopmentState from, DevelopmentState to) {
        transitions.add(new Transition(key, from, to));
    }

    public List<HistoryEvent> events() {
        return events;
    }

    public List<Transition> transitions() {
        return transitions;
    }

    public boolean isEmpty() {
        return events.isEmpty() && transitions.isEmpty();
    }

    public void clear() {
        events.clear();
        transitions.clear();
    }
}
