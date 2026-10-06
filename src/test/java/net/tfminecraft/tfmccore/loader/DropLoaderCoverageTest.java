package net.tfminecraft.tfmccore.loader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.tfminecraft.tfmccore.reference.Drop;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DropLoaderCoverageTest {
  @TempDir Path directory;
  private final DropLoader loader = new DropLoader();
  private Map<String, Drop> previous;

  @BeforeEach
  void isolateDefinitions() {
    previous = new HashMap<>(DropLoader.oList);
    DropLoader.clear();
  }

  @AfterEach
  void restoreDefinitions() {
    DropLoader.clear();
    DropLoader.oList.putAll(previous);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "directory", "yaml"})
  void failedConfigurationReadKeepsPreviouslyLoadedObjects(String failure) throws IOException {
    Map<String, Drop> loaded = loadInitialDefinitions();
    Path replacement = directory.resolve("replacement.yml");
    switch (failure) {
      case "directory" -> Files.createDirectory(replacement);
      case "yaml" -> Files.writeString(replacement, "drops: [unterminated\n");
      default -> {}
    }

    assertFalse(loader.load(replacement.toFile()));

    assertExistingDefinitions(loaded);
  }

  @Test
  void invalidEntryAfterAValidEntryDoesNotPublishAPartialReload() throws IOException {
    Map<String, Drop> loaded = loadInitialDefinitions();
    Path replacement =
        write(
            "replacement.yml",
            """
            drops:
              original:
                materials: [DIAMOND_ORE]
                vanilla_drops: false
                drops: ["v.diamond(1) 1 1"]
              later_invalid:
                materials: [GOLD_ORE]
                drops: ["   "]
            """);

    assertFalse(assertDoesNotThrow(() -> loader.load(replacement.toFile())));

    assertExistingDefinitions(loaded);
    assertNull(DropLoader.getByString("later_invalid"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void successfulReloadReplacesRemovedDefinitionsAndSupportsBothRootFormats(boolean nested)
      throws IOException {
    loadInitialDefinitions();
    String definitions =
        """
        note: ignored metadata
        replacement:
          materials: [DIAMOND_ORE, "DEEPSLATE_DIAMOND_ORE(1.5)"]
          vanilla_drops: false
          drops: ["v.diamond(1) 1 1"]
        """;
    if (nested) definitions = "drops:\n" + definitions.indent(2);

    assertTrue(loader.load(write("replacement.yml", definitions).toFile()));

    Drop replacement = DropLoader.getByString("replacement");
    assertEquals(1, DropLoader.get().size());
    assertEquals("replacement", replacement.getId());
    assertTrue(replacement.appliesToMaterial(Material.DIAMOND_ORE));
    assertTrue(replacement.appliesToMaterial(Material.DEEPSLATE_DIAMOND_ORE));
    assertFalse(replacement.appliesToMaterial(Material.STONE));
    assertFalse(replacement.keepsVanillaDrops());
    assertNull(DropLoader.getByString("original"));
    assertNull(DropLoader.getByString("removed"));
    assertNull(DropLoader.getByString("note"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "drops: {}\n"})
  void validEmptyConfigurationClearsPreviousDefinitions(String content) throws IOException {
    loadInitialDefinitions();

    assertTrue(loader.load(write("empty.yml", content).toFile()));

    assertTrue(DropLoader.get().isEmpty());
    assertNull(DropLoader.getByString("original"));
  }

  @Test
  void legacyAliasesRetainMaterialAndPermissionRules() throws IOException {
    Path file =
        write(
            "legacy.yml",
            """
            legacy:
              material: [GOLD_ORE]
              required-permissions: [profession.goldsmith]
              vanilla-drops: false
              tool: none
              drops: ["v.gold_nugget(0.5)"]
            """);

    assertTrue(loader.load(file.toFile()));

    Drop drop = DropLoader.getByString("legacy");
    Player player = mock(Player.class);
    assertTrue(drop.appliesToMaterial(Material.GOLD_ORE));
    assertFalse(drop.keepsVanillaDrops());
    assertEquals(
        "missing permission profession.goldsmith",
        drop.skipReasonForBroken(player, null, Material.GOLD_ORE));
    when(player.hasPermission("profession.goldsmith")).thenReturn(true);
    assertNull(drop.skipReasonForBroken(player, null, Material.GOLD_ORE));
  }

  private Map<String, Drop> loadInitialDefinitions() throws IOException {
    assertTrue(
        loader.load(
            write(
                    "initial.yml",
                    """
                    drops:
                      original:
                        materials: [STONE]
                        required_permissions: [miner]
                        drops: ["v.stone(1) 1 1"]
                      removed:
                        materials: [OAK_LOG]
                        drops: ["v.stick(0.5)"]
                    """)
                .toFile()));
    return new HashMap<>(DropLoader.oList);
  }

  private void assertExistingDefinitions(Map<String, Drop> loaded) {
    assertEquals(loaded.keySet(), DropLoader.oList.keySet());
    loaded.forEach((id, drop) -> assertSame(drop, DropLoader.getByString(id)));
    assertTrue(DropLoader.getByString("original").appliesToMaterial(Material.STONE));
  }

  private Path write(String filename, String content) throws IOException {
    return Files.writeString(directory.resolve(filename), content);
  }
}
