package net.tfminecraft.tfmccore.stones;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmccore.TFMCCore;

public final class LorestoneConfigLoader {

    private LorestoneConfigLoader() {}

    public static boolean load(File file) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            severe("[TFMCCore] Failed to load lorestones-config.yml: " + ex.getMessage());
            return false;
        }
        LorestoneConfig.lorestonePath = config.getString("items.lorestone", LorestoneConfig.lorestonePath);
        LorestoneConfig.namestonePath = config.getString("items.namestone", LorestoneConfig.namestonePath);
        if (config.contains("settings.blacklist")) {
            LorestoneConfig.blacklist = new ArrayList<>(config.getStringList("settings.blacklist"));
        }
        LorestoneConfig.maxLength = Math.max(1, Math.min(256,
                config.getInt("settings.max-length", LorestoneConfig.maxLength)));
        LorestoneConfig.promptTimeoutSeconds = Math.max(1, Math.min(600,
                config.getInt("settings.prompt-timeout-seconds", LorestoneConfig.promptTimeoutSeconds)));
        LorestoneConfig.maxLoreLines = Math.max(1, Math.min(64,
                config.getInt("settings.max-lore-lines", LorestoneConfig.maxLoreLines)));
        LorestoneConfig.promptLoreMessage = config.getString("messages.prompt-lore", LorestoneConfig.promptLoreMessage);
        LorestoneConfig.promptNameMessage = config.getString("messages.prompt-name", LorestoneConfig.promptNameMessage);
        LorestoneConfig.appliedLoreMessage = config.getString("messages.applied-lore", LorestoneConfig.appliedLoreMessage);
        LorestoneConfig.appliedNameMessage = config.getString("messages.applied-name", LorestoneConfig.appliedNameMessage);
        LorestoneConfig.clearedLoreMessage = config.getString("messages.cleared-lore", LorestoneConfig.clearedLoreMessage);
        LorestoneConfig.noLoreMessage = config.getString("messages.no-lore", LorestoneConfig.noLoreMessage);
        LorestoneConfig.pickColourMessage = config.getString("messages.pick-colour", LorestoneConfig.pickColourMessage);
        LorestoneConfig.invalidColourMessage = config.getString("messages.invalid-colour", LorestoneConfig.invalidColourMessage);
        LorestoneConfig.cancelledMessage = config.getString("messages.cancelled", LorestoneConfig.cancelledMessage);
        LorestoneConfig.timeoutMessage = config.getString("messages.timeout", LorestoneConfig.timeoutMessage);
        LorestoneConfig.itemMovedMessage = config.getString("messages.item-moved", LorestoneConfig.itemMovedMessage);
        LorestoneConfig.emptyMessage = config.getString("messages.empty", LorestoneConfig.emptyMessage);
        LorestoneConfig.tooLongMessage = config.getString("messages.too-long", LorestoneConfig.tooLongMessage);
        LorestoneConfig.tooManyLinesMessage = config.getString("messages.too-many-lines", LorestoneConfig.tooManyLinesMessage);
        LorestoneConfig.cannotApplyMessage = config.getString("messages.cannot-apply", LorestoneConfig.cannotApplyMessage);
        LorestoneConfig.stackedMessage = config.getString("messages.stacked", LorestoneConfig.stackedMessage);
        LorestoneConfig.expiredMessage = config.getString("messages.expired", LorestoneConfig.expiredMessage);
        LorestoneConfig.gaveMessage = config.getString("messages.gave", LorestoneConfig.gaveMessage);
        return true;
    }

    private static void severe(String message) {
        TFMCCore instance = TFMCCore.getInstance();
        if (instance != null) {
            instance.getLogger().severe(message);
        }
    }
}
