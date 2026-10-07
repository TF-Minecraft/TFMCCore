package net.tfminecraft.tfmccore.resourcepack;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.BukkitConverters;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Replaces ItemsAdder's outgoing pack with the matching published generation. */
final class ItemsAdderPackBridge implements AutoCloseable {
    private final JavaPlugin plugin;
    private final PacketAdapter listener;
    private final BukkitTask poll;
    private final PackDeliveryQueue queue;
    private final ThreadLocal<Boolean> bypass = ThreadLocal.withInitial(() -> false);
    private volatile boolean closed;

    ItemsAdderPackBridge(JavaPlugin plugin, MultipartPackService service, UUID packId) {
        this.plugin = plugin;
        this.queue = new PackDeliveryQueue(service, System::nanoTime);
        listener = new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.ADD_RESOURCE_PACK) {
            @Override public void onPacketSending(PacketEvent event) {
                if (closed || event.isCancelled() || event.isPlayerTemporary() || bypass.get()) return;
                PacketContainer packet = event.getPacket();
                if (!packId.equals(packet.getUUIDs().read(0))) return;
                String hash = packet.getStrings().read(1);
                if (hash == null || !hash.matches("[0-9a-f]{40}")) return;
                // Decode before cancellation: unsupported packet layouts retain IA delivery.
                try {
                    Component prompt = packet.getOptionals(BukkitConverters.getWrappedChatComponentConverter())
                            .read(0).map(chat -> GsonComponentSerializer.gson().deserialize(chat.getJson())).orElse(null);
                    Player player = event.getPlayer();
                    PacketContainer original = packet.deepClone();
                    boolean required = packet.getBooleans().read(0);
                    Runnable fallback = () -> original(player, original);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (closed) { fallback.run(); return; }
                        if (player.isOnline()) queue.add(player, hash, required, prompt, fallback);
                    });
                    event.setCancelled(true);
                } catch (RuntimeException error) {
                    plugin.getLogger().warning("Cannot replace ItemsAdder pack; retaining original delivery: " + error.getMessage());
                }
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(listener);
        try {
            poll = plugin.getServer().getScheduler().runTaskTimer(plugin, queue::drain, 1, 10);
        } catch (RuntimeException | LinkageError error) {
            ProtocolLibrary.getProtocolManager().removePacketListener(listener);
            throw error;
        }
    }

    private void original(Player player, PacketContainer packet) {
        if (player.isOnline()) {
            // This packet already passed normal listeners before we retained it.
            // Bypass packet listeners when replaying, avoiding another interception.
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet, false);
        }
    }

    /** ItemsAdder 4.0.18 self-host sends synchronously; fallback must not reenter the bridge. */
    void passThrough(Runnable action) {
        boolean previous = bypass.get();
        bypass.set(true);
        try { action.run(); } finally { bypass.set(previous); }
    }

    void quit(UUID player) { queue.quit(player); }

    @Override public void close() {
        closed = true;
        ProtocolLibrary.getProtocolManager().removePacketListener(listener);
        poll.cancel();
        queue.close();
        bypass.remove();
    }
}
