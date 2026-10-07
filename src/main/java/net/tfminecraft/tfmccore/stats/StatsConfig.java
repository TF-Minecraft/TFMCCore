package net.tfminecraft.tfmccore.stats;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class StatsConfig {
    private boolean enabled = true;

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

        enabled = config.getBoolean("enabled", true);
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
