package net.tfminecraft.tfmccore.indicators;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import io.lumine.mythic.lib.api.event.IndicatorDisplayEvent;
import io.lumine.mythic.lib.util.IndicatorConfig;

/**
 * Shows indicators with MythicLib's own {@link IndicatorConfig}, built from the
 * {@code damage_indicators} section of MythicLib's {@code indicators.yml}: same number format,
 * placement and motion as MythicLib. {@link #refresh()} checks the file at most once per tick
 * that has indicators to show, and reads it again when it has changed. A file it cannot use (broken
 * YAML or an invalid {@code decimal_format}, say) is reported once and the previous settings stay, or
 * MythicLib's defaults before any were read.
 */
final class MythicLibDisplay implements RealDamageIndicators.Display {

    private final File file;
    private final Function<ConfigurationSection, IndicatorConfig> factory;
    private final Logger logger;
    private IndicatorConfig config;
    private long loaded;

    MythicLibDisplay(File file, Function<ConfigurationSection, IndicatorConfig> factory, Logger logger) {
        this.file = file;
        this.factory = factory;
        this.logger = logger;
    }

    @Override
    public void refresh() {
        long modified = file.lastModified();
        if (config != null && modified == loaded) return;
        loaded = modified;
        ConfigurationSection section;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            section = yaml.getConfigurationSection("damage_indicators");
        } catch (FileNotFoundException e) {
            section = null;
        } catch (IOException | InvalidConfigurationException e) {
            // A typo must not quietly swap the settings for the defaults
            keepPrevious(e);
            return;
        }
        try {
            config = factory.apply(section != null ? section : defaults());
        } catch (RuntimeException e) {
            keepPrevious(e);
        }
    }

    private void keepPrevious(Exception e) {
        logger.warning("Damage indicators: can't use " + file + " (" + e + "); keeping the previous settings");
        if (config == null) config = factory.apply(defaults());
    }

    /** MythicLib refuses to show damage indicators without the section; this only covers it going missing later. */
    private static ConfigurationSection defaults() {
        ConfigurationSection section = new MemoryConfiguration();
        section.set("decimal_format", "0.#");
        section.set("format", "{value}");
        return section;
    }

    private IndicatorConfig config() {
        if (config == null) refresh();
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
