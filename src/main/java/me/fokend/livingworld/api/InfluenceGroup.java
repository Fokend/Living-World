package me.fokend.livingworld.api;

/**
 * A group that can accumulate regional influence: a clan, a team, a town, or a single player.
 *
 * @param id          stable unique id, namespaced by the provider (e.g. {@code "clan:42"}, {@code "player:<uuid>"})
 * @param displayName plain-text name shown to players (never parsed as markup)
 */
public record InfluenceGroup(String id, String displayName) {
    public InfluenceGroup {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (id.length() > 64) {
            throw new IllegalArgumentException("id too long");
        }
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        if (displayName.length() > 48) {
            displayName = displayName.substring(0, 48);
        }
    }
}
