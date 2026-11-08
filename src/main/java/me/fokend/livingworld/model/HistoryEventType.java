package me.fokend.livingworld.model;

/** Kinds of chronicle entries. Persisted by name. */
public enum HistoryEventType {
    SETTLEMENT_FOUNDED(Significance.NOTABLE),
    SETTLEMENT_GREW(Significance.NOTABLE),
    SETTLEMENT_DECLINED(Significance.MINOR),
    BECAME_COMMERCIAL_HUB(Significance.MAJOR),
    HUB_STATUS_LOST(Significance.NOTABLE),
    SETTLEMENT_ABANDONED(Significance.NOTABLE),
    SETTLEMENT_REVIVED(Significance.MAJOR),
    RECLAIMED_BY_NATURE(Significance.NOTABLE),
    REGION_OVEREXPLOITED(Significance.MAJOR),
    ECOLOGY_RESTORED(Significance.MAJOR),
    DEFORESTATION(Significance.NOTABLE),
    MASS_DEFORESTATION(Significance.MAJOR),
    FOREST_RESTORED(Significance.MAJOR),
    WILDLIFE_DECLINE(Significance.NOTABLE),
    WILDLIFE_RECOVERED(Significance.NOTABLE),
    INFLUENCE_SHIFT(Significance.NOTABLE),
    STEWARD_MILESTONE(Significance.NOTABLE),
    REGION_NAMED(Significance.NOTABLE);

    public enum Significance { MINOR, NOTABLE, MAJOR }

    private final Significance significance;

    HistoryEventType(Significance significance) {
        this.significance = significance;
    }

    public Significance significance() {
        return significance;
    }

    public static HistoryEventType parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
