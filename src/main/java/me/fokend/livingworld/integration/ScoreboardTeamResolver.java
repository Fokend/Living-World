package me.fokend.livingworld.integration;

import me.fokend.livingworld.api.InfluenceGroup;
import me.fokend.livingworld.api.InfluenceGroupResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

/**
 * Optional adapter (disabled by default): treats vanilla main-scoreboard teams as influence groups.
 * This is a generic fallback, not an assumption about how clans work on any particular server.
 */
public final class ScoreboardTeamResolver implements InfluenceGroupResolver {

    @Override
    public InfluenceGroup resolve(Player player) {
        var manager = Bukkit.getScoreboardManager();
        Team team = manager.getMainScoreboard().getEntryTeam(player.getName());
        if (team == null) {
            return null;
        }
        String display = PlainTextComponentSerializer.plainText().serialize(team.displayName());
        return new InfluenceGroup("team:" + team.getName(), display.isBlank() ? team.getName() : display);
    }
}
