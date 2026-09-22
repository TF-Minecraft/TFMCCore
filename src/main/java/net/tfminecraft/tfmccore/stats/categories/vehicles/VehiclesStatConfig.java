package net.tfminecraft.tfmccore.stats.categories.vehicles;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
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
        vehicleTypeToGroup.clear();
        groupDeathToStatKey.clear();
        labels.clear();

        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
            return;
        }

        if (config.isConfigurationSection("groups")) {
            for (String group : config.getConfigurationSection("groups").getKeys(false)) {
                List<String> vehicles = config.getStringList("groups." + group + ".vehicles");
                for (String vehicleTypeId : vehicles) {
                    if (vehicleTypeId == null || vehicleTypeId.isBlank()) {
                        continue;
                    }
                    vehicleTypeToGroup.put(vehicleTypeId.toLowerCase(), group.toLowerCase());
                }
            }
        }

        if (config.isConfigurationSection("death-stats")) {
            for (String group : config.getConfigurationSection("death-stats").getKeys(false)) {
                Map<String, String> deathMap = new HashMap<>();
                for (String deathCause : config.getConfigurationSection("death-stats." + group).getKeys(false)) {
                    String statKey = config.getString("death-stats." + group + "." + deathCause);
                    if (statKey != null && !statKey.isBlank()) {
                        deathMap.put(deathCause.toLowerCase(), statKey);
                    }
                }
                groupDeathToStatKey.put(group.toLowerCase(), deathMap);
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
    }

    public Optional<String> resolveStatKey(String vehicleTypeId, VehicleDeath deathCause) {
        if (vehicleTypeId == null || vehicleTypeId.isBlank() || deathCause == null) {
            return Optional.empty();
        }

        String group = vehicleTypeToGroup.get(vehicleTypeId.toLowerCase());
        if (group == null) {
            return Optional.empty();
        }

        Map<String, String> deathMap = groupDeathToStatKey.get(group);
        if (deathMap == null) {
            return Optional.empty();
        }

        String statKey = deathMap.get(deathCause.name().toLowerCase());
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
