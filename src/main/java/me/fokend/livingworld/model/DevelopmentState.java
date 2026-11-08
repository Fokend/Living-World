package me.fokend.livingworld.model;

/** Development stage of a region. Order of constants is persisted by name, never by ordinal. */
public enum DevelopmentState {
    WILDERNESS,
    DEVELOPING,
    ACTIVE,
    COMMERCIAL_HUB,
    ABANDONED,
    OVEREXPLOITED;

    public boolean isSettlement() {
        return this == DEVELOPING || this == ACTIVE || this == COMMERCIAL_HUB;
    }

    public static DevelopmentState parse(String raw, DevelopmentState fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
