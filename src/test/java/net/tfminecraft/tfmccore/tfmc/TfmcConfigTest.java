package net.tfminecraft.tfmccore.tfmc;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TfmcConfigTest {
  @TempDir Path directory;

  @Test
  void fileReloadReplacesContentOnlyAfterTheWholeFileParses() throws Exception {
    TfmcConfig config = new TfmcConfig();
    Path file = directory.resolve("tfmc.yml");
    Files.writeString(file, "tips:\n  permission: tips.off\n  enabled: [First, Second]\n");
    assertTrue(config.load(file.toFile()));
    assertEquals("tips.off", config.string("tips.permission"));
    assertEquals(List.of("First", "Second"), config.lines("tips.enabled"));

    Files.writeString(file, "tips:\n  permission: replacement\n  enabled: [unterminated\n");
    assertFalse(config.load(file.toFile()));
    assertEquals("tips.off", config.string("tips.permission"));
    assertEquals(List.of("First", "Second"), config.lines("tips.enabled"));

    Files.delete(file);
    assertFalse(config.load(file.toFile()));
    assertEquals("tips.off", config.string("tips.permission"));
    Files.createDirectory(file);
    assertFalse(config.load(file.toFile()));
    assertEquals(List.of("First", "Second"), config.lines("tips.enabled"));
    Files.delete(file);

    Files.writeString(file, "{}");
    assertTrue(config.load(file.toFile()));
    assertEquals("fallback", config.string("tips.permission", "fallback"));
    assertTrue(config.lines("tips.enabled").isEmpty());
  }
}
