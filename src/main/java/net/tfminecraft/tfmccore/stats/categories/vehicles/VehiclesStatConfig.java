package net.tfminecraft.tfmccore.stats.categories.vehicles;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.tfmccore.stats.StatLabelFormatter;

public final class VehiclesStatConfig {
    private final Map<String, String> vehicleTypeToGroup = new HashMap<>();
    private final Map<String, Map<String, String>> groupDeathToStatKey = new HashMap<>();
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

        Map<String, String> vehicleTypeToGroup = new HashMap<>();
        Map<String, Map<String, String>> groupDeathToStatKey = new HashMap<>();
        Map<String, String> labels = new HashMap<>();

        for (String section : List.of("groups", "death-stats", "labels")) {
            if (config.contains(section) && !config.isConfigurationSection(section)) {
                System.err.println("[TFMCCore] " + configFile.getName()
                        + ": " + section + " must be a section");
                return false;
            }
        }

        if (config.isConfigurationSection("groups")) {
            for (String group : config.getConfigurationSection("groups").getKeys(false)) {
                List<String> vehicles = config.getStringList("groups." + group + ".vehicles");
                for (String vehicleTypeId : vehicles) {
                    if (vehicleTypeId == null || vehicleTypeId.isBlank()) {
                        continue;
                    }
                    vehicleTypeToGroup.put(vehicleTypeId.toLowerCase(Locale.ROOT), group.toLowerCase(Locale.ROOT));
                }
            }
        }

        if (config.isConfigurationSection("death-stats")) {
            for (String group : config.getConfigurationSection("death-stats").getKeys(false)) {
                Map<String, String> deathMap = new HashMap<>();
                var deathSection = config.getConfigurationSection("death-stats." + group);
                if (deathSection == null) {
                    System.err.println("[TFMCCore] " + configFile.getName()
                            + ": death-stats." + group + " must be a section");
                    return false;
                }
                for (String deathCause : deathSection.getKeys(false)) {
                    String statKey = config.getString("death-stats." + group + "." + deathCause);
                    if (statKey != null && !statKey.isBlank()) {
                        deathMap.put(deathCause.toLowerCase(Locale.ROOT), statKey);
                    }
                }
                groupDeathToStatKey.put(group.toLowerCase(Locale.ROOT), deathMap);
            }
        }

        if (config.isConfigurationSection("labels")) {
            for (String statKey : config.getConfigurationSection("labels").getKeys(false)) {
                String label = config.getString("labels." + statKey);
                if (label != null && !label.isBlank()) {
                    labels.put(statKey, label);
                }
            }
        }
        this.vehicleTypeToGroup.clear();
        this.vehicleTypeToGroup.putAll(vehicleTypeToGroup);
        this.groupDeathToStatKey.clear();
        this.groupDeathToStatKey.putAll(groupDeathToStatKey);
        this.labels.clear();
        this.labels.putAll(labels);
        return true;
    }

    public Optional<String> resolveStatKey(String vehicleTypeId, VehicleDeath deathCause) {
        if (vehicleTypeId == null || vehicleTypeId.isBlank() || deathCause == null) {
            return Optional.empty();
        }

        String group = vehicleTypeToGroup.get(vehicleTypeId.toLowerCase(Locale.ROOT));
        if (group == null) {
            return Optional.empty();
        }

        Map<String, String> deathMap = groupDeathToStatKey.get(group);
        if (deathMap == null) {
            return Optional.empty();
        }

        String statKey = deathMap.get(deathCause.name().toLowerCase(Locale.ROOT));
        if (statKey == null || statKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(statKey);
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
