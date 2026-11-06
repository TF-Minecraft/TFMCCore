package net.tfminecraft.tfmccore.indicators;

import java.io.File;
import java.util.function.Function;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import io.lumine.mythic.lib.api.event.IndicatorDisplayEvent;
import io.lumine.mythic.lib.util.IndicatorConfig;

/**
 * Shows indicators with MythicLib's own {@link IndicatorConfig}, built from the
 * {@code damage_indicators} section of MythicLib's {@code indicators.yml}: same number format,
 * placement and motion as MythicLib. The file is read again when it changes, which covers a
 * {@code /ml reload} after an edit.
 */
final class MythicLibDisplay implements RealDamageIndicators.Display {

    private final File file;
    private final Function<ConfigurationSection, IndicatorConfig> factory;
    private IndicatorConfig config;
    private long loaded;

    MythicLibDisplay(File file, Function<ConfigurationSection, IndicatorConfig> factory) {
        this.file = file;
        this.factory = factory;
    }

    IndicatorConfig config() {
        long modified = file.lastModified();
        if (config == null || modified != loaded) {
            ConfigurationSection section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("damage_indicators");
            if (section == null) {
                // MythicLib refuses to show damage indicators without the section; this only covers it going missing later
                section = new MemoryConfiguration();
                section.set("decimal_format", "0.#");
                section.set("format", "{value}");
            }
            config = factory.apply(section);
            loaded = modified;
        }
        return config;
    }

    @Override
    public String format(double value) {
        return config().formatNumber(value);
    }

    @Override
    public void show(Entity entity, String line, Vector direction) {
        config().displayIndicator(entity, line, direction, IndicatorDisplayEvent.IndicatorType.DAMAGE);
    }
}
