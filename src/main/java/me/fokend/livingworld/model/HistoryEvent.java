package me.fokend.livingworld.model;

/**
 * Immutable chronicle entry.
 *
 * @param id        database id, or 0 for events that are not yet persisted
 * @param timestamp wall-clock epoch millis when the event happened
 * @param type      event type
 * @param region    affected region
 * @param value     the metric associated with the event (forest density, development score, ...)
 * @param subject   optional subject (species group, new state, group display name); may be empty
 * @param actors    optional comma separated display names of participants; may be empty
 */
public record HistoryEvent(long id, long timestamp, HistoryEventType type, RegionKey region,
                           double value, String subject, String actors) {

    public HistoryEvent {
        subject = subject == null ? "" : subject;
        actors = actors == null ? "" : actors;
    }

    public static HistoryEvent of(long timestamp, HistoryEventType type, RegionKey region, double value,
                                  String subject, String actors) {
        return new HistoryEvent(0L, timestamp, type, region, value, subject, actors);
    }
}
