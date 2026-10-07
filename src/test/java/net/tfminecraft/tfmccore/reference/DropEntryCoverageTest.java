package net.tfminecraft.tfmccore.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DropEntryCoverageTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 5, Integer.MAX_VALUE})
  void oneExplicitQuantityAlwaysGivesThatExactAmount(int amount) {
    DropEntry entry = new DropEntry("v.DIAMOND(1.0) " + amount);

    assertEquals("v.DIAMOND", entry.getItem());
    assertEquals(1.0, entry.getChance());
    assertEquals(amount, entry.getMin());
    assertEquals(amount, entry.getMax());
    for (int i = 0; i < 10; i++) assertEquals(amount, entry.getAmount());
  }

  @Test
  void surroundingAndSeparatingWhitespaceDoesNotBecomePartOfTheItem() {
    DropEntry entry = new DropEntry("  \tv.DIAMOND(0.5)  5\t8  ");

    assertEquals("v.DIAMOND", entry.getItem());
    assertEquals(0.5, entry.getChance());
    assertEquals(5, entry.getMin());
    assertEquals(8, entry.getMax());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", " \t\n "})
  void blankEntriesAreRejectedExplicitly(String value) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new DropEntry(value));

    assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("drop entry"));
  }

  @ParameterizedTest
  @CsvSource({"1, 2", "2, 5", "2147483646, 2147483647", "1, 2147483647"})
  void explicitRangesStayWithinBothInclusiveBounds(int minimum, int maximum) {
    DropEntry entry = new DropEntry("v.DIAMOND(0.5) " + minimum + " " + maximum);

    assertEquals(minimum, entry.getMin());
    assertEquals(maximum, entry.getMax());
    for (int i = 0; i < 64; i++) {
      int amount = entry.getAmount();
      assertTrue(
          amount >= minimum && amount <= maximum,
          "Amount escaped configured positive range: " + amount);
    }
    assertEquals(minimum, new DropEntry("v.DIAMOND(0.5) " + minimum + " " + minimum).getAmount());
    assertEquals(maximum, new DropEntry("v.DIAMOND(0.5) " + maximum + " " + maximum).getAmount());
  }

  @Test
  void omittedQuantityAndOmittedChanceRetainTheirDefaults() {
    DropEntry entry = new DropEntry("m.materials.stone_core");

    assertEquals("m.materials.stone_core", entry.getItem());
    assertEquals(0.0, entry.getChance());
    assertEquals(1, entry.getMin());
    assertEquals(1, entry.getMax());
    assertEquals(1, entry.getAmount());
  }

  @ParameterizedTest
  @CsvSource({"0, 0.0", "0.125, 0.125", "1, 1.0", "1e-3, 0.001"})
  void validFractionalChancesKeepTheDefaultQuantity(String chance, double expected) {
    DropEntry entry = new DropEntry("ia.tfmc:gem(" + chance + ")");

    assertEquals("ia.tfmc:gem", entry.getItem());
    assertEquals(expected, entry.getChance());
    assertEquals(1, entry.getAmount());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "v.DIAMOND(1) 0", "v.DIAMOND(1) -1", "v.DIAMOND(1) 1 0",
        "v.DIAMOND(1) 3 2", "v.DIAMOND(1) 1 -3", "v.DIAMOND(1) five",
        "v.DIAMOND(1) 1 two", "v.DIAMOND(1) 2147483648", "v.DIAMOND(1) 1 2147483648",
        "v.DIAMOND(1) 1 2 extra"
      })
  void invalidQuantitiesDoNotSilentlyBecomeRandomOrDefaultRewards(String value) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new DropEntry(value));

    assertTrue(error.getMessage().contains(value), "Failure must identify the rejected entry");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(0.5)",
        "v.DIAMOND(",
        "v.DIAMOND()",
        "v.DIAMOND(0.5",
        "v.DIAMOND0.5)",
        "v.DIAMOND(0.5))",
        "v.DIAMOND(0.5)(0.2)",
        "v.DIAMOND(NaN)",
        "v.DIAMOND(Infinity)",
        "v.DIAMOND(-Infinity)",
        "v.DIAMOND(1.1)",
        "v.DIAMOND(-0.1)",
        "v.DIAMOND(nonsense)",
        "v.DIAMOND(1e9999)"
      })
  void invalidItemOrChanceSyntaxRejectsTheEntry(String value) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new DropEntry(value));

    assertTrue(error.getMessage().contains(value), "Failure must identify the rejected entry");
  }

  @Test
  void everyBundledDropEntryStillLoadsWithItsDefaultSingleReward() throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    try (var stream = getClass().getResourceAsStream("/drops.yml")) {
      assertNotNull(stream);
      config.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    var root = config.getConfigurationSection("drops");
    assertNotNull(root);
    int checked = 0;
    for (String id : root.getKeys(false)) {
      for (String value : root.getStringList(id + ".drops")) {
        DropEntry entry = new DropEntry(value);
        assertTrue(entry.getChance() >= 0.0 && entry.getChance() <= 1.0);
        assertTrue(!entry.getItem().isBlank());
        assertEquals(1, entry.getMin());
        assertEquals(1, entry.getMax());
        assertEquals(1, entry.getAmount());
        checked++;
      }
    }
    assertTrue(checked > 0, "Bundled drop definitions must actually be exercised");
  }
}
