package net.tfminecraft.tfmccore.resourcepack;

import dev.lone.itemsadder.api.ItemsAdder;
import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.resource.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns only the opt-in multipart delivery path; ItemsAdder remains the single-pack fallback. */
public final class MultipartPackService implements Listener, AutoCloseable {
    private record Stamp(long size, String modified, String cacheModified) {}
    private final JavaPlugin plugin;
    private final Path source, cache;
    private final URI publicUrl;
    private final PackPublisher publisher;
    private final PackHttpServer http;
    private final ScheduledExecutorService worker;
    private final ItemsAdderPackBridge bridge;
    // Accessed only on the server thread. Callback tasks carry request identity.
    private final Map<UUID, PackLoadTracker> requests = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private volatile PackPublisher.Bundle current;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicLong buildEpoch = new java.util.concurrent.atomic.AtomicLong();
    private Stamp observed, failedAttempt;
    private long retryAfter;
    private String lastError;

    public MultipartPackService(JavaPlugin plugin, Path itemsAdder, InetSocketAddress address, String url) throws IOException {
        this.plugin = plugin;
        UUID itemsAdderPackId = UUID.fromString(YamlConfiguration.loadConfiguration(itemsAdder.resolve("config.yml").toFile())
                .getString("resource-pack.uuid", ""));
        if (address.getPort() < 1) throw new IOException("Invalid multipart HTTP port");
        try {
            publicUrl = URI.create(url.endsWith("/") ? url : url + "/");
            if (!Set.of("http", "https").contains(publicUrl.getScheme()) || publicUrl.getHost() == null
                    || publicUrl.getQuery() != null || publicUrl.getFragment() != null || publicUrl.getUserInfo() != null) {
                throw new IllegalArgumentException("Expected public HTTP(S) base URL");
            }
        } catch (IllegalArgumentException error) { throw new IOException("Invalid multipart public URL", error); }
        source = itemsAdder.resolve("output/generated.zip");
        cache = itemsAdder.resolve("storage/cache/various/resourcepacks.yml");
        Path root = plugin.getDataFolder().toPath().resolve("resource-pack/parts-v1");
        publisher = new PackPublisher(root);
        http = new PackHttpServer(address, root);
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TFMC-pack-publisher"); thread.setDaemon(true); return thread;
        });
        try {
            bridge = new ItemsAdderPackBridge(plugin, this, itemsAdderPackId);
        } catch (RuntimeException | LinkageError error) {
            worker.shutdownNow();
            http.close();
            throw error;
        }
        worker.scheduleWithFixedDelay(this::refresh, 0, 2, TimeUnit.SECONDS);
    }

    // The compressed event is before atlas finalization. Never split its unfinished entries.
    @EventHandler(priority = EventPriority.MONITOR)
    public void rebuilding(ItemsAdderPackCompressedEvent event) { buildEpoch.incrementAndGet(); current = null; }

    private Stamp stamp() throws IOException {
        var attributes = Files.readAttributes(source, BasicFileAttributes.class);
        return new Stamp(attributes.size(), attributes.lastModifiedTime().toString(), Files.getLastModifiedTime(cache).toString());
    }

    private void refresh() {
        if (closed) return;
        Stamp before = null;
        try {
            long epoch = buildEpoch.get();
            before = stamp();
            if (before.equals(observed)) return;
            if (before.equals(failedAttempt) && System.nanoTime() < retryAfter) return;
            current = null;
            String expected = YamlConfiguration.loadConfiguration(cache.toFile()).getString("last_hash", "");
            PackPublisher.Bundle bundle = publisher.publish(source, expected);
            if (epoch != buildEpoch.get() || !before.equals(stamp())) return; // A concurrent /iazip won; retry its completed generation.
            if (closed) return;
            observed = before;
            current = bundle;
            lastError = null;
            failedAttempt = null;
            plugin.getLogger().info("Published three resource-pack parts for " + bundle.sourceHash());
        } catch (IOException | RuntimeException error) {
            current = null;
            failedAttempt = before;
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            String message = error.getMessage();
            if (!Objects.equals(message, lastError)) {
                plugin.getLogger().warning("Multipart pack unavailable; single-pack fallback remains active: " + message);
                lastError = message;
            }
        }
    }

    public void send(Player player) {
        if (closed || !player.isOnline()) return;
        if (requests.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("Your resource pack is already loading.")); return;
        }
        long now = System.nanoTime();
        Long previous = cooldown.get(player.getUniqueId());
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(15)) {
            player.sendMessage(Component.text("Please wait before requesting the resource pack again.")); return;
        }
        cooldown.put(player.getUniqueId(), now);
        PackPublisher.Bundle bundle = current;
        if (bundle == null) { fallback(player); return; }
        send(player, bundle, false, null, () -> fallback(player));
    }

    boolean loading(UUID player) { return requests.containsKey(player); }

    /** Automatic IA deliveries bypass the user-command cooldown, but wait for the exact source hash. */
    boolean sendPublished(Player player, String sourceHash, boolean required, Component prompt, Runnable fallback) {
        PackPublisher.Bundle bundle = current;
        if (closed || !player.isOnline() || loading(player.getUniqueId())
                || bundle == null || !bundle.sourceHash().equals(sourceHash)) return false;
        send(player, bundle, required, prompt, fallback);
        return true;
    }

    private void send(Player player, PackPublisher.Bundle bundle, boolean required, Component prompt, Runnable fallback) {
        List<ResourcePackInfo> packs = new ArrayList<>();
        for (String part : PackPartitioner.PARTS) {
            String hash = bundle.hashes().get(part);
            UUID id = UUID.nameUUIDFromBytes(("tfmc:" + part + ":" + hash).getBytes(StandardCharsets.UTF_8));
            packs.add(ResourcePackInfo.resourcePackInfo(id, publicUrl.resolve(bundle.sourceHash() + "/" + part + ".zip"), hash));
        }
        PackLoadTracker tracker = new PackLoadTracker(new HashSet<>(packs.stream().map(ResourcePackInfo::id).toList()));
        requests.put(player.getUniqueId(), tracker);
        try {
            player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                    .packs(packs).replace(true).required(required).prompt(prompt)
                    .callback((id, status, audience) -> {
                        if (!closed && plugin.isEnabled()) {
                            Bukkit.getScheduler().runTask(plugin, () -> status(player, tracker, id, status, fallback));
                        }
                    }).build());
            plugin.getLogger().info("Sent three resource-pack parts to " + player.getName() + " (" + bundle.sourceHash() + ")");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (requests.remove(player.getUniqueId(), tracker) && player.isOnline()) {
                    player.removeResourcePacks(tracker.ids());
                    player.sendMessage(Component.text("Resource-pack loading timed out. Trying the standard pack."));
                    fallback.run();
                }
            }, 20L * 180);
        } catch (RuntimeException error) {
            requests.remove(player.getUniqueId(), tracker);
            player.removeResourcePacks(tracker.ids());
            plugin.getLogger().warning("Multipart request failed: " + error.getMessage());
            fallback.run();
        }
    }

    private void status(Player player, PackLoadTracker tracker, UUID id, ResourcePackStatus status, Runnable fallback) {
        if (closed || requests.get(player.getUniqueId()) != tracker || !player.isOnline()) return;
        boolean success = status == ResourcePackStatus.SUCCESSFULLY_LOADED;
        PackLoadTracker.Result result = tracker.accept(id, success, !status.intermediate() && !success);
        if (result == PackLoadTracker.Result.COMPLETE) {
            requests.remove(player.getUniqueId(), tracker);
            plugin.getLogger().info("All three resource-pack parts loaded for " + player.getName());
            player.sendMessage(Component.text("TFMC resource pack loaded."));
        } else if (result == PackLoadTracker.Result.FAILED) {
            requests.remove(player.getUniqueId(), tracker);
            player.removeResourcePacks(tracker.ids());
            plugin.getLogger().warning("Multipart pack " + status + " for " + player.getName());
            // Respect an explicit decline; do not immediately prompt again.
            if (status != ResourcePackStatus.DECLINED && status != ResourcePackStatus.DISCARDED) {
                player.sendMessage(Component.text("Could not load the split pack. Trying the standard pack."));
                fallback.run();
            }
        }
    }

    private void fallback(Player player) {
        requests.remove(player.getUniqueId());
        bridge.passThrough(() -> ItemsAdder.applyResourcepack(player));
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        bridge.quit(event.getPlayer().getUniqueId());
        requests.remove(event.getPlayer().getUniqueId()); cooldown.remove(event.getPlayer().getUniqueId());
    }

    @Override public void close() {
        closed = true; current = null;
        bridge.close();
        worker.shutdownNow(); http.close();
        HandlerList.unregisterAll(this);
        requests.clear(); cooldown.clear();
    }
}
