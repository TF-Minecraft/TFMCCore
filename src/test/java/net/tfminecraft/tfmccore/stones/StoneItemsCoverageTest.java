package net.tfminecraft.tfmccore.stones;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.stones.StoneItems.Kind;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import net.tfminecraft.tlibs.objects.api.subapi.ItemCreator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.MockedStatic;

class StoneItemsCoverageTest {
  private final StoneItems items = new StoneItems();
  private final TFMCCore plugin = mock(TFMCCore.class);
  private final Logger logger = mock(Logger.class);
  private final ItemAPI api = mock(ItemAPI.class);
  private final ItemChecker checker = mock(ItemChecker.class);
  private final ItemCreator creator = mock(ItemCreator.class);
  private MockedStatic<TLibs> tlibs;
  private MockedStatic<TFMCCore> core;
  private String previousLorePath;
  private String previousNamePath;
  private List<String> previousBlacklist;

  @BeforeEach
  void setUp() {
    previousLorePath = LorestoneConfig.lorestonePath;
    previousNamePath = LorestoneConfig.namestonePath;
    previousBlacklist = LorestoneConfig.blacklist;
    LorestoneConfig.lorestonePath = "m.consumable.lorestone";
    LorestoneConfig.namestonePath = "m.consumable.namestone";
    LorestoneConfig.blacklist = new ArrayList<>(List.of("v.bedrock"));
    when(plugin.getLogger()).thenReturn(logger);
    when(api.getChecker()).thenReturn(checker);
    when(api.getCreator()).thenReturn(creator);
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getItemAPI).thenReturn(api);
    core = mockStatic(TFMCCore.class);
    core.when(TFMCCore::getInstance).thenReturn(plugin);
  }

  @AfterEach
  void tearDown() {
    if (core != null) core.close();
    if (tlibs != null) tlibs.close();
    LorestoneConfig.lorestonePath = previousLorePath;
    LorestoneConfig.namestonePath = previousNamePath;
    LorestoneConfig.blacklist = previousBlacklist;
  }

  @ParameterizedTest
  @CsvSource({"AIR, 1", "DIAMOND, 0"})
  void emptyTemplatesCannotBeReturnedAsSuccessfulStoneItems(Material material, int amount) {
    ItemStack empty = mock(ItemStack.class);
    when(empty.getType()).thenReturn(material);
    when(empty.getAmount()).thenReturn(amount);
    when(empty.isEmpty()).thenReturn(true);
    ItemStack clone = mock(ItemStack.class);
    when(clone.isEmpty()).thenReturn(true);
    when(empty.clone()).thenReturn(clone);
    when(creator.getItemFromPath(LorestoneConfig.lorestonePath)).thenReturn(empty);

    assertNull(items.template(Kind.LORE));

    verify(empty, never()).clone();
    verify(logger).warning("No item found for lorestones config path: m.consumable.lorestone");
  }

  @Test
  void nullAndAirCursorsNeverReachTheItemProvider() {
    assertNull(items.kindOf(null));
    assertNull(items.kindOf(stack(true)));

    verifyNoInteractions(api, checker, creator, logger);
  }

  @Test
  void matchingLorestoneTakesPrecedenceOverTheNamePath() {
    ItemStack stack = stack(false);
    when(checker.checkItemWithPath(stack, LorestoneConfig.lorestonePath)).thenReturn(true);
    when(checker.checkItemWithPath(stack, LorestoneConfig.namestonePath)).thenReturn(true);

    assertSame(Kind.LORE, items.kindOf(stack));

    verify(checker).checkItemWithPath(stack, LorestoneConfig.lorestonePath);
    verify(checker, never()).checkItemWithPath(stack, LorestoneConfig.namestonePath);
  }

  @Test
  void matchingNamestoneIsRecognizedAfterTheLorePathDoesNotMatch() {
    ItemStack stack = stack(false);
    when(checker.checkItemWithPath(stack, LorestoneConfig.namestonePath)).thenReturn(true);

    assertSame(Kind.NAME, items.kindOf(stack));

    verify(checker).checkItemWithPath(stack, LorestoneConfig.lorestonePath);
    verify(checker).checkItemWithPath(stack, LorestoneConfig.namestonePath);
  }

  @Test
  void ordinaryItemsAreNotRecognizedAsStones() {
    ItemStack stack = stack(false);

    assertNull(items.kindOf(stack));

    verify(checker).checkItemWithPath(stack, LorestoneConfig.lorestonePath);
    verify(checker).checkItemWithPath(stack, LorestoneConfig.namestonePath);
    verifyNoInteractions(logger);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void unsetStonePathsAreIgnoredWithoutProviderCalls(String path) {
    LorestoneConfig.lorestonePath = path;
    LorestoneConfig.namestonePath = path;

    assertNull(items.kindOf(stack(false)));

    verifyNoInteractions(api, checker, logger);
  }

  @Test
  void itemCheckingFailureReturnsNoKindAndExplainsTheFailure() {
    ItemStack stack = stack(false);
    when(checker.checkItemWithPath(stack, LorestoneConfig.lorestonePath))
        .thenThrow(new IllegalStateException("item provider unavailable"));

    assertNull(items.kindOf(stack));

    verify(logger)
        .warning("Failed to check item against lorestones stone paths: item provider unavailable");
  }

  @Test
  void nullTargetsAreBlacklistedWithoutProviderCalls() {
    assertTrue(items.isBlacklisted(null));

    verifyNoInteractions(api, checker, logger);
  }

  @Test
  void blacklistSkipsUnsetPathsAndFindsLaterMatchingRules() {
    LorestoneConfig.blacklist = Arrays.asList(null, "", "v.obsidian", "v.bedrock");
    ItemStack stack = stack(false);
    when(checker.checkItemWithPath(stack, "v.bedrock")).thenReturn(true);

    assertTrue(items.isBlacklisted(stack));

    verify(checker).checkItemWithPath(stack, "v.obsidian");
    verify(checker).checkItemWithPath(stack, "v.bedrock");
    verifyNoMoreInteractions(checker);
  }

  @Test
  void unmatchedAndEmptyBlacklistsAllowTheItem() {
    ItemStack stack = stack(false);

    assertFalse(items.isBlacklisted(stack));
    LorestoneConfig.blacklist = List.of();
    assertFalse(items.isBlacklisted(stack));

    verify(checker).checkItemWithPath(stack, "v.bedrock");
    verifyNoMoreInteractions(checker);
  }

  @Test
  void blacklistProviderFailureFailsClosed() {
    tlibs.when(TLibs::getItemAPI).thenThrow(new IllegalStateException("provider offline"));

    assertTrue(items.isBlacklisted(stack(false)));

    verify(logger).warning("Failed to check item against lorestones blacklist: provider offline");
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void templatesResolveTheConfiguredPathAndReturnIndependentClones(Kind kind) {
    String path = kind == Kind.LORE ? LorestoneConfig.lorestonePath : LorestoneConfig.namestonePath;
    ItemStack template = mock(ItemStack.class);
    ItemStack first = mock(ItemStack.class);
    ItemStack second = mock(ItemStack.class);
    when(template.clone()).thenReturn(first, second);
    when(creator.getItemFromPath(path)).thenReturn(template);

    assertSame(first, items.template(kind));
    assertSame(second, items.template(kind));

    assertNotSame(template, first);
    assertNotSame(first, second);
    first.setAmount(5);
    verify(template, never()).setAmount(anyInt());
  }

  @Test
  void unresolvedTemplatesReturnNullWithTheConfiguredPathInTheWarning() {
    assertNull(items.template(Kind.NAME));

    verify(logger).warning("No item found for lorestones config path: m.consumable.namestone");
  }

  @ParameterizedTest
  @NullAndEmptySource
  void unsetTemplatePathsRemainCallerSafe(String path) {
    LorestoneConfig.lorestonePath = path;

    assertNull(items.template(Kind.LORE));

    verify(creator).getItemFromPath(path);
    verify(logger).warning("No item found for lorestones config path: " + path);
  }

  @Test
  void templateProviderFailuresReturnNullAndExplainTheFailure() {
    when(creator.getItemFromPath(LorestoneConfig.lorestonePath))
        .thenThrow(new IllegalArgumentException("invalid configured item"));

    assertNull(items.template(Kind.LORE));

    verify(logger)
        .warning(
            "Failed to resolve lorestones item 'm.consumable.lorestone': invalid configured item");
  }

  @Test
  void unavailablePluginSingletonDoesNotTurnAnItemFailureIntoAnException() {
    core.when(TFMCCore::getInstance).thenReturn(null);

    assertNull(assertDoesNotThrow(() -> items.template(Kind.LORE)));

    verifyNoInteractions(logger);
  }

  @Test
  void deadPlayersReceiveRefundsAtTheirLocationWithoutInventoryInsertion() {
    Player player = mock(Player.class);
    World world = mock(World.class);
    Location location = new Location(world, 4, 70, 8);
    ItemStack refund = mock(ItemStack.class);
    when(player.isDead()).thenReturn(true);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(location);

    items.giveOrDrop(player, refund);

    verify(player, never()).getInventory();
    verify(world).dropItemNaturally(location, refund);
    verifyNoMoreInteractions(world);
  }

  @Test
  void livingPlayersReceiveItemsInTheirInventoryBeforeOverflowIsDropped() {
    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    World world = mock(World.class);
    Location location = new Location(world, 4, 70, 8);
    ItemStack refund = mock(ItemStack.class);
    ItemStack first = mock(ItemStack.class);
    ItemStack second = mock(ItemStack.class);
    when(player.getInventory()).thenReturn(inventory);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(location);
    when(inventory.addItem(refund)).thenReturn(new HashMap<>(Map.of(0, first, 1, second)));

    items.giveOrDrop(player, refund);

    var order = org.mockito.Mockito.inOrder(inventory, world);
    order.verify(inventory).addItem(refund);
    order.verify(world, times(2)).dropItemNaturally(eq(location), any(ItemStack.class));
    verify(world).dropItemNaturally(location, first);
    verify(world).dropItemNaturally(location, second);
    verifyNoMoreInteractions(world);
  }

  @Test
  void successfulInventoryInsertionDoesNotDropAnExtraCopy() {
    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    ItemStack refund = mock(ItemStack.class);
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.addItem(refund)).thenReturn(new HashMap<>());

    items.giveOrDrop(player, refund);

    verify(inventory).addItem(refund);
    verify(player, never()).getWorld();
    verify(player, never()).getLocation();
  }

  @ParameterizedTest
  @NullAndEmptySource
  void absentMessageTemplatesDoNotSendAnything(String message) {
    Player player = mock(Player.class);

    items.msg(player, message);

    verifyNoInteractions(player);
  }

  @Test
  void messagesSubstituteCompletePairsAndTranslateColors() {
    Player player = mock(Player.class);

    items.msg(
        player,
        "&aGave %amount%x to %player%. %left%",
        "%amount%",
        "3",
        "%player%",
        "Ada",
        "%left%");

    verify(player).sendMessage("§aGave 3x to Ada. %left%");
  }

  @Test
  void literalMessagesDoNotNeedPlaceholderArguments() {
    Player player = mock(Player.class);

    items.msg(player, "&eReady");

    verify(player).sendMessage("§eReady");
  }

  private ItemStack stack(boolean air) {
    ItemStack stack = mock(ItemStack.class);
    Material material = mock(Material.class);
    when(material.isAir()).thenReturn(air);
    when(stack.getType()).thenReturn(material);
    return stack;
  }
}
