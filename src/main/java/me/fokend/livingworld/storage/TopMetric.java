package me.fokend.livingworld.storage;

import java.util.Locale;

/** Ranking criteria for {@code /world top}. The SQL fragment is a constant, never user input. */
public enum TopMetric {
    DEVELOPED("development DESC"),
    STABLE("stability DESC"),
    DAMAGED("stability ASC"),
    TRADE("trade_ema DESC"),
    GREEN("total_trees_grown DESC");

    private final String orderBy;

    TopMetric(String orderBy) {
        this.orderBy = orderBy;
    }

    String orderBy() {
        return orderBy;
    }

    public static TopMetric parse(String raw) {
        if (raw == null) {
            return DEVELOPED;
        }
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
