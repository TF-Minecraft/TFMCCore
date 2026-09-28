package net.tfminecraft.tfmccore.tfmc;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Content for the /tfmc player commands (messages, commands, item lists), read from tfmc.yml.
 * Command behaviour lives in {@link TfmcCommand}; this only exposes values with defaults.
 */
public final class TfmcConfig {

    /** Permissions for one LuckPerms track's promote and demote steps. */
    public record TrackSteps(String promotePermission, String demotePermission) {}

    private static final Logger LOGGER = Logger.getLogger("TFMCCore");

    private volatile YamlConfiguration config = new YamlConfiguration();

    public boolean load(File file) {
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (Exception e) {
            LOGGER.warning("Failed to load " + file.getName() + ": " + e.getMessage());
            return false;
        }
        config = loaded;
        return true;
    }

    public void loadFromString(String yaml) throws InvalidConfigurationException {
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.loadFromString(yaml);
        config = loaded;
    }

    public String string(String path) {
        return string(path, "");
    }

    public String string(String path, String fallback) {
        String value = config.getString(path);
        return value == null || value.isBlank() ? fallback : value;
    }

    public boolean enabled(String section) {
        return config.getBoolean(section + ".enabled", false);
    }

    public double decimal(String path, double fallback) {
        return config.getDouble(path, fallback);
    }

    public long seconds(String path) {
        return Math.max(0L, config.getLong(path, 0L));
    }

    /** A value that may be written as one string or as a list of strings. */
    public List<String> lines(String path) {
        if (config.isList(path)) {
            return config.getStringList(path);
        }
        String value = config.getString(path);
        return value == null ? List.of() : List.of(value);
    }

    /** Keys and string values of a section, in file order. */
    public Map<String, String> entries(String path) {
        Map<String, String> entries = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                entries.put(key, section.getString(key, ""));
            }
        }
        return entries;
    }

    public List<String> keys(String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section == null ? List.of() : new ArrayList<>(section.getKeys(false));
    }

    public Map<String, TrackSteps> tracks() {
        Map<String, TrackSteps> tracks = new LinkedHashMap<>();
        for (String track : keys("helper-tracks")) {
            String base = "helper-tracks." + track + ".";
            tracks.put(track, new TrackSteps(
                    config.getString(base + "promote-permission", track + ".promote"),
                    config.getString(base + "demote-permission", track + ".demote")));
        }
        return tracks;
    }
}
