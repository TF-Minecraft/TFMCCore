package net.tfminecraft.tfmccore.resourcepack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Warns staff when the ItemsAdder pack holds a texture larger than the GPU limit.
 * Most Intel graphics only take 16384 px textures; one larger texture stops the whole
 * pack loading for those players (FAILED_RELOAD, then a client crash).
 */
public final class PackTextureGuard implements Listener, AutoCloseable {
    public record Oversized(String path, int width, int height) {}
    private record Stamp(long size, long modified) {}
    static final String PERMISSION = "tfmccore.admin";
    private static final long CHECK_TICKS = 20L * 30;
    private final JavaPlugin plugin;
    private final Path pack;
    private final int limit;
    private final BukkitTask task;
    private Stamp scanned;
    private volatile List<Oversized> found = List.of();

    public PackTextureGuard(JavaPlugin plugin, Path pack, int limit) {
        this.plugin = plugin;
        this.pack = pack;
        this.limit = limit;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L, CHECK_TICKS);
    }

    /** Scans the pack once per build; a pack still being written is retried on the next check. */
    synchronized void check() {
        Stamp stamp;
        List<Oversized> oversized;
        try {
            var attributes = Files.readAttributes(pack, BasicFileAttributes.class);
            stamp = new Stamp(attributes.size(), attributes.lastModifiedTime().toMillis());
            if (stamp.equals(scanned)) return;
            oversized = scan(pack, limit);
        } catch (IOException | RuntimeException error) {
            return;
        }
        scanned = stamp;
        found = oversized;
        if (oversized.isEmpty()) return;
        plugin.getLogger().severe(summary(oversized) + " Players whose GPU allows at most " + limit
                + " px (most Intel graphics) cannot load the pack: " + list(oversized));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) warn(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { warn(event.getPlayer()); }

    private void warn(Player player) {
        List<Oversized> current = found;
        if (current.isEmpty() || !player.hasPermission(PERMISSION)) return;
        player.sendMessage(Component.text("[TFMC] " + summary(current)
                + " Players on Intel graphics can't load it. See the console.", NamedTextColor.RED));
    }

    private String summary(List<Oversized> oversized) {
        Oversized first = oversized.getFirst();
        return "The resource pack has " + oversized.size() + " texture" + (oversized.size() == 1 ? "" : "s")
                + " over " + limit + " px (e.g. " + first.path() + ", " + first.width() + "x" + first.height() + ").";
    }

    private static String list(List<Oversized> oversized) {
        StringJoiner joiner = new StringJoiner(", ");
        oversized.forEach(texture -> joiner.add(texture.path() + " " + texture.width() + "x" + texture.height()));
        return joiner.toString();
    }

    /** PNG entries wider or taller than the limit, read from each image header. */
    public static List<Oversized> scan(Path pack, int limit) throws IOException {
        List<Oversized> oversized = new ArrayList<>();
        // Java's ZipFile reads ItemsAdder's protected entries by their central-directory names.
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory() || !entry.getName().endsWith(".png")) continue;
                byte[] header;
                try (InputStream stream = zip.getInputStream(entry)) { header = stream.readNBytes(24); }
                ByteBuffer buffer = ByteBuffer.wrap(header);
                if (header.length < 24 || buffer.getLong(0) != 0x89504E470D0A1A0AL || buffer.getInt(12) != 0x49484452) continue;
                int width = buffer.getInt(16), height = buffer.getInt(20);
                if (Integer.toUnsignedLong(width) > limit || Integer.toUnsignedLong(height) > limit) {
                    oversized.add(new Oversized(entry.getName(), width, height));
                }
            }
        }
        oversized.sort(Comparator.comparing(Oversized::path));
        return List.copyOf(oversized);
    }

    List<Oversized> found() { return found; }

    @Override public void close() { if (task != null) task.cancel(); }
}
