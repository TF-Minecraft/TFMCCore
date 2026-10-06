package net.tfminecraft.tfmccore.whistle;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import net.tfminecraft.tfmccore.TFMCCore;

public final class WhistleConfigLoader {

    private WhistleConfigLoader() {}

    public static boolean load(File file) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            severe("[TFMCCore] Failed to load animal-whistle-config.yml: " + ex.getMessage());
            return false;
        }
        WhistleConfig.itemPath = config.getString("items.animal-whistle", WhistleConfig.itemPath);
        WhistleConfig.detectionRadius = Math.max(0.0, config.getDouble("settings.detection-radius", WhistleConfig.detectionRadius));
        WhistleConfig.glowDuration = Math.max(0, config.getInt("settings.glow-duration", WhistleConfig.glowDuration));
        WhistleConfig.cooldownSeconds = Math.max(0, config.getInt("settings.cooldown", WhistleConfig.cooldownSeconds));
        WhistleConfig.whitelistedAnimals.clear();
        for (String name : config.getStringList("settings.whitelisted-animals")) {
            try {
                WhistleConfig.whitelistedAnimals.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                warn("Unknown entity type in animal whistle whitelist: " + name);
            }
        }
        if (WhistleConfig.whitelistedAnimals.isEmpty()) {
            warn("Animal whistle whitelist is empty - defaulting to HORSE");
            WhistleConfig.whitelistedAnimals.add(EntityType.HORSE);
        }
        WhistleConfig.soundName = config.getString("sound.type", WhistleConfig.soundName);
        WhistleConfig.soundVolume = (float) config.getDouble("sound.volume", WhistleConfig.soundVolume);
        WhistleConfig.soundPitch = (float) config.getDouble("sound.pitch", WhistleConfig.soundPitch);
        WhistleConfig.highlightedMessage = config.getString("messages.highlighted", WhistleConfig.highlightedMessage);
        WhistleConfig.noAnimalsMessage = config.getString("messages.no-animals", WhistleConfig.noAnimalsMessage);
        WhistleConfig.cooldownMessage = config.getString("messages.cooldown", WhistleConfig.cooldownMessage);
        return true;
    }

    private static void warn(String message) {
        TFMCCore instance = TFMCCore.getInstance();
        if (instance != null) {
            instance.getLogger().warning(message);
        }
    }

    private static void severe(String message) {
        TFMCCore instance = TFMCCore.getInstance();
        if (instance != null) {
            instance.getLogger().severe(message);
        }
    }
}
