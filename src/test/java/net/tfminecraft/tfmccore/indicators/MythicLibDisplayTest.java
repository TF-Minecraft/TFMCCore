package net.tfminecraft.tfmccore.indicators;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.lumine.mythic.lib.api.event.IndicatorDisplayEvent.IndicatorType;
import io.lumine.mythic.lib.util.IndicatorConfig;

class MythicLibDisplayTest {

  @TempDir Path dir;

  private final List<ConfigurationSection> built = new ArrayList<>();
  private final IndicatorConfig config = mock(IndicatorConfig.class);

  private MythicLibDisplay display(File file) {
    return new MythicLibDisplay(file, section -> {
      built.add(section);
      return config;
    });
  }

  @Test
  void usesMythicLibsDamageIndicatorSectionAndRereadsItWhenItChanges() throws IOException {
    File file = dir.resolve("indicators.yml").toFile();
    Files.writeString(file.toPath(), "damage_indicators:\n  decimal_format: '0.##'\n  lifespan: 30\n");
    when(config.formatNumber(3.704)).thenReturn("3.7");
    MythicLibDisplay display = display(file);

    assertEquals("3.7", display.format(3.704));
    Entity entity = mock(Entity.class);
    Vector direction = new Vector(1, 0, 0);
    display.show(entity, "&f3.7", direction);

    verify(config).displayIndicator(entity, "&f3.7", direction, IndicatorType.DAMAGE);
    assertEquals(1, built.size());
    assertEquals("0.##", built.get(0).getString("decimal_format"));
    assertEquals(30, built.get(0).getLong("lifespan"));

    Files.writeString(file.toPath(), "damage_indicators:\n  decimal_format: '0'\n");
    assertTrue(file.setLastModified(file.lastModified() + 5000));
    display.format(1);

    assertEquals(2, built.size());
    assertEquals("0", built.get(1).getString("decimal_format"));
  }

  @Test
  void fallsBackToMythicLibsDefaultFormatWithoutTheSection() {
    MythicLibDisplay display = display(dir.resolve("missing.yml").toFile());

    display.format(1);

    assertEquals("0.#", built.get(0).getString("decimal_format"));
    assertEquals("{value}", built.get(0).getString("format"));
  }
}
