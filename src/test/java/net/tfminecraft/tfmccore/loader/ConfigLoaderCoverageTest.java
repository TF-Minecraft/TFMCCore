package net.tfminecraft.tfmccore.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.cache.Cache;
import net.tfminecraft.tfmccore.reference.Station;
import net.tfminecraft.tfmccore.whistle.WhistleConfig;
import net.tfminecraft.tfmccore.whistle.WhistleConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderCoverageTest {
  @TempDir Path root;
  private final Map<Field, Object> before = new LinkedHashMap<>();
  private Locale previousLocale;

  @BeforeEach
  void snapshotGlobals() throws Exception {
    previousLocale = Locale.getDefault();
    for (Class<?> type : List.of(Cache.class, WhistleConfig.class)) {
      for (Field field : type.getFields()) {
        Object value = field.get(null);
        before.put(
            field,
            value instanceof List<?> list
                ? new ArrayList<>(list)
                : value instanceof EnumSet<?> set ? set.clone() : value);
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @AfterEach
  void restoreGlobals() throws Exception {
    Locale.setDefault(previousLocale);
    for (var entry : before.entrySet()) {
      if (Modifier.isFinal(entry.getKey().getModifiers())
          && entry.getValue() instanceof Collection<?> value) {
        Collection current = (Collection) entry.getKey().get(null);
        current.clear();
        current.addAll(value);
      } else entry.getKey().set(null, entry.getValue());
    }
  }

  @Test
  void coldConfigurationFailureRetainsTheSameDefaultsAsAnEmptyConfiguration() throws Exception {
    ClassLoader isolated =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(Cache.class.getName()) && !name.equals(ConfigLoader.class.getName()))
              return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
              try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (input == null) throw new ClassNotFoundException(name);
                byte[] bytes = input.readAllBytes();
                loaded =
                    defineClass(name, bytes, 0, bytes.length, Cache.class.getProtectionDomain());
              } catch (IOException ex) {
                throw new ClassNotFoundException(name, ex);
              }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
          }
        };
    Class<?> cache = isolated.loadClass(Cache.class.getName());
    assertSame(cache, cache.getConstructor().newInstance().getClass());
    Object loader = isolated.loadClass(ConfigLoader.class.getName()).getConstructor().newInstance();
    Object result =
        loader
            .getClass()
            .getMethod("loadConfig", java.io.File.class)
            .invoke(loader, root.resolve("missing.yml").toFile());
    assertEquals(false, result);
    for (String name :
        List.of(
            "compactResourcePackOverlays",
            "allowBoneMeal",
            "allowBrewing",
            "allowEnchanting",
            "horseArchery",
            "preventGolemScrape",
            "dropsDebug",
            "xaeroFairPlay",
            "hideBookGlint")) {
      assertEquals(true, cache.getField(name).get(null), name);
    }
    assertEquals(false, cache.getField("limitShields").get(null));
    assertEquals(7, cache.getField("armourTime").get(null));
    assertEquals(List.of(), cache.getField("blockedCrafts").get(null));
    assertEquals(List.of(), cache.getField("blockedConsume").get(null));
  }

  @Test
  void validConfigReplacesValuesAndMalformedReloadRetainsThem() throws Exception {
    Path config = root.resolve("config.yml");
    Files.writeString(
        config,
        """
        resource-pack:
          compact-overlays: false
        bone-meal: false
        allow-brewing: false
        allow-enchanting: false
        limit-shields: true
        horse-archery: false
        prevent-golem-scrape: false
        drops-debug: false
        xaero-fair-play: false
        hide-book-glint: false
        armour-time: 12
        blocked-consume: [GOLDEN_APPLE]
        blocked-crafts: [DIAMOND_SWORD]
        """);
    ConfigLoader loader = new ConfigLoader();
    assertTrue(loader.loadConfig(config.toFile()));
    assertFalse(Cache.compactResourcePackOverlays);
    assertFalse(Cache.allowBoneMeal);
    assertFalse(Cache.allowBrewing);
    assertFalse(Cache.allowEnchanting);
    assertTrue(Cache.limitShields);
    assertFalse(Cache.horseArchery);
    assertFalse(Cache.preventGolemScrape);
    assertFalse(Cache.dropsDebug);
    assertFalse(Cache.xaeroFairPlay);
    assertFalse(Cache.hideBookGlint);
    assertEquals(12, Cache.armourTime);
    assertEquals(List.of(Material.GOLDEN_APPLE), Cache.blockedConsume);
    assertEquals(List.of(Material.DIAMOND_SWORD), Cache.blockedCrafts);
    Files.writeString(config, "blocked-crafts: [broken");
    assertFalse(loader.loadConfig(config.toFile()));
    assertEquals(12, Cache.armourTime);
    assertEquals(List.of(Material.DIAMOND_SWORD), Cache.blockedCrafts);
    assertFalse(loader.loadConfig(root.toFile()));
    Files.writeString(config, "{}");
    assertTrue(loader.loadConfig(config.toFile()));
    assertTrue(Cache.allowBoneMeal);
    assertTrue(Cache.compactResourcePackOverlays);
    assertTrue(Cache.hideBookGlint);
    assertEquals(7, Cache.armourTime);
    assertTrue(Cache.blockedCrafts.isEmpty());
    assertTrue(Cache.blockedConsume.isEmpty());
  }

  @Test
  void materialAndStationIdentifiersDoNotDependOnServerLocale() throws Exception {
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    Path config = root.resolve("config.yml");
    Files.writeString(
        config, "blocked-consume: [diamond, invalid]\nblocked-crafts: [iron_sword, invalid]\n");
    Logger logger = mock(Logger.class);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getLogger).thenReturn(logger);
      assertTrue(new ConfigLoader().loadConfig(config.toFile()));
      assertEquals(List.of(Material.DIAMOND), Cache.blockedConsume);
      assertEquals(List.of(Material.IRON_SWORD), Cache.blockedCrafts);
      verify(logger, times(2)).info(contains("could not convert invalid"));
    }
    assertEquals(Station.Click.SHIFT_RIGHT, Station.parseClick(" SHIFT-RIGHT "));
    Station station = new Station("loom", "minecraft:loom", null);
    assertEquals(Station.Click.RIGHT, station.getClick());
    assertTrue(station.matchesClick(false));
    assertFalse(station.matchesClick(true));
  }

  @Test
  void whistleTypesUseRootLocaleAndFailedReloadKeepsPreviousSettings() throws Exception {
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    Path config = root.resolve("whistle.yml");
    Files.writeString(config, "settings:\n  whitelisted-animals: [pig, missing]\n");
    TFMCCore plugin = mock(TFMCCore.class);
    Logger logger = mock(Logger.class);
    when(plugin.getLogger()).thenReturn(logger);
    try (var core = mockStatic(TFMCCore.class)) {
      core.when(TFMCCore::getInstance).thenReturn(plugin);
      assertTrue(WhistleConfigLoader.load(config.toFile()));
      assertEquals(Set.of(EntityType.PIG), WhistleConfig.whitelistedAnimals);
      verify(logger).warning(contains("Unknown entity type"));
      Files.writeString(config, "settings: [broken");
      assertFalse(WhistleConfigLoader.load(config.toFile()));
      assertEquals(Set.of(EntityType.PIG), WhistleConfig.whitelistedAnimals);
      verify(logger).severe(contains("Failed to load"));
      Files.writeString(config, "settings:\n  whitelisted-animals: []\n");
      assertTrue(WhistleConfigLoader.load(config.toFile()));
      assertEquals(Set.of(EntityType.HORSE), WhistleConfig.whitelistedAnimals);
      verify(logger).warning(contains("defaulting to HORSE"));
    }
  }
}
