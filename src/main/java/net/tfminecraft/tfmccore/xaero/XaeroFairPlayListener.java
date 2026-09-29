package net.tfminecraft.tfmccore.xaero;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;
import net.tfminecraft.tfmccore.cache.Cache;

/**
 * Sends Xaero's Minimap fair-play code on every join. The client drops the flag
 * when it disconnects, so it must arrive once per session.
 *
 * <p>This replaces the autorun in Xaero's Map Server Utils datapack. That pack
 * tags each player on first join and only resends after the leave_game stat
 * rises, so an unclean shutdown that skips the save leaves players tagged with
 * no pending leave and their entity radar enabled.
 */
public final class XaeroFairPlayListener implements Listener {
    // Plain text with literal section signs: Xaero matches the raw string, so
    // this must not go through a legacy serialiser that turns it into styles.
    static final Component FAIR_PLAY = Component.text("§f§a§i§r§x§a§e§r§o");
    // Leave the client a second to finish joining before it reads chat.
    static final long JOIN_DELAY_TICKS = 20;

    private final Plugin plugin;

    public XaeroFairPlayListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> send(player), JOIN_DELAY_TICKS);
    }

    public static void send(Player player) {
        if (Cache.xaeroFairPlay && player.isOnline()) {
            player.sendMessage(FAIR_PLAY);
        }
    }
}
