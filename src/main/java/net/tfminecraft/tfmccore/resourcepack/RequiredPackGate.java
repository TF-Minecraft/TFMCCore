package net.tfminecraft.tfmccore.resourcepack;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Publishes each joining player's required-pack state as Bukkit metadata: "loading" from
 * join, then "ready" or "failed" once this connection's attempt finishes. Other plugins
 * hold the player while it reads "loading".
 */
public final class RequiredPackGate implements Listener, AutoCloseable {
    public static final String METADATA = "tfmccore:required-pack";
    public enum State { LOADING, READY, FAILED }
    // One per connection and try; a callback only counts while its attempt is current.
    private static final class Attempt {
        final Player player; State state = State.LOADING;
        Attempt(Player player) { this.player = player; }
    }
    private final JavaPlugin plugin;
    private final BiConsumer<Player, Consumer<Boolean>> delivery;
    private final StandardPackFallback standard;
    // Server thread only.
    private final Map<UUID, Attempt> attempts = new HashMap<>();
    private boolean closed;

    RequiredPackGate(JavaPlugin plugin, BiConsumer<Player, Consumer<Boolean>> delivery, StandardPackFallback standard) {
        this.plugin = plugin;
        this.delivery = delivery;
        this.standard = standard;
    }

    /** Multipart when enabled, else ItemsAdder's pack, else every attempt fails. */
    public static RequiredPackGate create(JavaPlugin plugin, MultipartPackService multipart, Path itemsAdder) {
        if (multipart != null) return new RequiredPackGate(plugin, multipart::sendRequired, null);
        if (itemsAdder != null) {
            StandardPackFallback standard = new StandardPackFallback(plugin, itemsAdder);
            return new RequiredPackGate(plugin, standard::send, standard);
        }
        plugin.getLogger().warning("Required resource pack enabled without ItemsAdder; joining players will fail the pack check");
        return new RequiredPackGate(plugin, (player, done) -> done.accept(false), null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) {
        // Metadata outlives the connection by UUID; always overwrite it before anything is sent.
        start(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void afterJoin(PlayerJoinEvent event) {
        Attempt attempt = attempts.get(event.getPlayer().getUniqueId());
        if (attempt == null || attempt.player != event.getPlayer()) return;
        // Queue behind room entry/loading dialogs scheduled by earlier join handlers.
        // Minecraft's pack confirmation must be the last screen sent to the client.
        plugin.getServer().getScheduler().runTask(plugin, () -> deliver(attempt));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Attempt attempt = attempts.get(player.getUniqueId());
        if (attempt == null || attempt.player != player) return;
        attempts.remove(player.getUniqueId());
        player.removeMetadata(METADATA, plugin);
        if (standard != null) standard.quit(player);
    }

    /** True while this player's required pack, fallback included, is still loading. */
    public boolean loading(UUID player) {
        Attempt attempt = attempts.get(player);
        return attempt != null && attempt.state == State.LOADING;
    }

    /** Starts a fresh attempt only after this connection's last attempt failed. */
    public boolean retry(Player player) {
        Attempt attempt = attempts.get(player.getUniqueId());
        if (closed || attempt == null || attempt.player != player || attempt.state != State.FAILED || !player.isOnline()) return false;
        deliver(start(player));
        return true;
    }

    private Attempt start(Player player) {
        Attempt attempt = new Attempt(player);
        attempts.put(player.getUniqueId(), attempt);
        mark(player, State.LOADING);
        return attempt;
    }

    private void deliver(Attempt attempt) {
        if (!current(attempt) || attempt.state != State.LOADING) return;
        try {
            delivery.accept(attempt.player, loaded -> finish(attempt, loaded));
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().warning("Required resource pack failed for " + attempt.player.getName() + ": " + error.getMessage());
            finish(attempt, false);
        }
    }

    private void finish(Attempt attempt, boolean loaded) {
        if (!current(attempt) || attempt.state != State.LOADING) return;
        attempt.state = loaded ? State.READY : State.FAILED;
        mark(attempt.player, attempt.state);
        plugin.getLogger().info("Required resource pack " + attempt.state.name().toLowerCase(Locale.ROOT)
                + " for " + attempt.player.getName());
    }

    private boolean current(Attempt attempt) {
        UUID id = attempt.player.getUniqueId();
        return !closed && attempts.get(id) == attempt && plugin.getServer().getPlayer(id) == attempt.player;
    }

    private void mark(Player player, State state) {
        player.setMetadata(METADATA, new FixedMetadataValue(plugin, state.name().toLowerCase(Locale.ROOT)));
    }

    @Override public void close() {
        closed = true;
        attempts.values().forEach(attempt -> attempt.player.removeMetadata(METADATA, plugin));
        attempts.clear();
        if (standard != null) standard.close();
        HandlerList.unregisterAll(this);
    }
}
