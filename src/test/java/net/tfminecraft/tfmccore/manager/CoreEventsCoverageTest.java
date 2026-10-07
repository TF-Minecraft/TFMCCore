package net.tfminecraft.tfmccore.manager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.world.WeatheringCopperState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.tfminecraft.tfmccore.cache.Cache;
import net.tfminecraft.tfmccore.commands.SilentPermissionCommand;
import net.tfminecraft.tfmccore.golem.GolemListener;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.armour.ArmorEquipEvent;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class CoreEventsCoverageTest {
  private static final UUID PLAYER_ID = UUID.fromString("93db1bc5-e9ef-48d9-bc1d-eaf8394571bf");

  private record MaterialTag(NamespacedKey key, Set<Material> values) implements Tag<Material> {
    @Override
    public NamespacedKey getKey() {
      return key;
    }

    @Override
    public Set<Material> getValues() {
      return values;
    }

    @Override
    public boolean isTagged(Material material) {
      return values.contains(material);
    }
  }

  @BeforeAll
  static void bootstrapVanillaAxeTag() throws Exception {
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit
          .when(() -> Bukkit.getTag(anyString(), any(NamespacedKey.class), eq(Material.class)))
          .thenAnswer(
              call ->
                  new MaterialTag(
                      call.getArgument(1),
                      ((NamespacedKey) call.getArgument(1)).getKey().equals("axes")
                          ? Set.of(
                              Material.WOODEN_AXE,
                              Material.STONE_AXE,
                              Material.IRON_AXE,
                              Material.GOLDEN_AXE,
                              Material.DIAMOND_AXE,
                              Material.NETHERITE_AXE,
                              Material.COPPER_AXE)
                          : Set.of()));
      Class.forName("org.bukkit.Tag");
    }
    assertNotNull(Tag.ITEMS_AXES, "The vanilla axe tag must be available to the event listener");
  }

  private final CoreManager manager = new CoreManager();
  private final GolemListener golems = new GolemListener();
  private final SilentPermissionCommand permissions = new SilentPermissionCommand();
  private Player player;
  private PlayerInventory inventory;
  private ItemChecker checker;
  private Logger logger;
  private PluginManager plugins;
  private ServicesManager services;
  private Command command;
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<TLibs> tlibs;
  private boolean boneMeal, brewing, enchanting, shields, archery, scrape;
  private int armourTime;
  private List<Material> blockedCrafts, blockedConsume;

  @BeforeEach
  void setup() {
    boneMeal = Cache.allowBoneMeal;
    brewing = Cache.allowBrewing;
    enchanting = Cache.allowEnchanting;
    shields = Cache.limitShields;
    archery = Cache.horseArchery;
    scrape = Cache.preventGolemScrape;
    armourTime = Cache.armourTime;
    blockedCrafts = new ArrayList<>(Cache.blockedCrafts);
    blockedConsume = new ArrayList<>(Cache.blockedConsume);
    Cache.allowBoneMeal = Cache.allowBrewing = Cache.allowEnchanting = Cache.horseArchery = false;
    Cache.preventGolemScrape = true;
    Cache.blockedCrafts.clear();
    Cache.blockedConsume.clear();
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(PLAYER_ID);
    when(player.getName()).thenReturn("Alice");
    inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    doReturn(stack(Material.AIR)).when(inventory).getItemInMainHand();
    doReturn(stack(Material.AIR)).when(inventory).getItemInOffHand();
    ItemAPI items = mock(ItemAPI.class);
    checker = mock(ItemChecker.class);
    when(items.getChecker()).thenReturn(checker);
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getItemAPI).thenReturn(items);
    logger = mock(Logger.class);
    plugins = mock(PluginManager.class);
    services = mock(ServicesManager.class);
    command = mock(Command.class);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getLogger).thenReturn(logger);
    bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
    bukkit.when(Bukkit::getServicesManager).thenReturn(services);
  }

  @AfterEach
  void cleanup() {
    bukkit.close();
    tlibs.close();
    Cache.allowBoneMeal = boneMeal;
    Cache.allowBrewing = brewing;
    Cache.allowEnchanting = enchanting;
    Cache.limitShields = shields;
    Cache.horseArchery = archery;
    Cache.preventGolemScrape = scrape;
    Cache.armourTime = armourTime;
    Cache.blockedCrafts.clear();
    Cache.blockedCrafts.addAll(blockedCrafts);
    Cache.blockedConsume.clear();
    Cache.blockedConsume.addAll(blockedConsume);
  }

  @Test
  void offhandBoneMealCannotBypassTheGrowthRestriction() {
    ItemStack meal = stack(Material.BONE_MEAL);
    when(checker.checkItemWithPath(meal, "v.bone_meal")).thenReturn(true);
    when(inventory.getItemInOffHand()).thenReturn(meal);
    PlayerInteractEvent event =
        interact(Action.RIGHT_CLICK_BLOCK, Material.WHEAT, EquipmentSlot.OFF_HAND, meal);
    when(event.getClickedBlock().getBlockData()).thenReturn(mock(Ageable.class));

    manager.preventBoneMeal(event);

    assertTrue(event.isCancelled(), "The configured growth restriction applies to the used hand");
    assertEquals(Material.BONE_MEAL, inventory.getItemInOffHand().getType());
  }

  @Test
  void boneMealRestrictionPreservesNonGrowthActionsAndCanBeDisabled() {
    ItemStack meal = stack(Material.BONE_MEAL);
    when(inventory.getItemInMainHand()).thenReturn(meal);
    when(checker.checkItemWithPath(meal, "v.bone_meal")).thenReturn(true);
    PlayerInteractEvent crop =
        interact(Action.RIGHT_CLICK_BLOCK, Material.WHEAT, EquipmentSlot.HAND, meal);
    when(crop.getClickedBlock().getBlockData()).thenReturn(mock(Ageable.class));
    Cache.allowBoneMeal = true;
    manager.preventBoneMeal(crop);
    assertFalse(crop.isCancelled());
    Cache.allowBoneMeal = false;
    PlayerInteractEvent air = interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND, meal);
    manager.preventBoneMeal(air);
    assertFalse(air.isCancelled());
    PlayerInteractEvent stone =
        interact(Action.RIGHT_CLICK_BLOCK, Material.STONE, EquipmentSlot.HAND, meal);
    manager.preventBoneMeal(stone);
    assertFalse(stone.isCancelled());
    when(checker.checkItemWithPath(meal, "v.bone_meal")).thenReturn(false);
    manager.preventBoneMeal(crop);
    assertFalse(crop.isCancelled());
    when(checker.checkItemWithPath(meal, "v.bone_meal")).thenReturn(true);
    manager.preventBoneMeal(crop);
    assertTrue(crop.isCancelled());
  }

  @Test
  void equippingArmorAppliesTheConfiguredWeaknessWithoutParticles() {
    Cache.armourTime = 4;
    ArmorEquipEvent event = mock(ArmorEquipEvent.class);
    when(event.getPlayer()).thenReturn(player);
    manager.equipArmor(event);
    ArgumentCaptor<PotionEffect> effect = ArgumentCaptor.forClass(PotionEffect.class);
    verify(player).addPotionEffect(effect.capture());
    assertSame(PotionEffectType.WEAKNESS, effect.getValue().getType());
    assertEquals(80, effect.getValue().getDuration());
    assertEquals(2, effect.getValue().getAmplifier());
    assertFalse(effect.getValue().isAmbient());
    assertFalse(effect.getValue().hasParticles());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void brewingRespectsTheConfiguredToggle(boolean allowed) {
    Cache.allowBrewing = allowed;
    BrewEvent event = mock(BrewEvent.class);
    manager.brewEvent(event);
    verify(event, times(allowed ? 0 : 1)).setCancelled(true);
  }

  @Test
  void enchantingOnlyBlocksOpeningAnEnchantingTableWhenDisabled() {
    ItemStack empty = stack(Material.AIR);
    PlayerInteractEvent left =
        interact(Action.LEFT_CLICK_BLOCK, Material.ENCHANTING_TABLE, EquipmentSlot.HAND, empty);
    PlayerInteractEvent other =
        interact(Action.RIGHT_CLICK_BLOCK, Material.STONE, EquipmentSlot.HAND, empty);
    PlayerInteractEvent table =
        interact(Action.RIGHT_CLICK_BLOCK, Material.ENCHANTING_TABLE, EquipmentSlot.HAND, empty);
    manager.enchantEvent(left);
    manager.enchantEvent(other);
    manager.enchantEvent(table);
    assertFalse(left.isCancelled());
    assertFalse(other.isCancelled());
    assertTrue(table.isCancelled());
    Cache.allowEnchanting = true;
    PlayerInteractEvent allowed =
        interact(Action.RIGHT_CLICK_BLOCK, Material.ENCHANTING_TABLE, EquipmentSlot.HAND, empty);
    manager.enchantEvent(allowed);
    assertFalse(allowed.isCancelled());
  }

  @Test
  void blockedCraftsClearTheResultAndAllowedOrAbsentResultsRemainUntouched() {
    Cache.blockedCrafts.add(Material.BREAD);
    CraftingInventory crafting = mock(CraftingInventory.class);
    PrepareItemCraftEvent event = mock(PrepareItemCraftEvent.class);
    when(event.getInventory()).thenReturn(crafting);
    manager.craftEvent(event);
    doReturn(stack(Material.STICK)).when(crafting).getResult();
    manager.craftEvent(event);
    verify(crafting, never()).setResult(any());
    doReturn(stack(Material.BREAD)).when(crafting).getResult();
    try (var created =
        mockConstruction(
            ItemStack.class,
            (item, context) -> {
              assertEquals(List.of(Material.AIR, 1), context.arguments());
              when(item.getType()).thenReturn(Material.AIR);
              when(item.getAmount()).thenReturn(1);
            })) {
      manager.craftEvent(event);
      assertEquals(1, created.constructed().size());
      verify(crafting).setResult(created.constructed().getFirst());
      assertEquals(Material.AIR, created.constructed().getFirst().getType());
    }
  }

  @Test
  void blockedConsumptionExplainsTheDenialAndAllowedFoodIsUnchanged() {
    Cache.blockedConsume.add(Material.POTION);
    PlayerItemConsumeEvent allowed = mock(PlayerItemConsumeEvent.class);
    doReturn(stack(Material.BREAD)).when(allowed).getItem();
    when(allowed.getPlayer()).thenReturn(player);
    manager.blockConsume(allowed);
    verify(allowed, never()).setCancelled(anyBoolean());
    verify(player, never()).sendMessage(anyString());
    PlayerItemConsumeEvent blocked = mock(PlayerItemConsumeEvent.class);
    doReturn(stack(Material.POTION)).when(blocked).getItem();
    when(blocked.getPlayer()).thenReturn(player);
    manager.blockConsume(blocked);
    verify(blocked).setCancelled(true);
    verify(player).sendMessage("§cYou cannot eat or drink that!");
  }

  @Test
  void mountedArcheryRestrictionOnlyAffectsMountedPlayersWhenEnabled() {
    EntityShootBowEvent mob = mock(EntityShootBowEvent.class);
    when(mob.getEntity()).thenReturn(mock(Skeleton.class));
    manager.stopHorseArcher(mob);
    verify(mob, never()).setCancelled(anyBoolean());
    EntityShootBowEvent event = mock(EntityShootBowEvent.class);
    when(event.getEntity()).thenReturn(player);
    manager.stopHorseArcher(event);
    verify(event, never()).setCancelled(anyBoolean());
    when(player.isInsideVehicle()).thenReturn(true);
    manager.stopHorseArcher(event);
    verify(event).setCancelled(true);
    Cache.horseArchery = true;
    clearInvocations(event);
    manager.stopHorseArcher(event);
    verify(event, never()).setCancelled(anyBoolean());
  }

  @Test
  void shieldDamageUsesTheGenericBuilderAndPreservesProjectileAndAttacker() {
    Cache.limitShields = true;
    when(player.isBlocking()).thenReturn(true);
    Player attacker = mock(Player.class);
    Arrow arrow = mock(Arrow.class);
    DamageSource hit = mock(DamageSource.class);
    when(hit.getCausingEntity()).thenReturn(attacker);
    when(hit.getDirectEntity()).thenReturn(arrow);
    DamageSource piercing = mock(DamageSource.class);
    DamageSource.Builder builder = mock(DamageSource.Builder.class, RETURNS_SELF);
    when(builder.build()).thenReturn(piercing);
    EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
    when(event.getEntity()).thenReturn(player);
    when(event.getDamageSource()).thenReturn(hit);
    when(event.getDamager()).thenReturn(arrow);
    when(event.getDamage()).thenReturn(6.0);
    try (var sources = mockStatic(DamageSource.class)) {
      sources.when(() -> DamageSource.builder(DamageType.GENERIC)).thenReturn(builder);
      manager.blockShield(event);
      sources.verify(() -> DamageSource.builder(DamageType.GENERIC));
    }
    verify(event).setCancelled(true);
    verify(builder).withDirectEntity(arrow);
    verify(builder).withCausingEntity(attacker);
    verify(player).damage(6.0, piercing);
  }

  @ParameterizedTest
  @EnumSource(
      value = Material.class,
      names = {
        "COPPER_GOLEM_STATUE",
        "EXPOSED_COPPER_GOLEM_STATUE",
        "WEATHERED_COPPER_GOLEM_STATUE",
        "OXIDIZED_COPPER_GOLEM_STATUE",
        "WAXED_COPPER_GOLEM_STATUE",
        "WAXED_EXPOSED_COPPER_GOLEM_STATUE",
        "WAXED_WEATHERED_COPPER_GOLEM_STATUE",
        "WAXED_OXIDIZED_COPPER_GOLEM_STATUE"
      })
  void onlyAnUnweatheredUnwaxedStatueScrapeThatSpawnsAGolemIsBlocked(Material statue) {
    ItemStack axe = stack(Material.IRON_AXE);
    when(inventory.getItemInMainHand()).thenReturn(axe);
    PlayerInteractEvent event = interact(Action.RIGHT_CLICK_BLOCK, statue, EquipmentSlot.HAND, axe);
    golems.onStatueScrape(event);
    assertEquals(statue == Material.COPPER_GOLEM_STATUE, event.isCancelled());
    verify(logger).info(contains("block=" + statue));
  }

  @Test
  void statueScrapingUsesTheActualHandAndPreservesOtherInteractions() {
    ItemStack axe = stack(Material.DIAMOND_AXE);
    ItemStack empty = stack(Material.AIR);
    when(inventory.getItemInOffHand()).thenReturn(axe);
    PlayerInteractEvent offhand =
        interact(
            Action.RIGHT_CLICK_BLOCK, Material.COPPER_GOLEM_STATUE, EquipmentSlot.OFF_HAND, axe);
    golems.onStatueScrape(offhand);
    assertTrue(offhand.isCancelled());
    PlayerInteractEvent main =
        interact(Action.RIGHT_CLICK_BLOCK, Material.COPPER_GOLEM_STATUE, EquipmentSlot.HAND, empty);
    golems.onStatueScrape(main);
    assertFalse(main.isCancelled());
    Cache.preventGolemScrape = false;
    PlayerInteractEvent permitted =
        interact(
            Action.RIGHT_CLICK_BLOCK, Material.COPPER_GOLEM_STATUE, EquipmentSlot.OFF_HAND, axe);
    golems.onStatueScrape(permitted);
    assertFalse(permitted.isCancelled());
    PlayerInteractEvent cancelled =
        interact(
            Action.RIGHT_CLICK_BLOCK, Material.COPPER_GOLEM_STATUE, EquipmentSlot.OFF_HAND, axe);
    cancelled.setCancelled(true);
    golems.onStatueScrape(cancelled);
    assertTrue(
        cancelled.isCancelled(), "A disabled restriction must not undo another listener's denial");
    PlayerInteractEvent air = interact(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND, empty);
    PlayerInteractEvent absent =
        interact(Action.RIGHT_CLICK_BLOCK, null, EquipmentSlot.HAND, empty);
    PlayerInteractEvent stone =
        interact(Action.RIGHT_CLICK_BLOCK, Material.STONE, EquipmentSlot.HAND, empty);
    golems.onStatueScrape(air);
    golems.onStatueScrape(absent);
    golems.onStatueScrape(stone);
    assertFalse(air.isCancelled());
    assertFalse(absent.isCancelled());
    assertFalse(stone.isCancelled());
  }

  @Test
  void golemEntityInteractionOnlyLogsWithoutChangingTheExistingCancellation() {
    PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
    when(event.getRightClicked()).thenReturn(player);
    golems.onGolemEntityInteract(event);
    verifyNoInteractions(logger);
    CopperGolem golem = mock(CopperGolem.class);
    when(golem.getWeatheringState()).thenReturn(WeatheringCopperState.EXPOSED);
    when(event.getRightClicked()).thenReturn(golem);
    when(event.getPlayer()).thenReturn(player);
    when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
    when(event.isCancelled()).thenReturn(true);
    golems.onGolemEntityInteract(event);
    verify(logger).info(contains("weather=EXPOSED item=AIR axe=false cancelled=true"));
    verify(event, never()).setCancelled(anyBoolean());
  }

  @Test
  void reanimationIsBlockedButOtherSpawnsAndDisabledRestrictionsArePreserved() {
    CreatureSpawnEvent unrelated = mock(CreatureSpawnEvent.class);
    when(unrelated.getEntity()).thenReturn(mock(Skeleton.class));
    golems.onCopperGolemSpawn(unrelated);
    verify(unrelated, never()).setCancelled(anyBoolean());
    CreatureSpawnEvent reanimated = spawn(CreatureSpawnEvent.SpawnReason.REANIMATE);
    golems.onCopperGolemSpawn(reanimated);
    verify(reanimated).setCancelled(true);
    verify(logger).info(contains("loc=test-world 12,64,-3"));
    CreatureSpawnEvent natural = spawn(CreatureSpawnEvent.SpawnReason.NATURAL);
    golems.onCopperGolemSpawn(natural);
    verify(natural, never()).setCancelled(anyBoolean());
    Cache.preventGolemScrape = false;
    CreatureSpawnEvent allowed = spawn(CreatureSpawnEvent.SpawnReason.REANIMATE);
    golems.onCopperGolemSpawn(allowed);
    verify(allowed, never()).setCancelled(anyBoolean());
  }

  @Test
  void silentPermissionsRejectUnauthorizedOrMalformedCommandsWithoutMessagesOrLookups() {
    CommandSender sender = mock(CommandSender.class);
    assertTrue(invoke(sender, "Alice", "example.node", "true"));
    when(sender.hasPermission("tfmccore.silentpermission")).thenReturn(true);
    assertTrue(invoke(sender, "Alice", "example.node"));
    assertTrue(invoke(sender, "Alice", "example.node", "maybe"));
    assertTrue(invoke(sender, "Alice", "  ", "true"));
    verifyNoInteractions(plugins, services);
    verify(sender, never()).sendMessage(anyString());
  }

  @Test
  void missingLuckPermsPluginOrServiceLeavesTheCommandSilent() {
    when(player.hasPermission("tfmccore.silentpermission")).thenReturn(true);
    assertTrue(invoke(player, "Alice", "example.node", "true"));
    verifyNoInteractions(services);
    when(plugins.getPlugin("LuckPerms")).thenReturn(mock(Plugin.class));
    assertTrue(invoke(player, "Alice", "example.node", "true"));
    verify(services).getRegistration(LuckPerms.class);
    verify(player, never()).sendMessage(anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "YES", "1", "false", "NO", "0"})
  void booleanAliasesReplaceOnlyMatchingGlobalPermissionsAfterTheUserCallback(String raw) {
    PermissionFixture fixture = luckPerms();
    bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(player);
    PermissionNode global = permission("EXAMPLE.NODE", true, true);
    PermissionNode contextual = permission("example.node", true, false);
    PermissionNode other = permission("other.node", true, true);
    InheritanceNode group = mock(InheritanceNode.class);
    fixture.nodes.addAll(List.of(global, contextual, other, group));
    assertTrue(invoke(player, "Alice", "example.node", raw));
    assertEquals(List.of(global, contextual, other, group), fixture.nodes);
    assertEquals(1, fixture.modifications.size());
    verify(fixture.users).modifyUser(eq(PLAYER_ID), any());
    verify(fixture.users, never()).lookupUniqueId(anyString());

    try (var provider = mockStatic(LuckPermsProvider.class)) {
      provider.when(LuckPermsProvider::get).thenReturn(fixture.api);
      fixture.modifications.getFirst().accept(fixture.user);
    }
    assertEquals(4, fixture.nodes.size());
    assertTrue(fixture.nodes.containsAll(List.of(contextual, other, group)));
    assertFalse(fixture.nodes.contains(global));
    PermissionNode replacement = (PermissionNode) fixture.nodes.getLast();
    assertEquals("example.node", replacement.getPermission());
    assertEquals(Set.of("true", "YES", "1").contains(raw), replacement.getValue());
    assertTrue(replacement.getContexts().isEmpty());
    verify(player, never()).sendMessage(anyString());
  }

  @Test
  void literalUuidsSkipNameLookupAndOfflineNamesWaitForResolution() {
    PermissionFixture fixture = luckPerms();
    assertTrue(invoke(player, PLAYER_ID.toString(), "example.node", "true"));
    assertEquals(1, fixture.modifications.size());
    verify(fixture.users, never()).lookupUniqueId(anyString());
    fixture.modifications.clear();
    CompletableFuture<UUID> lookup = new CompletableFuture<>();
    when(fixture.users.lookupUniqueId("Offline")).thenReturn(lookup);
    assertTrue(invoke(player, "Offline", "example.node", "false"));
    assertTrue(fixture.modifications.isEmpty());
    lookup.complete(PLAYER_ID);
    assertEquals(1, fixture.modifications.size());
    CompletableFuture<UUID> absent = CompletableFuture.completedFuture(null);
    when(fixture.users.lookupUniqueId("Unknown")).thenReturn(absent);
    fixture.modifications.clear();
    assertTrue(invoke(player, "Unknown", "example.node", "true"));
    assertTrue(fixture.modifications.isEmpty());
  }

  @Test
  void silentPermissionCompletionFiltersNamesAndBooleanValuesCaseInsensitively() {
    assertEquals(
        List.of(),
        permissions.onTabComplete(player, command, "silentpermission", new String[] {""}));
    when(player.hasPermission("tfmccore.silentpermission")).thenReturn(true);
    Player albert = mock(Player.class);
    when(albert.getName()).thenReturn("ALBERT");
    Player bob = mock(Player.class);
    when(bob.getName()).thenReturn("Bob");
    bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player, albert, bob));
    assertEquals(
        List.of("Alice", "ALBERT"),
        permissions.onTabComplete(player, command, "silentpermission", new String[] {"al"}));
    assertEquals(
        List.of("true", "false"),
        permissions.onTabComplete(
            player, command, "silentpermission", new String[] {"Alice", "node", ""}));
    assertEquals(
        List.of("false"),
        permissions.onTabComplete(
            player, command, "silentpermission", new String[] {"Alice", "node", "F"}));
    assertEquals(
        List.of(),
        permissions.onTabComplete(
            player, command, "silentpermission", new String[] {"Alice", "node"}));
  }

  private PlayerInteractEvent interact(
      Action action, Material material, EquipmentSlot hand, ItemStack item) {
    PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getAction()).thenReturn(action);
    when(event.getHand()).thenReturn(hand);
    when(event.getItem()).thenReturn(item);
    if (material != null) {
      Block block = mock(Block.class);
      when(block.getType()).thenReturn(material);
      when(block.getBlockData()).thenReturn(mock(BlockData.class));
      when(event.getClickedBlock()).thenReturn(block);
    }
    AtomicBoolean cancelled = new AtomicBoolean();
    when(event.isCancelled()).thenAnswer(call -> cancelled.get());
    when(event.useInteractedBlock())
        .thenAnswer(call -> cancelled.get() ? Event.Result.DENY : Event.Result.DEFAULT);
    doAnswer(
            call -> {
              cancelled.set(call.getArgument(0));
              return null;
            })
        .when(event)
        .setCancelled(anyBoolean());
    return event;
  }

  private CreatureSpawnEvent spawn(CreatureSpawnEvent.SpawnReason reason) {
    CopperGolem golem = mock(CopperGolem.class);
    when(golem.getWeatheringState()).thenReturn(WeatheringCopperState.UNAFFECTED);
    World world = mock(World.class);
    when(world.getName()).thenReturn("test-world");
    Block block = mock(Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getX()).thenReturn(12);
    when(block.getY()).thenReturn(64);
    when(block.getZ()).thenReturn(-3);
    when(world.getBlockAt(any(Location.class))).thenReturn(block);
    CreatureSpawnEvent event = mock(CreatureSpawnEvent.class);
    when(event.getEntity()).thenReturn(golem);
    when(event.getSpawnReason()).thenReturn(reason);
    when(event.getLocation()).thenReturn(new Location(world, 12, 64, -3));
    return event;
  }

  private boolean invoke(CommandSender sender, String... args) {
    return permissions.onCommand(sender, command, "silentpermission", args);
  }

  private PermissionFixture luckPerms() {
    PermissionFixture fixture = new PermissionFixture();
    when(player.hasPermission("tfmccore.silentpermission")).thenReturn(true);
    Plugin plugin = mock(Plugin.class);
    when(plugins.getPlugin("LuckPerms")).thenReturn(plugin);
    when(services.getRegistration(LuckPerms.class))
        .thenReturn(
            new RegisteredServiceProvider<>(
                LuckPerms.class, fixture.api, ServicePriority.Normal, plugin));
    when(fixture.api.getUserManager()).thenReturn(fixture.users);
    when(fixture.users.modifyUser(any(UUID.class), any()))
        .thenAnswer(
            call -> {
              fixture.modifications.add(call.getArgument(1));
              return CompletableFuture.completedFuture(null);
            });
    NodeMap data = mock(NodeMap.class);
    when(fixture.user.data()).thenReturn(data);
    doAnswer(
            call -> {
              Predicate<Node> predicate = call.getArgument(0);
              fixture.nodes.removeIf(predicate);
              return null;
            })
        .when(data)
        .clear(any(Predicate.class));
    when(data.add(any(Node.class)))
        .thenAnswer(
            call -> {
              fixture.nodes.add(call.getArgument(0));
              return DataMutateResult.SUCCESS;
            });
    NodeBuilderRegistry builders = mock(NodeBuilderRegistry.class);
    when(fixture.api.getNodeBuilderRegistry()).thenReturn(builders);
    PermissionNode.Builder builder = mock(PermissionNode.Builder.class);
    when(builders.forPermission()).thenReturn(builder);
    AtomicReference<String> name = new AtomicReference<>();
    AtomicBoolean value = new AtomicBoolean();
    when(builder.permission(anyString()))
        .thenAnswer(
            call -> {
              name.set(call.getArgument(0));
              return builder;
            });
    when(builder.value(anyBoolean()))
        .thenAnswer(
            call -> {
              value.set(call.getArgument(0));
              return builder;
            });
    when(builder.build()).thenAnswer(call -> permission(name.get(), value.get(), true));
    return fixture;
  }

  private PermissionNode permission(String name, boolean value, boolean global) {
    PermissionNode node = mock(PermissionNode.class);
    when(node.getPermission()).thenReturn(name);
    when(node.getValue()).thenReturn(value);
    ImmutableContextSet contexts = mock(ImmutableContextSet.class);
    when(contexts.isEmpty()).thenReturn(global);
    when(node.getContexts()).thenReturn(contexts);
    return node;
  }

  private static final class PermissionFixture {
    final LuckPerms api = mock(LuckPerms.class);
    final UserManager users = mock(UserManager.class);
    final User user = mock(User.class);
    final List<Node> nodes = new ArrayList<>();
    final List<Consumer<User>> modifications = new ArrayList<>();
  }

  private ItemStack stack(Material material) {
    ItemStack stack = mock(ItemStack.class);
    when(stack.getType()).thenReturn(material);
    when(stack.getAmount()).thenReturn(1);
    return stack;
  }
}
