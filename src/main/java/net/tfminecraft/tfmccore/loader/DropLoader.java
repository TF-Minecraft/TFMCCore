package net.tfminecraft.tfmccore.loader;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmccore.reference.Drop;

public class DropLoader {
    static Map<String, Drop> oList = new HashMap<>();
	public static void clear() {
		oList.clear();
	}
	public static Collection<Drop> get() {
		return oList.values();
	}
	public static Drop getByString(String id) {
		return oList.get(id);
	}
	public boolean load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		Map<String, Drop> loaded = new HashMap<>();
		try {
			config.load(configFile);
			ConfigurationSection root = config.getConfigurationSection("drops");
			if (root == null) {
				root = config;
			}
			for (String key : root.getKeys(false)) {
				ConfigurationSection section = root.getConfigurationSection(key);
				if (section == null) continue;
				loaded.put(key, new Drop(key, section));
			}
		} catch (IOException | InvalidConfigurationException | RuntimeException e) {
			e.printStackTrace();
			return false;
		}
		oList.clear();
		oList.putAll(loaded);
		return true;
	}
}
