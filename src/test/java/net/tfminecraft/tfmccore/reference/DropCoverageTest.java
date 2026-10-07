package net.tfminecraft.tfmccore.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.tfminecraft.tfmccore.cache.Cache;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import net.tfminecraft.tlibs.objects.api.subapi.ItemCreator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class DropCoverageTest {
  private final Player player = mock(Player.class);
  private final Block block = mock(Block.class);
  private final World world = mock(World.class);
  private final ItemAPI api = mock(ItemAPI.class);
  private final ItemChecker checker = mock(ItemChecker.class);
  private final ItemCreator creator = mock(ItemCreator.class);
  private final ItemStack tool = mock(ItemStack.class);
  private final List<String> messages = new ArrayList<>();
  private final Location blockLocation = new Location(world, 5, 70, 8);
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<TLibs> tlibs;
  private Locale previousLocale;
  private boolean previousDebug;

  @BeforeEach
  void setUp() {
    // The shared test service supplies stable server-owned enchantment identifiers.
    assertNotNull(Enchantment.FORTUNE);
    previousLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
    previousDebug = Cache.dropsDebug;
    Cache.dropsDebug = true;
    java.util.logging.Logger logger = mock(java.util.logging.Logger.class);
    doAnswer(
            invocation -> {
              messages.add(invocation.getArgument(0));
              return null;
            })
        .when(logger)
        .info(anyString());
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getLogger).thenReturn(logger);
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getItemAPI).thenReturn(api);
    when(api.getChecker()).thenReturn(checker);
    when(api.getCreator()).thenReturn(creator);
    when(block.getType()).thenReturn(Material.STONE);
    when(block.getWorld()).thenReturn(world);
    when(block.getLocation()).thenReturn(blockLocation);
    when(block.getX()).thenReturn(5);
    when(block.getY()).thenReturn(70);
    when(block.getZ()).thenReturn(8);
    when(world.getName()).thenReturn("mining");
  }

  @AfterEach
  void tearDown() {
    if (tlibs != null) tlibs.close();
    if (bukkit != null) bukkit.close();
    Cache.dropsDebug = previousDebug;
    if (previousLocale != null) Locale.setDefault(previousLocale);
  }

  @Test
  void materialNamesRemainValidUnderTurkishLocale() throws InvalidConfigurationException {
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));

    Drop drop =
        drop(
            """
            materials: [iron_ore, diorite]
            """);

    assertTrue(drop.appliesToMaterial(Material.IRON_ORE));
    assertTrue(drop.appliesToMaterial(Material.DIORITE));
    assertTrue(messages.isEmpty(), "Valid identifiers must not produce invalid-material warnings");
  }

  @Test
  void fortuneDoesNotMakeAGuaranteedBoostedDropImpossible() throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: ["STONE(3)"]
            drops: ["v.diamond(1) 3 3"]
            """);
    when(tool.containsEnchantment(Enchantment.FORTUNE)).thenReturn(true);
    when(tool.getEnchantmentLevel(Enchantment.FORTUNE)).thenReturn(1);
    ItemStack reward = reward("v.diamond");

    drop.trigger(player, block, tool);

    verifySpawn(reward, 3);
    assertTrue(messages.stream().anyMatch(message -> message.contains("chance=1.0000")));
  }

  @Test
  void emptyCreatedItemsAreSkippedWithoutPreventingLaterRewards()
      throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: [STONE]
            drops: ["v.air(1) 1 1", "v.diamond(1) 2 2"]
            """);
    ItemStack empty = mock(ItemStack.class);
    when(empty.getType()).thenReturn(Material.AIR);
    when(empty.isEmpty()).thenReturn(true);
    when(creator.getItemFromPath("v.air")).thenReturn(empty);
    ItemStack reward = reward("v.diamond");

    drop.trigger(player, block, null);

    verify(world, never()).dropItem(any(Location.class), same(empty));
    verifySpawn(reward, 2);
    assertTrue(
        messages.stream()
            .anyMatch(message -> message.contains("could not create drop item v.air")));
  }

  @Test
  void applicabilityAndVanillaDropsRespectMaterialsAndPermissions()
      throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: [STONE]
            required_permissions: ["", miner]
            vanilla_drops: false
            """);
    assertEquals("mining", drop.getId());
    assertFalse(drop.appliesTo((Block) null));
    assertFalse(drop.appliesToMaterial(null));
    assertFalse(drop.appliesToMaterial(Material.DIRT));
    assertEquals("material null not in table", drop.skipReason(player, null, tool));
    assertEquals(
        "material DIRT not in table", drop.skipReasonForBroken(player, tool, Material.DIRT));
    when(block.getType()).thenReturn(Material.DIRT);
    assertEquals("material DIRT not in table", drop.skipReason(player, block, tool));
    when(block.getType()).thenReturn(Material.STONE);
    assertTrue(drop.appliesTo(block));
    assertFalse(drop.appliesTo(player, block, tool));
    assertEquals("missing permission miner", drop.skipReason(player, block, tool));
    assertTrue(drop.hasVanillaDrops(player, block, tool));
    when(player.hasPermission("miner")).thenReturn(true);
    assertTrue(drop.appliesTo(player, block, tool));
    assertFalse(drop.hasVanillaDrops(player, block, tool));
    assertFalse(drop.keepsVanillaDrops());
  }

  @Test
  void legacyAliasesAndDefaultVanillaPolicyStillApply() throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            material: [STONE]
            required-permissions: [miner]
            vanilla-drops: false
            tool: none
            """);
    assertEquals(
        "missing permission miner", drop.skipReasonForBroken(player, null, Material.STONE));
    when(player.hasPermission("miner")).thenReturn(true);
    assertNull(drop.skipReasonForBroken(player, null, Material.STONE));
    assertFalse(drop.keepsVanillaDrops());
    assertTrue(drop("").keepsVanillaDrops());
  }

  @ParameterizedTest
  @CsvSource({
    "hammer, m.tools.hammer",
    "tools.hammer, m.tools.hammer",
    "m.tools.hammer, m.tools.hammer",
    "v.IRON_PICKAXE, v.IRON_PICKAXE",
    "ia.tfmc:hammer, ia.tfmc:hammer",
    "m.tools.hammer(2), m.tools.hammer"
  })
  void supportedToolPathsAreNormalizedBeforeMatching(String configured, String expected) {
    YamlConfiguration config = new YamlConfiguration();
    config.set("materials", List.of("STONE"));
    config.set("tools", List.of(configured));
    Drop drop = new Drop("tool-check", config);
    assertTrue(
        drop.skipReasonForBroken(player, tool, Material.STONE).startsWith("tool did not match"));
    when(checker.checkItemWithPath(tool, expected)).thenReturn(true);

    assertNull(drop.skipReasonForBroken(player, tool, Material.STONE));
  }

  @Test
  void malformedOptionalEntriesAreLoggedAndValidMaterialsRemainUsable()
      throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: [NOT_A_MATERIAL, AIR, "DIRT(bad-material)", STONE]
            mults: [no_multiplier, "boost(bad-permission)"]
            tools: [none, "", "   ", "  (2)", "hammer(bad-tool)"]
            """);

    assertTrue(drop.appliesToMaterial(Material.STONE));
    assertFalse(drop.appliesToMaterial(Material.AIR));
    assertFalse(drop.appliesToMaterial(Material.DIRT));
    assertNull(drop.skipReasonForBroken(player, null, Material.STONE));
    assertTrue(messages.stream().anyMatch(message -> message.contains("NOT_A_MATERIAL")));
    for (String invalid : List.of("bad-material", "bad-permission", "bad-tool")) {
      assertTrue(
          messages.stream().anyMatch(message -> message.contains("could not parse " + invalid)));
    }
    verifyNoInteractions(checker);
  }

  @Test
  void ineligibleTriggersDoNotCreateOrSpawnRewards() throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: [STONE]
            required_permissions: [miner]
            drops: ["v.diamond(1) 1 1"]
            """);

    drop.trigger(player, block, tool);

    verifyNoInteractions(creator);
    verify(world, never()).dropItem(any(Location.class), any(ItemStack.class));
    assertTrue(
        messages.stream()
            .anyMatch(message -> message.contains("skip trigger: missing permission miner")));
  }

  @Test
  void blockToolAndGrantedPermissionMultipliersCombineWithoutUnmatchedBonuses()
      throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: ["STONE(2)"]
            tools: ["m.tools.hammer(2)", "m.tools.other(100)"]
            mults: ["boost(2)", "not_granted(100)"]
            drops: ["v.diamond(0.125) 4 4"]
            """);
    when(checker.checkItemWithPath(tool, "m.tools.hammer")).thenReturn(true);
    when(player.hasPermission("boost")).thenReturn(true);
    ItemStack reward = reward("v.diamond");

    drop.trigger(player, block, tool);

    verifySpawn(reward, 4);
    assertTrue(messages.stream().anyMatch(message -> message.contains("chance=1.0000")));
    verify(checker, org.mockito.Mockito.atLeastOnce()).checkItemWithPath(tool, "m.tools.other");
    verify(player).hasPermission("not_granted");
  }

  @Test
  void singularToolConfigurationUsesItsBonus() throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: [STONE]
            tool: m.tools.hammer(2)
            drops: ["v.diamond(0.5) 1 1"]
            """);
    when(checker.checkItemWithPath(tool, "m.tools.hammer")).thenReturn(true);
    ItemStack reward = reward("v.diamond");

    drop.trigger(player, block, tool);

    verifySpawn(reward, 1);
  }

  @Test
  void delayedDropsUseTheOriginalMaterialAndDoNotMutateTheBlockLocation()
      throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: [STONE]
            drops: ["v.diamond(1) 2 2"]
            """);
    when(block.getType()).thenReturn(Material.AIR);
    ItemStack reward = reward("v.diamond");

    drop.trigger(player, block, null, Material.STONE);

    verifySpawn(reward, 2);
    assertEquals(new Location(world, 5, 70, 8), blockLocation);
  }

  @Test
  void unresolvedItemsAreLoggedAndLaterEntriesStillDrop() throws InvalidConfigurationException {
    Drop drop =
        drop(
            """
            materials: [STONE]
            drops: ["v.missing(1) 1 1", "v.diamond(1) 1 1"]
            """);
    ItemStack reward = reward("v.diamond");

    drop.trigger(player, block, null);

    verifySpawn(reward, 1);
    verify(creator).getItemFromPath("v.missing");
    assertTrue(
        messages.stream()
            .anyMatch(message -> message.contains("could not create drop item v.missing")));
  }

  private Drop drop(String yaml) throws InvalidConfigurationException {
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString(yaml);
    return new Drop("mining", config);
  }

  private ItemStack reward(String path) {
    ItemStack reward = mock(ItemStack.class);
    when(reward.getType()).thenReturn(Material.DIAMOND);
    when(creator.getItemFromPath(path)).thenReturn(reward);
    return reward;
  }

  private void verifySpawn(ItemStack reward, int amount) {
    ArgumentCaptor<Location> location = ArgumentCaptor.forClass(Location.class);
    verify(world).dropItem(location.capture(), same(reward));
    verify(reward).setAmount(amount);
    assertEquals(new Location(world, 5.5, 70.2, 8.5), location.getValue());
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("spawned v.diamond x" + amount)));
  }
}
