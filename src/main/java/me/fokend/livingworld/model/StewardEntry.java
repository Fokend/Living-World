package me.fokend.livingworld.model;

import java.util.UUID;

/** Positive contribution of a single player to a region's nature (trees grown from their saplings). */
public final class StewardEntry {

    private final UUID playerId;
    private String playerName;
    private int treesGrown;
    private int lastMilestone;

    public StewardEntry(UUID playerId, String playerName, int treesGrown, int lastMilestone) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.treesGrown = treesGrown;
        this.lastMilestone = lastMilestone;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public void playerName(String name) {
        if (name != null && !name.isEmpty()) {
            this.playerName = name;
        }
    }

    public int treesGrown() {
        return treesGrown;
    }

    public void addTrees(int amount) {
        this.treesGrown += amount;
    }

    public int lastMilestone() {
        return lastMilestone;
    }

    public void lastMilestone(int milestone) {
        this.lastMilestone = milestone;
    }
}
