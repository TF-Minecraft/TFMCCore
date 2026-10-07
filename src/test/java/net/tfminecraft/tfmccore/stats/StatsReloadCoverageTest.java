package net.tfminecraft.tfmccore.stats;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.stats.categories.advancedcrafting.AdvancedCraftingStatConfig;
import net.tfminecraft.tfmccore.stats.categories.factions.FactionsStatConfig;
import net.tfminecraft.tfmccore.stats.categories.rpcharacters.RpCharactersStatConfig;
import net.tfminecraft.tfmccore.stats.categories.skills.SkillsStatConfig;
import net.tfminecraft.tfmccore.stats.categories.vehicles.VehiclesStatConfig;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StatsReloadCoverageTest {
  @TempDir Path root;

  record Category(String name, Consumer<File> load, Function<String, String> label) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Category> categories() {
    VehiclesStatConfig vehicles = new VehiclesStatConfig();
    RpCharactersStatConfig characters = new RpCharactersStatConfig();
    AdvancedCraftingStatConfig crafting = new AdvancedCraftingStatConfig();
    SkillsStatConfig skills = new SkillsStatConfig();
    FactionsStatConfig factions = new FactionsStatConfig();
    return Stream.of(
        new Category("vehicles", vehicles::load, vehicles::getLabel),
        new Category("characters", characters::load, characters::getLabel),
        new Category("crafting", crafting::load, crafting::getLabel),
        new Category("skills", skills::load, skills::getLabel),
        new Category("factions", factions::load, factions::getLabel));
  }

  @ParameterizedTest(name = "{0} retains labels after failed reads")
  @MethodSource("categories")
  void failedReloadPreservesLabelsAndValidEmptyReloadResetsThem(Category category)
      throws Exception {
    Path config = root.resolve("category.yml");
    Files.writeString(config, "labels:\n  score_key: Configured label\n");
    category.load().accept(config.toFile());
    assertEquals("Configured label", category.label().apply("score_key"));
    Files.writeString(config, "labels: [not closed\n");
    category.load().accept(config.toFile());
    assertEquals("Configured label", category.label().apply("score_key"));
    category.load().accept(root.resolve("missing.yml").toFile());
    assertEquals("Configured label", category.label().apply("score_key"));
    Files.writeString(config, "# explicitly reset\n");
    category.load().accept(config.toFile());
    assertEquals("Score Key", category.label().apply("score_key"));
    assertEquals("", category.label().apply(null));
    assertEquals("", category.label().apply(" "));
  }

  @Test
  void failedReloadDoesNotEnableDisabledStats() throws Exception {
    StatsConfig config = new StatsConfig();
    assertTrue(config.isEnabled());
    Path file = root.resolve("stats.yml");
    Files.writeString(file, "enabled: false\n");
    config.load(file.toFile());
    assertFalse(config.isEnabled());
    Files.writeString(file, "enabled: [broken\n");
    config.load(file.toFile());
    assertFalse(config.isEnabled());
    config.load(root.resolve("missing.yml").toFile());
    assertFalse(config.isEnabled());
    Files.writeString(file, "# empty selects documented default\n");
    config.load(file.toFile());
    assertTrue(config.isEnabled());
  }

  @Test
  void malformedVehicleGroupRetainsThePreviousMappingInsteadOfPartlyReplacingIt() throws Exception {
    VehiclesStatConfig config = new VehiclesStatConfig();
    Path file = root.resolve("vehicles.yml");
    Files.writeString(
        file,
        "groups:\n"
            + "  ship:\n"
            + "    vehicles: [ironclad]\n"
            + "death-stats:\n"
            + "  ship:\n"
            + "    sink: ships_sunk\n");
    config.load(file.toFile());
    assertEquals("ships_sunk", config.resolveStatKey("ironclad", VehicleDeath.SINK).orElseThrow());
    Files.writeString(
        file, "groups:\n  changed:\n    vehicles: [other]\ndeath-stats:\n  invalid: scalar\n");
    var diagnostic = new java.io.ByteArrayOutputStream();
    var previousError = System.err;
    try (var capture =
        new java.io.PrintStream(diagnostic, true, java.nio.charset.StandardCharsets.UTF_8)) {
      System.setErr(capture);
      assertFalse(config.loadChecked(file.toFile()));
    } finally {
      System.setErr(previousError);
    }
    assertTrue(
        diagnostic
            .toString(java.nio.charset.StandardCharsets.UTF_8)
            .contains("death-stats.invalid must be a section"));
    assertEquals("ships_sunk", config.resolveStatKey("ironclad", VehicleDeath.SINK).orElseThrow());
    assertTrue(config.resolveStatKey("other", VehicleDeath.SINK).isEmpty());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"groups", "death-stats", "labels"})
  void presentScalarVehicleSectionPreservesTheCompletePreviousConfiguration(String section)
      throws Exception {
    VehiclesStatConfig config = new VehiclesStatConfig();
    Path file = root.resolve("vehicles.yml");
    Files.writeString(
        file,
        "groups:\n"
            + "  ship:\n"
            + "    vehicles: [ironclad]\n"
            + "death-stats:\n"
            + "  ship:\n"
            + "    sink: ships_sunk\n"
            + "labels:\n"
            + "  ships_sunk: Lost ships\n");
    assertTrue(config.loadChecked(file.toFile()));
    Files.writeString(file, section + ": invalid\n");
    var diagnostic = new java.io.ByteArrayOutputStream();
    var previous = System.err;
    try (var capture =
        new java.io.PrintStream(diagnostic, true, java.nio.charset.StandardCharsets.UTF_8)) {
      System.setErr(capture);
      assertFalse(config.loadChecked(file.toFile()));
    } finally {
      System.setErr(previous);
    }
    assertTrue(
        diagnostic
            .toString(java.nio.charset.StandardCharsets.UTF_8)
            .contains(section + " must be a section"));
    assertEquals("ships_sunk", config.resolveStatKey("ironclad", VehicleDeath.SINK).orElseThrow());
    assertEquals("Lost ships", config.getLabel("ships_sunk"));
    Files.writeString(file, "# omitted sections intentionally clear the configuration\n");
    assertTrue(config.loadChecked(file.toFile()));
    assertTrue(config.resolveStatKey("ironclad", VehicleDeath.SINK).isEmpty());
    assertEquals("Ships Sunk", config.getLabel("ships_sunk"));
  }

  @Test
  void vehicleMappingsUseLocaleIndependentIdentifiers() throws Exception {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      VehiclesStatConfig config = new VehiclesStatConfig();
      Path file = root.resolve("vehicles.yml");
      Files.writeString(
          file,
          "groups:\n"
              + "  SHIP:\n"
              + "    vehicles: [IRONCLAD]\n"
              + "death-stats:\n"
              + "  ship:\n"
              + "    sink: ships_sunk\n");
      config.load(file.toFile());
      assertEquals(
          "ships_sunk", config.resolveStatKey("ironclad", VehicleDeath.SINK).orElseThrow());
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void vehicleGroupsIgnoreEmptyIdsAndReturnNoStatForMissingInputsOrMappings() throws Exception {
    VehiclesStatConfig config = new VehiclesStatConfig();
    Path file = root.resolve("vehicles.yml");
    Files.writeString(
        file,
        "groups:\n"
            + "  ship:\n"
            + "    vehicles: [ironclad, ' ']\n"
            + "  unmapped:\n"
            + "    vehicles: [other]\n"
            + "death-stats:\n"
            + "  ship:\n"
            + "    sink: ships_sunk\n"
            + "    crash: ' '\n");
    assertTrue(config.loadChecked(file.toFile()));
    assertTrue(config.resolveStatKey(null, VehicleDeath.SINK).isEmpty());
    assertTrue(config.resolveStatKey(" ", VehicleDeath.SINK).isEmpty());
    assertTrue(config.resolveStatKey("ironclad", null).isEmpty());
    assertTrue(config.resolveStatKey("other", VehicleDeath.SINK).isEmpty());
    assertTrue(config.resolveStatKey("ironclad", VehicleDeath.CRASH).isEmpty());
    assertEquals("ships_sunk", config.resolveStatKey("ironclad", VehicleDeath.SINK).orElseThrow());
  }

  @Test
  void craftingHitLabelsSupportConfiguredNamesAndFallbacks() throws Exception {
    AdvancedCraftingStatConfig config = new AdvancedCraftingStatConfig();
    Path file = root.resolve("crafting.yml");
    Files.writeString(file, "hit_labels:\n  MITHRIL: Mithril strikes\n  empty: ' '\n");
    assertTrue(config.loadChecked(file.toFile()));
    assertEquals("Mithril strikes", config.getLabel("hits_mithril"));
    assertEquals("Hits Empty", config.getLabel("hits_empty"));
    assertEquals("Hits Unknown", config.getLabel("hits_unknown"));
  }

  @Test
  void combinedReloadReportsAnyFailedFileWhileLoadingOtherCategories() throws Exception {
    TFMCCore plugin = mock(TFMCCore.class);
    when(plugin.getDataFolder()).thenReturn(root.toFile());
    when(plugin.reloadStatsConfigs()).thenCallRealMethod();
    Object[][] configs = {
      {"statsConfig", new StatsConfig()},
      {"vehiclesStatConfig", new VehiclesStatConfig()},
      {"rpCharactersStatConfig", new RpCharactersStatConfig()},
      {"advancedCraftingStatConfig", new AdvancedCraftingStatConfig()},
      {"skillsStatConfig", new SkillsStatConfig()},
      {"factionsStatConfig", new FactionsStatConfig()}
    };
    for (Object[] entry : configs) {
      var field = TFMCCore.class.getDeclaredField((String) entry[0]);
      field.setAccessible(true);
      field.set(plugin, entry[1]);
    }
    String[] names = {
      "stats",
      "vehiclestats",
      "rpcharactersstats",
      "advancedcraftingstats",
      "skillsstats",
      "factionsstats"
    };
    for (String name : names)
      Files.writeString(root.resolve(name + ".yml"), "labels:\n  score: Loaded\n");
    assertTrue(plugin.reloadStatsConfigs());
    for (String name : names) {
      Path file = root.resolve(name + ".yml");
      Files.delete(file);
      assertFalse(plugin.reloadStatsConfigs(), name + " failure must reach the command result");
      Files.writeString(file, "labels:\n  score: Reloaded\n");
    }
    assertTrue(plugin.reloadStatsConfigs());
    assertEquals("Reloaded", ((FactionsStatConfig) configs[5][1]).getLabel("score"));
  }
}
