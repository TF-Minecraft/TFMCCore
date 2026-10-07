package net.tfminecraft.tfmccore.stats.categories.rpcharacters;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmccore.stats.StatLabelFormatter;

public final class RpCharactersStatConfig {
    private static final String CLASS_PREFIX = "class_";
    private static final String RACE_PREFIX = "race_";

    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, String> classLabels = new HashMap<>();
    private final Map<String, String> raceLabels = new HashMap<>();

    public void load(File configFile) {
        loadChecked(configFile);
    }

    public boolean loadChecked(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
            return false;
        }

        Map<String, String> labels = new HashMap<>();
        Map<String, String> classLabels = new HashMap<>();
        Map<String, String> raceLabels = new HashMap<>();

        if (config.isConfigurationSection("labels")) {
            for (String statKey : config.getConfigurationSection("labels").getKeys(false)) {
                String label = config.getString("labels." + statKey);
                if (label != null && !label.isBlank()) {
                    labels.put(statKey, label);
                }
            }
        }

        if (config.isConfigurationSection("class_labels")) {
            for (String classId : config.getConfigurationSection("class_labels").getKeys(false)) {
                String label = config.getString("class_labels." + classId);
                if (label != null && !label.isBlank()) {
                    classLabels.put(classId.toLowerCase(Locale.ROOT), label);
                }
            }
        }

        if (config.isConfigurationSection("race_labels")) {
            for (String raceId : config.getConfigurationSection("race_labels").getKeys(false)) {
                String label = config.getString("race_labels." + raceId);
                if (label != null && !label.isBlank()) {
                    raceLabels.put(raceId.toLowerCase(Locale.ROOT), label);
                }
            }
        }
        this.labels.clear();
        this.labels.putAll(labels);
        this.classLabels.clear();
        this.classLabels.putAll(classLabels);
        this.raceLabels.clear();
        this.raceLabels.putAll(raceLabels);
        return true;
    }

    public String getLabel(String statKey) {
        if (statKey == null || statKey.isBlank()) {
            return "";
        }

        String label = labels.get(statKey);
        if (label != null && !label.isBlank()) {
            return label;
        }

        if (statKey.startsWith(CLASS_PREFIX)) {
            String classId = statKey.substring(CLASS_PREFIX.length()).toLowerCase(Locale.ROOT);
            label = classLabels.get(classId);
            if (label != null && !label.isBlank()) {
                return label;
            }
        }

        if (statKey.startsWith(RACE_PREFIX)) {
            String raceId = statKey.substring(RACE_PREFIX.length()).toLowerCase(Locale.ROOT);
            label = raceLabels.get(raceId);
            if (label != null && !label.isBlank()) {
                return label;
            }
        }

        return StatLabelFormatter.format(statKey);
    }
}
