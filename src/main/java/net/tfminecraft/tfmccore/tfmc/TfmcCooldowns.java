package net.tfminecraft.tfmccore.tfmc;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Per-player cooldowns for /tfmc subcommands, keyed by subcommand name.
 * Keys listed as persistent survive restarts in their own file; the rest are kept in memory.
 */
public final class TfmcCooldowns {

    private static final Logger LOGGER = Logger.getLogger("TFMCCore");

    private final Map<String, Map<UUID, Long>> lastUse = new ConcurrentHashMap<>();
    private final File file;
    private final Set<String> persistent;
    private final LongSupplier clock;

    public TfmcCooldowns(File file, Set<String> persistent, LongSupplier clock) {
        this.file = file;
        this.persistent = Set.copyOf(persistent);
        this.clock = clock;
    }

    /** Milliseconds left before {@code key} may be used again, or 0 when it is ready. */
    public long remaining(String key, UUID player, long cooldownMillis) {
        Long used = lastUse.getOrDefault(key, Map.of()).get(player);
        if (used == null || cooldownMillis <= 0) {
            return 0L;
        }
        return Math.max(0L, used + cooldownMillis - clock.getAsLong());
    }

    public void markUsed(String key, UUID player) {
        lastUse.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(player, clock.getAsLong());
        if (persistent.contains(key)) {
            save();
        }
    }

    public boolean exists() {
        return file != null && file.exists();
    }

    public void load() {
        if (!exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            for (String id : section.getKeys(false)) {
                try {
                    lastUse.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                            .put(UUID.fromString(id), section.getLong(id));
                } catch (IllegalArgumentException ignored) {
                    // skip entries that are not player UUIDs
                }
            }
        }
    }

    /**
     * Copies last-use times for a ConditionalEvents event (plugins/ConditionalEvents/players/UUID.yml,
     * events.EVENT.cooldown holds the epoch millis of the last run) into {@code key}.
     */
    public int importConditionalEvents(File playersDir, String event, String key) {
        File[] files = playersDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return 0;
        }
        int imported = 0;
        for (File player : files) {
            UUID id;
            try {
                id = UUID.fromString(player.getName().substring(0, player.getName().length() - 4));
            } catch (IllegalArgumentException e) {
                continue;
            }
            long used = YamlConfiguration.loadConfiguration(player).getLong("events." + event + ".cooldown", 0L);
            if (used > 0) {
                lastUse.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).merge(id, used, Math::max);
                imported++;
            }
        }
        return imported;
    }

    public synchronized void save() {
        if (file == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (String key : persistent) {
            lastUse.getOrDefault(key, Map.of()).forEach((id, used) -> yaml.set(key + "." + id, used));
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            LOGGER.warning("Failed to save " + file.getName() + ": " + e.getMessage());
        }
    }

    /** Formats a duration the way ConditionalEvents did, e.g. "2d 5h 3m 1s". */
    public static String format(long millis) {
        long total = Math.max(1L, (millis + 999L) / 1000L);
        long days = total / 86_400L;
        long hours = total % 86_400L / 3_600L;
        long minutes = total % 3_600L / 60L;
        long seconds = total % 60L;
        StringBuilder out = new StringBuilder();
        append(out, days, "d");
        append(out, hours, "h");
        append(out, minutes, "m");
        append(out, seconds, "s");
        return out.toString();
    }

    private static void append(StringBuilder out, long value, String unit) {
        if (value > 0) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(value).append(unit);
        }
    }
}
