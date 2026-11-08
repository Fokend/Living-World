package me.fokend.livingworld.command;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Reference to a command sender that is safe to capture in asynchronous callbacks: players are kept by
 * UUID (so an offline player is not retained), anything else resolves to the console.
 */
public final class SenderRef {

    private final UUID playerId;

    private SenderRef(UUID playerId) {
        this.playerId = playerId;
    }

    public static SenderRef of(CommandSender sender) {
        return new SenderRef(sender instanceof Player p ? p.getUniqueId() : null);
    }

    /** The sender, or null if the player went offline meanwhile. */
    public CommandSender resolve() {
        return playerId == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(playerId);
    }
}
