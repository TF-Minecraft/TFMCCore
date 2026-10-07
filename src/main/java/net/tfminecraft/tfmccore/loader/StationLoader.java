package net.tfminecraft.tfmccore.loader;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmccore.reference.Station;

public class StationLoader {
	private static final Map<String, Station> oList = new LinkedHashMap<>();

	public static void clear() {
		oList.clear();
	}

	public static Collection<Station> get() {
		return oList.values();
	}

	public static Station getByString(String id) {
		return oList.get(id);
	}

	public boolean load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		Map<String, Station> loaded = new LinkedHashMap<>();
		try {
			config.load(configFile);
			if (config.isConfigurationSection("stations")) {
				ConfigurationSection root = config.getConfigurationSection("stations");
				for (String key : root.getKeys(false)) {
					ConfigurationSection section = root.getConfigurationSection(key);
					if (section == null) {
						continue;
					}
					String block = section.getString("block");
					if (block == null || block.isBlank()) {
						continue;
					}
					Station.Click click = Station.parseClick(section.getString("click", "right"));
					loaded.put(key, new Station(key, block.trim(), click));
				}
			} else {
				List<String> list = config.getStringList("stations");
				for (String s : list) {
					String[] args = s.trim().split("\\s+");
					if (args.length < 2) {
						continue;
					}
					String block = args[0];
					String id = args[1];
					loaded.put(id, new Station(id, block, defaultClickForBlock(block)));
				}
			}
		} catch (IOException | InvalidConfigurationException | RuntimeException e) {
			e.printStackTrace();
			return false;
		}
		oList.clear();
		oList.putAll(loaded);
		return true;
	}

	private static Station.Click defaultClickForBlock(String block) {
		String path = block.toLowerCase(Locale.ROOT);
		if (path.contains("crafting_table")
				|| path.contains("blast_furnace")
				|| path.contains("stonecutter")
				|| path.contains("jukebox")
				|| path.contains("cartography_table")) {
			return Station.Click.SHIFT_RIGHT;
		}
		return Station.Click.RIGHT;
	}
}
