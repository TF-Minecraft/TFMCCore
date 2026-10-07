package net.tfminecraft.tfmccore.whistle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class WhistleListenerCoverageTest {
  private record ConfigSnapshot(
      String path,
      double radius,
      int duration,
      int cooldown,
      EnumSet<EntityType> animals,
      String sound,
      float volume,
      float pitch,
      String highlighted,
      String noAnimals,
      String cooldownMessage) {
    static ConfigSnapshot capture() {
      return new ConfigSnapshot(
          WhistleConfig.itemPath,
          WhistleConfig.detectionRadius,
          WhistleConfig.glowDuration,
          WhistleConfig.cooldownSeconds,
          WhistleConfig.whitelistedAnimals.clone(),
          WhistleConfig.soundName,
          WhistleConfig.soundVolume,
          WhistleConfig.soundPitch,
          WhistleConfig.highlightedMessage,
          WhistleConfig.noAnimalsMessage,
          WhistleConfig.cooldownMessage);
    }

    void restore() {
      WhistleConfig.itemPath = path;
      WhistleConfig.detectionRadius = radius;
      WhistleConfig.glowDuration = duration;
      WhistleConfig.cooldownSeconds = cooldown;
      WhistleConfig.whitelistedAnimals.clear();
      WhistleConfig.whitelistedAnimals.addAll(animals);
      WhistleConfig.soundName = sound;
      WhistleConfig.soundVolume = volume;
      WhistleConfig.soundPitch = pitch;
      WhistleConfig.highlightedMessage = highlighted;
      WhistleConfig.noAnimalsMessage = noAnimals;
      WhistleConfig.cooldownMessage = cooldownMessage;
    }
  }

  private ConfigSnapshot previous;
  private Player player;
  private World world;
  private Location location;
  private ItemStack whistle;
  private ItemChecker checker;
  private Logger logger;
  private WhistleListener listener;
  private MockedStatic<TLibs> tlibs;
  private MockedStatic<TFMCCore> core;

  @BeforeEach
  void setup() {
    previous = ConfigSnapshot.capture();
    WhistleConfig.itemPath = "custom:animal-whistle";
    WhistleConfig.detectionRadius = 23.5;
    WhistleConfig.glowDuration = 7;
    WhistleConfig.cooldownSeconds = 0;
    WhistleConfig.whitelistedAnimals.clear();
    WhistleConfig.whitelistedAnimals.addAll(EnumSet.of(EntityType.HORSE, EntityType.DONKEY));
    WhistleConfig.soundName = "minecraft:entity.cat.ambient";
    WhistleConfig.soundVolume = 2.5f;
    WhistleConfig.soundPitch = 1.5f;
    WhistleConfig.highlightedMessage = "&aFound %count% animals";
    WhistleConfig.noAnimalsMessage = "&7No nearby animals";
    WhistleConfig.cooldownMessage = "&eWait %seconds% seconds";
    assertNotNull(Sound.ENTITY_CAT_AMBIENT);
    world = mock(World.class);
    location = new Location(world, 4, 65, -2);
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.fromString("4f6e9c93-3e3c-4a89-807a-8275e11e4d32"));
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(location);
    when(player.getNearbyEntities(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
    whistle = mock(ItemStack.class);
    when(whistle.getAmount()).thenReturn(3);
    ItemAPI api = mock(ItemAPI.class);
    checker = mock(ItemChecker.class);
    when(api.getChecker()).thenReturn(checker);
    when(checker.checkItemWithPath(whistle, WhistleConfig.itemPath)).thenReturn(true);
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getItemAPI).thenReturn(api);
    TFMCCore plugin = mock(TFMCCore.class);
    logger = mock(Logger.class);
    when(plugin.getLogger()).thenReturn(logger);
    core = mockStatic(TFMCCore.class);
    core.when(TFMCCore::getInstance).thenReturn(plugin);
    listener = new WhistleListener();
  }

  @AfterEach
  void cleanup() {
    core.close();
    tlibs.close();
    previous.restore();
  }

  @ParameterizedTest
  @ValueSource(strings = {"minecraft:item.goat_horn.sound.6", "item.goat_horn.sound.6"})
  void soundRegistryKeysKeepTheirLiteralUnderscores(String key) {
    WhistleConfig.soundName = key;

    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));

    verify(world)
        .playSound(location, Sound.ITEM_GOAT_HORN_SOUND_6, SoundCategory.AMBIENT, 2.5f, 1.5f);
    verifyNoInteractions(logger);
  }

  @ParameterizedTest
  @EnumSource(
      value = Action.class,
      names = {"LEFT_CLICK_AIR", "LEFT_CLICK_BLOCK", "PHYSICAL"})
  void unrelatedActionsLeaveTheEventAndItemUntouched(Action action) {
    PlayerInteractEvent event = interact(action, EquipmentSlot.HAND, whistle);
    listener.onPlayerInteract(event);
    assertFalse(event.isCancelled());
    verifyNoInteractions(checker, world);
    verify(player, never()).sendMessage(anyString());
    verify(whistle, never()).setAmount(anyInt());
  }

  @Test
  void offhandEmptyAndUnrecognizedItemsDoNotActivateTheWhistle() {
    PlayerInteractEvent offhand = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, whistle);
    PlayerInteractEvent empty = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, null);
    listener.onPlayerInteract(offhand);
    listener.onPlayerInteract(empty);
    verifyNoInteractions(checker);
    when(checker.checkItemWithPath(whistle, WhistleConfig.itemPath)).thenReturn(false);
    PlayerInteractEvent unrelated = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle);
    listener.onPlayerInteract(unrelated);
    assertFalse(offhand.isCancelled());
    assertFalse(empty.isCancelled());
    assertFalse(unrelated.isCancelled());
    verifyNoInteractions(world);
    verify(player, never()).sendMessage(anyString());
  }

  @Test
  void aValidWhistleHighlightsOnlyWhitelistedLivingEntitiesAndReportsTheirCount() {
    Entity itemEntity = mock(Entity.class);
    LivingEntity horse = animal(EntityType.HORSE);
    LivingEntity cow = animal(EntityType.COW);
    LivingEntity donkey = animal(EntityType.DONKEY);
    when(player.getNearbyEntities(23.5, 23.5, 23.5))
        .thenReturn(List.of(itemEntity, horse, cow, donkey));
    PlayerInteractEvent event = interact(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, whistle);

    listener.onPlayerInteract(event);

    assertTrue(event.isCancelled());
    verify(player).getNearbyEntities(23.5, 23.5, 23.5);
    ArgumentCaptor<PotionEffect> effects = ArgumentCaptor.forClass(PotionEffect.class);
    verify(horse).addPotionEffect(effects.capture());
    verify(donkey).addPotionEffect(effects.capture());
    for (PotionEffect effect : effects.getAllValues()) {
      assertSame(PotionEffectType.GLOWING, effect.getType());
      assertEquals(140, effect.getDuration());
      assertEquals(0, effect.getAmplifier());
      assertFalse(effect.isAmbient());
      assertFalse(effect.hasParticles());
    }
    verify(cow, never()).addPotionEffect(any());
    verify(world).playSound(location, Sound.ENTITY_CAT_AMBIENT, SoundCategory.AMBIENT, 2.5f, 1.5f);
    verify(player).sendMessage("§aFound 2 animals");
    assertEquals(3, whistle.getAmount());
    verify(whistle, never()).setAmount(anyInt());
  }

  @Test
  void cooldownPreventsRepeatedEffectsUntilThePlayerQuits() {
    WhistleConfig.cooldownSeconds = 3600;
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    PlayerInteractEvent repeated = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle);
    listener.onPlayerInteract(repeated);
    assertTrue(repeated.isCancelled());
    verify(player, times(1)).getNearbyEntities(23.5, 23.5, 23.5);
    verify(world, times(1))
        .playSound(location, Sound.ENTITY_CAT_AMBIENT, SoundCategory.AMBIENT, 2.5f, 1.5f);
    ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
    verify(player, times(2)).sendMessage(messages.capture());
    assertEquals("§7No nearby animals", messages.getAllValues().getFirst());
    String cooldown = messages.getAllValues().getLast();
    assertTrue(cooldown.matches("§eWait [0-9]+ seconds"));
    long seconds = Long.parseLong(cooldown.split(" ")[1]);
    assertTrue(seconds > 0 && seconds <= 3600);

    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(player);
    listener.onQuit(quit);
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    verify(player, times(2)).getNearbyEntities(23.5, 23.5, 23.5);
    verify(world, times(2))
        .playSound(location, Sound.ENTITY_CAT_AMBIENT, SoundCategory.AMBIENT, 2.5f, 1.5f);
    verify(player, times(2)).sendMessage("§7No nearby animals");
  }

  @Test
  void anotherPlayersQuitDoesNotClearTheWhistlersCooldown() {
    WhistleConfig.cooldownSeconds = 3600;
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    Player other = mock(Player.class);
    when(other.getUniqueId()).thenReturn(UUID.fromString("438913dc-8269-497b-b5cd-8f27e90c8c88"));
    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(other);
    listener.onQuit(quit);
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    verify(player, times(1)).getNearbyEntities(23.5, 23.5, 23.5);
    verify(player).sendMessage(startsWith("§eWait "));
  }

  @ParameterizedTest
  @ValueSource(strings = {"entity.cat.ambient", "ITEM_GOAT_HORN_SOUND_6"})
  void unqualifiedRegistryNamesAndLegacySoundNamesResolve(String name) {
    WhistleConfig.soundName = name;
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    Sound expected =
        name.equals("entity.cat.ambient") ? Sound.ENTITY_CAT_AMBIENT : Sound.ITEM_GOAT_HORN_SOUND_6;
    verify(world).playSound(location, expected, SoundCategory.AMBIENT, 2.5f, 1.5f);
    verifyNoInteractions(logger);
  }

  @Test
  void soundConfigurationChangesTakeEffectAfterTheCacheIsInvalidated() {
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    WhistleConfig.soundName = "minecraft:entity.wolf.ambient";
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    listener.invalidateSound();
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    ArgumentCaptor<Sound> sounds = ArgumentCaptor.forClass(Sound.class);
    verify(world, times(3))
        .playSound(eq(location), sounds.capture(), eq(SoundCategory.AMBIENT), eq(2.5f), eq(1.5f));
    assertEquals(
        List.of(Sound.ENTITY_CAT_AMBIENT, Sound.ENTITY_CAT_AMBIENT, Sound.ENTITY_WOLF_AMBIENT),
        sounds.getAllValues());
  }

  @ParameterizedTest
  @NullAndEmptySource
  void emptySoundConfigurationStillSearchesForAnimalsWithoutPlayingASound(String name) {
    WhistleConfig.soundName = name;
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    verifyNoInteractions(world, logger);
    verify(player).getNearbyEntities(23.5, 23.5, 23.5);
    verify(player).sendMessage("§7No nearby animals");
  }

  @ParameterizedTest
  @ValueSource(strings = {"not_a_real_sound", "invalid sound !"})
  void invalidSoundIsWarnedOncePerCacheAndDoesNotPreventWhistling(String name) {
    WhistleConfig.soundName = name;
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    verify(logger).warning("Invalid sound type in animal whistle config: " + name);
    listener.invalidateSound();
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    verify(logger, times(2)).warning("Invalid sound type in animal whistle config: " + name);
    verifyNoInteractions(world);
    verify(player, times(3)).sendMessage("§7No nearby animals");
  }

  @ParameterizedTest
  @NullAndEmptySource
  void emptyNoAnimalsMessagesRemainSilent(String message) {
    WhistleConfig.noAnimalsMessage = message;
    WhistleConfig.soundName = "";
    listener.onPlayerInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle));
    verify(player).getNearbyEntities(23.5, 23.5, 23.5);
    verify(player, never()).sendMessage(anyString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void itemProviderFailureLeavesTheItemUntouchedAndLogsWhenThePluginIsAvailable(boolean available) {
    if (!available) core.when(TFMCCore::getInstance).thenReturn(null);
    when(checker.checkItemWithPath(whistle, WhistleConfig.itemPath))
        .thenThrow(new IllegalStateException("provider offline"));
    PlayerInteractEvent event = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, whistle);
    assertDoesNotThrow(() -> listener.onPlayerInteract(event));
    assertFalse(event.isCancelled());
    assertEquals(3, whistle.getAmount());
    verify(whistle, never()).setAmount(anyInt());
    verifyNoInteractions(world);
    if (available) verify(logger).warning("Failed to validate animal whistle: provider offline");
    else verifyNoInteractions(logger);
  }

  private LivingEntity animal(EntityType type) {
    LivingEntity entity = mock(LivingEntity.class);
    when(entity.getType()).thenReturn(type);
    return entity;
  }

  private PlayerInteractEvent interact(Action action, EquipmentSlot hand, ItemStack item) {
    PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    when(event.getAction()).thenReturn(action);
    when(event.getHand()).thenReturn(hand);
    when(event.getItem()).thenReturn(item);
    when(event.getPlayer()).thenReturn(player);
    AtomicBoolean cancelled = new AtomicBoolean();
    when(event.isCancelled()).thenAnswer(call -> cancelled.get());
    doAnswer(
            call -> {
              cancelled.set(call.getArgument(0));
              return null;
            })
        .when(event)
        .setCancelled(anyBoolean());
    return event;
  }
}
