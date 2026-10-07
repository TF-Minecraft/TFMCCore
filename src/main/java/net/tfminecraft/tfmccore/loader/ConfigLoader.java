package net.tfminecraft.tfmccore.loader;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmccore.cache.Cache;

public class ConfigLoader {
    public boolean loadConfig(File configFile) {
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
            return false;
        }

        Cache.blockedConsume.clear();
        Cache.blockedCrafts.clear();

        Cache.compactResourcePackOverlays = config.getBoolean("resource-pack.compact-overlays", true);

        Cache.allowBoneMeal = config.getBoolean("bone-meal", true);
        Cache.limitShields = config.getBoolean("limit-shields", false);
        Cache.allowBrewing = config.getBoolean("allow-brewing", true);
        Cache.allowEnchanting = config.getBoolean("allow-enchanting", true);
        Cache.horseArchery = config.getBoolean("horse-archery", true);
        Cache.preventGolemScrape = config.getBoolean("prevent-golem-scrape", true);
        Cache.dropsDebug = config.getBoolean("drops-debug", true);
        Cache.xaeroFairPlay = config.getBoolean("xaero-fair-play", true);
        Cache.hideBookGlint = config.getBoolean("hide-book-glint", true);

        Cache.armourTime = config.getInt("armour-time", 7);

        if(config.contains("blocked-consume")) {
            for(String s : config.getStringList("blocked-consume")) {
                try {
                    Cache.blockedConsume.add(Material.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (Exception e) {
                    Bukkit.getLogger().info("[TFMCCore] could not convert "+s+" to a material");
                }
            }
        }

        if(config.contains("blocked-crafts")) {
            for(String s : config.getStringList("blocked-crafts")) {
                try {
                    Cache.blockedCrafts.add(Material.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (Exception e) {
                    Bukkit.getLogger().info("[TFMCCore] could not convert "+s+" to a material");
                }
            }
        }
        return true;
	}
}
