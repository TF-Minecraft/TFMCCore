package net.tfminecraft.tfmccore.stats.categories.factions;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmccore.stats.StatLabelFormatter;

public final class FactionsStatConfig {
    private final Map<String, String> labels = new HashMap<>();

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

        if (config.isConfigurationSection("labels")) {
            for (String statKey : config.getConfigurationSection("labels").getKeys(false)) {
                String label = config.getString("labels." + statKey);
                if (label != null && !label.isBlank()) {
                    labels.put(statKey, label);
                }
            }
        }
        this.labels.clear();
        this.labels.putAll(labels);
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

        return StatLabelFormatter.format(statKey);
    }
}
