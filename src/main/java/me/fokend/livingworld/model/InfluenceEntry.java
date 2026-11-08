package me.fokend.livingworld.model;

/** Mutable influence score of one group (clan/team or a single player) in a region. Main thread only. */
public final class InfluenceEntry {

    private final String groupId;
    private String displayName;
    private double score;

    public InfluenceEntry(String groupId, String displayName, double score) {
        this.groupId = groupId;
        this.displayName = displayName;
        this.score = score;
    }

    public String groupId() {
        return groupId;
    }

    public String displayName() {
        return displayName;
    }

    public void displayName(String displayName) {
        if (displayName != null && !displayName.isEmpty()) {
            this.displayName = displayName;
        }
    }

    public double score() {
        return score;
    }

    public void score(double score) {
        this.score = score;
    }
}
