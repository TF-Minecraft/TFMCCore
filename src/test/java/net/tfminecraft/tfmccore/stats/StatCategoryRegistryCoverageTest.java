package net.tfminecraft.tfmccore.stats;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StatCategoryRegistryCoverageTest {
  private List<StatCategory> backingCategories;
  private List<StatCategory> previousCategories;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void isolateRegistry() throws Exception {
    var field = StatCategoryRegistry.class.getDeclaredField("categories");
    field.setAccessible(true);
    backingCategories = (List<StatCategory>) field.get(null);
    previousCategories = new ArrayList<>(backingCategories);
    backingCategories.clear();
  }

  @AfterEach
  void restoreRegistry() {
    backingCategories.clear();
    backingCategories.addAll(previousCategories);
  }

  @Test
  void registrationIgnoresNullAndDuplicateIdsWhileKeepingOriginalOrderAndInstances() {
    StatCategory first = category("skills");
    StatCategory duplicate = category("SKILLS");
    StatCategory second = category("vehicles");
    StatCategoryRegistry.register(null);
    assertEquals(List.of(), StatCategoryRegistry.getCategories());
    StatCategoryRegistry.register(first);
    StatCategoryRegistry.register(first);
    StatCategoryRegistry.register(duplicate);
    StatCategoryRegistry.register(second);

    assertEquals(List.of("skills", "vehicles"), StatCategoryRegistry.getCategoryIds());
    assertEquals(List.of(first, second), StatCategoryRegistry.getCategories());
    assertSame(first, StatCategoryRegistry.getCategories().getFirst());
  }

  @Test
  void categoryIdsAreDetachedSnapshotsAndCategoriesCannotBeModifiedThroughThePublicView() {
    StatCategory first = category("skills");
    StatCategory second = category("vehicles");
    StatCategoryRegistry.register(first);
    List<String> ids = StatCategoryRegistry.getCategoryIds();
    List<StatCategory> categories = StatCategoryRegistry.getCategories();
    assertThrows(UnsupportedOperationException.class, () -> categories.add(second));
    assertThrows(UnsupportedOperationException.class, () -> categories.remove(first));
    ids.clear();

    assertEquals(List.of("skills"), StatCategoryRegistry.getCategoryIds());
    assertEquals(List.of(first), StatCategoryRegistry.getCategories());
    StatCategoryRegistry.register(second);
    assertEquals(List.of(), ids, "An ID snapshot cannot change after it is returned");
    assertEquals(List.of(first, second), categories, "The category view follows registrations");
  }

  @Test
  void registerAllDelegatesToEachAcceptedCategoryInOrderUsingTheSuppliedPlugin() {
    Plugin plugin = mock(Plugin.class);
    StatCategoryRegistry.registerAll(plugin);
    verifyNoInteractions(plugin);
    StatCategory first = category("skills");
    StatCategory duplicate = category("SKILLS");
    StatCategory second = category("vehicles");
    StatCategoryRegistry.register(first);
    StatCategoryRegistry.register(duplicate);
    StatCategoryRegistry.register(second);

    StatCategoryRegistry.registerAll(plugin);

    var order = inOrder(first, second);
    order.verify(first).register(plugin);
    order.verify(second).register(plugin);
    verify(duplicate, never()).register(any());
    verify(first, times(1)).register(plugin);
    verify(second, times(1)).register(plugin);
  }

  static Stream<Arguments> labels() {
    return Stream.of(
        Arguments.of(null, ""),
        Arguments.of("", ""),
        Arguments.of(" \t\n", ""),
        Arguments.of("___", ""),
        Arguments.of("x", "X"),
        Arguments.of("__a__B_c__", "A B C"),
        Arguments.of("DaMAGE_TAKEN", "Damage Taken"),
        Arguments.of("player123_stat2", "Player123 Stat2"),
        Arguments.of("123_STEPS", "123 Steps"));
  }

  @ParameterizedTest
  @MethodSource("labels")
  void labelsHandleEmptyKeysRepeatedSeparatorsAndMixedCase(String key, String label) {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.ROOT);
      assertEquals(label, StatLabelFormatter.format(key));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void categoryTitlesUseTheReadableLabelAndStatsSuffix() {
    assertEquals(
        "Advanced Crafting stats:", StatLabelFormatter.formatCategoryTitle("advanced_crafting"));
    assertEquals("Vehicles stats:", StatLabelFormatter.formatCategoryTitle("VEHICLES"));
    assertEquals("A B stats:", StatLabelFormatter.formatCategoryTitle("__a__b__"));
  }

  private StatCategory category(String id) {
    StatCategory category = mock(StatCategory.class);
    when(category.getId()).thenReturn(id);
    return category;
  }
}
