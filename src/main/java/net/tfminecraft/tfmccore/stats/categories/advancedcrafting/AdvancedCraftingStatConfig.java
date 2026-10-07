package net.tfminecraft.tfmccore.stats.categories.advancedcrafting;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmccore.stats.StatLabelFormatter;

public final class AdvancedCraftingStatConfig {
    private static final String ITEMS_CRAFTED_PREFIX = "items_crafted_";
    private static final String HITS_PREFIX = "hits_";

    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, String> categoryLabels = new HashMap<>();
    private final Map<String, String> hitLabels = new HashMap<>();

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
        Map<String, String> categoryLabels = new HashMap<>();
        Map<String, String> hitLabels = new HashMap<>();

        if (config.isConfigurationSection("labels")) {
            for (String statKey : config.getConfigurationSection("labels").getKeys(false)) {
                String label = config.getString("labels." + statKey);
                if (label != null && !label.isBlank()) {
                    labels.put(statKey, label);
                }
            }
        }

        if (config.isConfigurationSection("category_labels")) {
            for (String categoryId : config.getConfigurationSection("category_labels").getKeys(false)) {
                String label = config.getString("category_labels." + categoryId);
                if (label != null && !label.isBlank()) {
                    categoryLabels.put(categoryId.toLowerCase(Locale.ROOT), label);
                }
            }
        }

        if (config.isConfigurationSection("hit_labels")) {
            for (String hitId : config.getConfigurationSection("hit_labels").getKeys(false)) {
                String label = config.getString("hit_labels." + hitId);
                if (label != null && !label.isBlank()) {
                    hitLabels.put(hitId.toLowerCase(Locale.ROOT), label);
                }
            }
        }
        this.labels.clear();
        this.labels.putAll(labels);
        this.categoryLabels.clear();
        this.categoryLabels.putAll(categoryLabels);
        this.hitLabels.clear();
        this.hitLabels.putAll(hitLabels);
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

        if (statKey.startsWith(ITEMS_CRAFTED_PREFIX)) {
            String categoryId = statKey.substring(ITEMS_CRAFTED_PREFIX.length()).toLowerCase(Locale.ROOT);
            label = categoryLabels.get(categoryId);
            if (label != null && !label.isBlank()) {
                return label;
            }
        }

        if (statKey.startsWith(HITS_PREFIX)) {
            String hitId = statKey.substring(HITS_PREFIX.length()).toLowerCase(Locale.ROOT);
            label = hitLabels.get(hitId);
            if (label != null && !label.isBlank()) {
                return label;
            }
        }

        return StatLabelFormatter.format(statKey);
    }
}
