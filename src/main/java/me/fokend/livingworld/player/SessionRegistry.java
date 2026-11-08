package me.fokend.livingworld.player;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Sessions of online players. Bounded by the number of online players: a session is created on join,
 * removed on quit, and stale entries (players that left without a quit event reaching us, e.g. during a
 * reload) are swept by the maintenance task. Player preferences live in the player's own
 * PersistentDataContainer, so nothing is retained after the player leaves.
 */
public final class SessionRegistry {

    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    private final NamespacedKey hudKey;
    private final NamespacedKey notifyKey;

    public SessionRegistry(Plugin plugin) {
        this.hudKey = new NamespacedKey(plugin, "hud");
        this.notifyKey = new NamespacedKey(plugin, "notify");
    }

    public PlayerSession open(Player player, boolean hudDefault, boolean notifyDefault) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Byte hud = pdc.get(hudKey, PersistentDataType.BYTE);
        Byte notify = pdc.get(notifyKey, PersistentDataType.BYTE);
        PlayerSession s = new PlayerSession(player.getUniqueId(),
                hud == null ? hudDefault : hud != 0, notify == null ? notifyDefault : notify != 0);
        PlayerSession previous = sessions.put(player.getUniqueId(), s);
        if (previous != null && previous.bossBar() != null) {
            player.hideBossBar(previous.bossBar());
        }
        return s;
    }

    public PlayerSession get(UUID id) {
        return sessions.get(id);
    }

    public PlayerSession getOrOpen(Player player, boolean hudDefault, boolean notifyDefault) {
        PlayerSession s = sessions.get(player.getUniqueId());
        return s != null ? s : open(player, hudDefault, notifyDefault);
    }

    public PlayerSession close(UUID id) {
        return sessions.remove(id);
    }

    public void savePreferences(Player player, PlayerSession s) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(hudKey, PersistentDataType.BYTE, (byte) (s.hud() ? 1 : 0));
        pdc.set(notifyKey, PersistentDataType.BYTE, (byte) (s.notifyOnEnter() ? 1 : 0));
    }

    public Collection<PlayerSession> all() {
        return sessions.values();
    }

    public int size() {
        return sessions.size();
    }

    public void clear() {
        sessions.clear();
    }
}
