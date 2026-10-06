package net.tfminecraft.tfmccore.stats.categories.skills;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmccore.stats.StatLabelFormatter;

public final class SkillsStatConfig {
    private static final String SKILL_PREFIX = "skill_";

    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, String> skillLabels = new HashMap<>();

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
        Map<String, String> skillLabels = new HashMap<>();

        if (config.isConfigurationSection("labels")) {
            for (String statKey : config.getConfigurationSection("labels").getKeys(false)) {
                String label = config.getString("labels." + statKey);
                if (label != null && !label.isBlank()) {
                    labels.put(statKey, label);
                }
            }
        }

        if (config.isConfigurationSection("skill_labels")) {
            for (String skillId : config.getConfigurationSection("skill_labels").getKeys(false)) {
                String label = config.getString("skill_labels." + skillId);
                if (label != null && !label.isBlank()) {
                    skillLabels.put(skillId.toLowerCase(Locale.ROOT), label);
                }
            }
        }
        this.labels.clear();
        this.labels.putAll(labels);
        this.skillLabels.clear();
        this.skillLabels.putAll(skillLabels);
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

        if (statKey.startsWith(SKILL_PREFIX)) {
            String skillId = statKey.substring(SKILL_PREFIX.length()).toLowerCase(Locale.ROOT);
            label = skillLabels.get(skillId);
            if (label != null && !label.isBlank()) {
                return label;
            }
        }

        return StatLabelFormatter.format(statKey);
    }
}
