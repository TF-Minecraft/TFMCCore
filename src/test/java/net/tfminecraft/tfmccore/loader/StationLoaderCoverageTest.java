package net.tfminecraft.tfmccore.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.tfminecraft.tfmccore.reference.Station;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StationLoaderCoverageTest {
  @TempDir Path directory;
  private final StationLoader loader = new StationLoader();

  @BeforeEach
  @AfterEach
  void clearDefinitions() {
    StationLoader.clear();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void absentClickSettingDefaultsToAnOrdinaryRightClick(String click) {
    Station station = new Station("default", "v(crafting_table)", Station.parseClick(click));
    assertEquals(Station.Click.RIGHT, station.getClick());
    assertTrue(station.matchesClick(false));
    assertFalse(station.matchesClick(true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "directory", "yaml"})
  void failedReloadKeepsStationObjectsAndTheirOrder(String failure) throws IOException {
    List<Station> initial = loadInitialDefinitions();
    Path file = directory.resolve("replacement.yml");
    switch (failure) {
      case "directory" -> Files.createDirectory(file);
      case "yaml" -> Files.writeString(file, "stations: [unterminated\n");
      default -> {}
    }

    assertFalse(loader.load(file.toFile()));

    assertEquals(initial, List.copyOf(StationLoader.get()));
    initial.forEach(station -> assertSame(station, StationLoader.getByString(station.getId())));
  }

  @Test
  void currentFormatKeepsConfiguredOrderAndSkipsIncompleteEntries() throws IOException {
    loadInitialDefinitions();
    assertTrue(
        loader.load(
            write(
                    "sections.yml",
                    """
                    stations:
                      first:
                        block: "  v(crafting_table)  "
                        click: shift-right
                      ignored_scalar: metadata
                      ignored_missing:
                        click: right
                      ignored_blank:
                        block: "  "
                      normal:
                        block: v(brewing_stand)
                      sneak:
                        block: v(stonecutter)
                        click: sneak_right
                      unknown:
                        block: v(jukebox)
                        click: left
                    """)
                .toFile()));

    assertEquals(
        List.of("first", "normal", "sneak", "unknown"),
        StationLoader.get().stream().map(Station::getId).toList());
    assertStation("first", "v(crafting_table)", Station.Click.SHIFT_RIGHT);
    assertStation("normal", "v(brewing_stand)", Station.Click.RIGHT);
    assertStation("sneak", "v(stonecutter)", Station.Click.SHIFT_RIGHT);
    assertStation("unknown", "v(jukebox)", Station.Click.RIGHT);
    assertNull(StationLoader.getByString("original"));
  }

  @Test
  void legacyEntriesIgnoreSurroundingWhitespaceAndSkipMissingTokens() throws IOException {
    assertTrue(
        loader.load(
            write(
                    "legacy.yml",
                    """
                    stations:
                      - "  v(fletching_table) forester  "
                      - "v(brewing_stand)    alchemy"
                      - "missing_id"
                      - ""
                      - "   "
                    """)
                .toFile()));

    assertEquals(
        List.of("forester", "alchemy"), StationLoader.get().stream().map(Station::getId).toList());
    assertStation("forester", "v(fletching_table)", Station.Click.RIGHT);
    assertStation("alchemy", "v(brewing_stand)", Station.Click.RIGHT);
  }

  @ParameterizedTest
  @CsvSource({
    "v(CRAFTING_TABLE), SHIFT_RIGHT",
    "v(BLAST_FURNACE), SHIFT_RIGHT",
    "v(STONECUTTER), SHIFT_RIGHT",
    "v(JUKEBOX), SHIFT_RIGHT",
    "v(CARTOGRAPHY_TABLE), SHIFT_RIGHT",
    "iaf(tfmc:MEDICINE_STATION), RIGHT"
  })
  void legacyClickDefaultsDoNotDependOnTheHostLocale(String block, Station.Click expected)
      throws IOException {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      Path file = write("defaults.yml", "stations:\n  - \"" + block + " station\"\n");

      assertTrue(loader.load(file.toFile()));

      assertStation("station", block, expected);
    } finally {
      Locale.setDefault(original);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "stations: {}\n", "stations: []\n"})
  void successfulEmptyLoadRemovesDeletedDefinitions(String content) throws IOException {
    loadInitialDefinitions();

    assertTrue(loader.load(write("empty.yml", content).toFile()));

    assertTrue(StationLoader.get().isEmpty());
    assertNull(StationLoader.getByString("original"));
  }

  private List<Station> loadInitialDefinitions() throws IOException {
    assertTrue(
        loader.load(
            write(
                    "initial.yml",
                    """
                    stations:
                      original:
                        block: v(crafting_table)
                        click: shift_right
                      removed:
                        block: v(brewing_stand)
                        click: right
                    """)
                .toFile()));
    return List.copyOf(StationLoader.get());
  }

  private void assertStation(String id, String block, Station.Click click) {
    Station station = StationLoader.getByString(id);
    assertEquals(id, station.getId());
    assertEquals(block, station.getBlock());
    assertEquals(click, station.getClick());
    assertEquals(click == Station.Click.SHIFT_RIGHT, station.matchesClick(true));
    assertEquals(click == Station.Click.RIGHT, station.matchesClick(false));
  }

  private Path write(String filename, String content) throws IOException {
    return Files.writeString(directory.resolve(filename), content);
  }
}
