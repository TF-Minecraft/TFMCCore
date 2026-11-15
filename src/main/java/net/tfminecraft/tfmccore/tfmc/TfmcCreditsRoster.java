package net.tfminecraft.tfmccore.tfmc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Everyone /tfmc credits has listed, with the highest section they reached, so staff and helpers
 * who later step down stay in the credits. Kept in credits-roster.yml; a null file keeps it in memory.
 */
public final class TfmcCreditsRoster {

    /** A remembered player: the section key they reached and their name as last spelled. */
    public record Entry(String section, String name) {}

    private static final Logger LOGGER = Logger.getLogger("TFMCCore");

    private final File file;
    private final Map<String, Entry> players = new LinkedHashMap<>();
    /** The file could not be read: it is left as it is and never overwritten until fixed and reloaded. */
    private boolean readOnly;

    public TfmcCreditsRoster(File file) {
        this.file = file;
        if (file != null && file.isFile()) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException e) {
                readOnly = true;
                LOGGER.warning("Could not read " + file.getName() + " (" + e.getMessage()
                        + "); it is left untouched and not saved until it is fixed.");
                return;
            }
            ConfigurationSection saved = yaml.getConfigurationSection("players");
            if (saved != null) {
                for (String key : saved.getKeys(false)) {
                    String section = saved.getString(key + ".section", "");
                    if (!section.isBlank()) {
                        players.put(key.toLowerCase(Locale.ROOT), new Entry(section, saved.getString(key + ".name", key)));
                    }
                }
            }
        }
    }

    /** Everyone remembered, in the order they joined their section. */
    public Map<String, Entry> players() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(players));
    }

    /**
     * Remembers the player in the section if it ranks higher than the one they reached before
     * (lower {@code rank} is higher), and keeps the best spelling of their name. True when changed.
     */
    public boolean record(String name, String section, Map<String, Integer> ranks) {
        String key = name.toLowerCase(Locale.ROOT);
        Entry old = players.get(key);
        String keptSection = old == null || ranks.getOrDefault(section, Integer.MAX_VALUE)
                < ranks.getOrDefault(old.section(), Integer.MAX_VALUE) ? section : old.section();
        String keptName = old == null || !name.equals(name.toLowerCase(Locale.ROOT)) ? name : old.name();
        Entry entry = new Entry(keptSection, keptName);
        if (entry.equals(old)) {
            return false;
        }
        if (old != null && !old.section().equals(keptSection)) {
            players.remove(key);
        }
        players.put(key, entry);
        return true;
    }

    public void save() {
        if (file == null || readOnly) {
            return;
        }
        YamlConfiguration out = new YamlConfiguration();
        out.options().setHeader(java.util.List.of(
                "Everyone /tfmc credits has listed and the highest section they reached, so former staff",
                "and helpers stay in the credits. To drop someone, add them to credits.exclude in tfmc.yml."));
        players.forEach((key, entry) -> {
            out.set("players." + key + ".section", entry.section());
            out.set("players." + key + ".name", entry.name());
        });
        // Written beside it and moved into place, so a crash mid-write never leaves half a roster.
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            out.save(temp);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warning("Failed to save " + file.getName() + ": " + e.getMessage());
        }
    }
}
