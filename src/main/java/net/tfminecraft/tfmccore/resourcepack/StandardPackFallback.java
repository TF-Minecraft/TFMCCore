package net.tfminecraft.tfmccore.resourcepack;

import dev.lone.itemsadder.api.ItemsAdder;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.resource.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Required single-pack delivery of ItemsAdder's self-hosted pack under a fresh request ID. */
final class StandardPackFallback implements AutoCloseable {
    static final Component PROMPT = Component.text("TFMC needs its resource pack to play.");
    static final long TIMEOUT_TICKS = 20L * 180;
    private record Stamp(long size, String modified, String cacheModified) {}
    private final class Pending {
        final Player player; final UUID id; final Consumer<Boolean> done; BukkitTask timeout;
        Pending(Player player, UUID id, Consumer<Boolean> done) { this.player = player; this.id = id; this.done = done; }
    }
    private final JavaPlugin plugin;
    private final Path source, cache;
    // Server thread only.
    private final Map<UUID, Pending> pending = new HashMap<>();
    private Stamp stamp;
    private String hash;
    private volatile boolean closed;

    StandardPackFallback(JavaPlugin plugin, Path itemsAdder) {
        this.plugin = plugin;
        source = itemsAdder.resolve("output/generated.zip");
        cache = itemsAdder.resolve("storage/cache/various/resourcepacks.yml");
    }

    /** Completes once on the server thread; replaces any earlier fallback for this player. */
    void send(Player player, Consumer<Boolean> done) {
        cancel(player.getUniqueId());
        if (closed || !player.isOnline()) { done.accept(false); return; }
        Pending request;
        try {
            String url = ItemsAdder.getPackUrl(true);
            if (url == null || url.isBlank()) throw new IllegalStateException("ItemsAdder has no pack URL");
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(UUID.randomUUID(), URI.create(url), hash());
            request = new Pending(player, info.id(), done);
            pending.put(player.getUniqueId(), request);
            player.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).replace(true).required(true)
                    .prompt(PROMPT).callback((id, status, audience) -> {
                        if (!closed && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> status(request, id, status));
                    }).build());
            request.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (finish(request)) {
                    request.player.removeResourcePacks(request.id);
                    plugin.getLogger().warning("Standard resource pack timed out for " + player.getName());
                    request.done.accept(false);
                }
            }, TIMEOUT_TICKS);
        } catch (IOException | RuntimeException | LinkageError error) {
            plugin.getLogger().warning("Standard resource pack unavailable for " + player.getName() + ": " + error.getMessage());
            Pending failed = pending.remove(player.getUniqueId());
            if (failed != null) player.removeResourcePacks(failed.id);
            done.accept(false);
            return;
        }
        plugin.getLogger().info("Sent the required standard resource pack to " + player.getName());
    }

    private void status(Pending request, UUID id, ResourcePackStatus status) {
        if (closed || !request.id.equals(id) || status.intermediate() || !finish(request)) return;
        boolean loaded = status == ResourcePackStatus.SUCCESSFULLY_LOADED;
        if (!loaded) plugin.getLogger().warning("Standard resource pack " + status + " for " + request.player.getName());
        request.done.accept(loaded);
    }

    /** Claims the request for its single terminal outcome. */
    private boolean finish(Pending request) {
        if (!pending.remove(request.player.getUniqueId(), request)) return false;
        if (request.timeout != null) request.timeout.cancel();
        return true;
    }

    /** Pins the IA cache hash to a source generation that did not change while it was read. */
    private String hash() throws IOException {
        Stamp before = stamp();
        if (before.equals(stamp)) return hash;
        String value = YamlConfiguration.loadConfiguration(cache.toFile()).getString("last_hash", "");
        if (!value.matches("[0-9a-f]{40}")) throw new IOException("ItemsAdder pack hash missing");
        if (!before.equals(stamp())) throw new IOException("ItemsAdder pack is rebuilding");
        stamp = before;
        hash = value;
        return value;
    }

    private Stamp stamp() throws IOException {
        var attributes = Files.readAttributes(source, BasicFileAttributes.class);
        return new Stamp(attributes.size(), attributes.lastModifiedTime().toString(), Files.getLastModifiedTime(cache).toString());
    }

    /** Drops a request without completing it; its owner has already moved on. */
    void cancel(UUID player) {
        Pending request = pending.get(player);
        if (request != null) finish(request);
    }

    /** Quit cleanup that never touches a newer connection under the same UUID. */
    void quit(Player player) {
        Pending request = pending.get(player.getUniqueId());
        if (request != null && request.player == player) finish(request);
    }

    @Override public void close() {
        closed = true;
        List.copyOf(pending.values()).forEach(this::finish);
    }
}
