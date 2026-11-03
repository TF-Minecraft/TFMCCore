package net.tfminecraft.tfmccore.movement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import net.tfminecraft.tfmccore.cache.Cache;

class SoulSandStrideTest {

  private final Server server = mock(Server.class);
  private final Player player = mock(Player.class);
  private final AttributeInstance efficiency = mock(AttributeInstance.class);
  private final AttributeInstance speed = mock(AttributeInstance.class);
  private final Block feet = mock(Block.class);
  private final Block below = mock(Block.class);
  private final Block twoBelow = mock(Block.class);
  private final Set<String> tags = new HashSet<>();
  private final AtomicBoolean enabled = new AtomicBoolean(true);
  private final SoulSandStride stride = new SoulSandStride(server, enabled::get);

  @BeforeEach
  void setUp() {
    doReturn(List.of(player)).when(server).getOnlinePlayers();
    Location location = mock(Location.class);
    when(player.getLocation()).thenReturn(location);
    when(location.getBlock()).thenReturn(feet);
    when(feet.getRelative(BlockFace.DOWN)).thenReturn(below);
    when(feet.getRelative(BlockFace.DOWN, 2)).thenReturn(twoBelow);
    when(feet.getType()).thenReturn(Material.STONE);
    when(below.getType()).thenReturn(Material.STONE);
    when(twoBelow.getType()).thenReturn(Material.STONE);
    when(player.getScoreboardTags()).thenReturn(tags);
    when(player.addScoreboardTag(anyString())).thenAnswer(call -> tags.add(call.getArgument(0)));
    when(player.removeScoreboardTag(anyString())).thenAnswer(call -> tags.remove(call.getArgument(0)));
    when(player.getAttribute(Attribute.MOVEMENT_EFFICIENCY)).thenReturn(efficiency);
    when(player.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(speed);
  }

  private static AttributeModifier added(AttributeInstance attribute) {
    ArgumentCaptor<AttributeModifier> captor = ArgumentCaptor.forClass(AttributeModifier.class);
    verify(attribute).addModifier(captor.capture());
    return captor.getValue();
  }

  @Test
  void steppingOnSoulSandAddsTheOldEventsModifiersAndTag() {
    when(feet.getType()).thenReturn(Material.SOUL_SAND);

    stride.tick();

    assertTrue(tags.contains("tfmc_soulstride"));
    AttributeModifier e = added(efficiency);
    assertEquals("tfmc:soulstride", e.getKey().toString());
    assertEquals(1.0, e.getAmount());
    assertEquals(AttributeModifier.Operation.ADD_NUMBER, e.getOperation());
    assertEquals(EquipmentSlotGroup.ANY, e.getSlotGroup());
    AttributeModifier s = added(speed);
    assertEquals("tfmc:soulstride", s.getKey().toString());
    assertEquals(0.0405, s.getAmount());
    // A leftover modifier is cleared first, so addModifier never sees a duplicate key
    for (AttributeInstance attribute : List.of(efficiency, speed)) {
      InOrder order = inOrder(attribute);
      order.verify(attribute).removeModifier(SoulSandStride.KEY);
      order.verify(attribute).addModifier(any());
    }
  }

  @Test
  void closeTakesTheEffectOffOnlyFromPlayersWhoHaveIt() {
    Player other = mock(Player.class);
    when(other.getScoreboardTags()).thenReturn(new HashSet<>());
    doReturn(List.of(player, other)).when(server).getOnlinePlayers();
    tags.add("tfmc_soulstride");
    when(feet.getType()).thenReturn(Material.SOUL_SAND);

    stride.close();

    assertTrue(tags.isEmpty(), "removed even while still on soul sand");
    verify(efficiency).removeModifier(SoulSandStride.KEY);
    verify(speed).removeModifier(SoulSandStride.KEY);
    verify(other, never()).getAttribute(any());
  }

  @Test
  void nothingHappensOffSoulSandWithoutTheEffect() {
    stride.tick();

    assertTrue(tags.isEmpty());
    verifyNoInteractions(efficiency, speed);
  }

  @Test
  void keepsTheEffectWhileOnOrJustAboveSoulSand() {
    tags.add("tfmc_soulstride");
    when(feet.getType()).thenReturn(Material.SOUL_SAND);
    stride.tick();
    when(feet.getType()).thenReturn(Material.AIR);
    when(below.getType()).thenReturn(Material.SOUL_SAND);
    stride.tick();
    when(below.getType()).thenReturn(Material.AIR);
    when(twoBelow.getType()).thenReturn(Material.SOUL_SAND);
    stride.tick();

    assertTrue(tags.contains("tfmc_soulstride"));
    verifyNoInteractions(efficiency, speed);
  }

  @Test
  void leavingSoulSandRemovesModifiersAndTag() {
    tags.add("tfmc_soulstride");

    stride.tick();

    assertFalse(tags.contains("tfmc_soulstride"));
    verify(efficiency).removeModifier(SoulSandStride.KEY);
    verify(speed).removeModifier(SoulSandStride.KEY);
    verify(efficiency, never()).addModifier(any());
  }

  @Test
  void disabledTakesTheEffectOffAndNeverAddsIt() {
    enabled.set(false);
    when(feet.getType()).thenReturn(Material.SOUL_SAND);
    stride.tick();
    assertTrue(tags.isEmpty());
    verifyNoInteractions(efficiency, speed);
    verify(player, never()).getLocation();

    tags.add("tfmc_soulstride");
    stride.tick();
    assertTrue(tags.isEmpty());
    verify(speed).removeModifier(SoulSandStride.KEY);
  }

  @Test
  void missingAttributesAreSkipped() {
    when(player.getAttribute(Attribute.MOVEMENT_EFFICIENCY)).thenReturn(null);
    when(player.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(null);
    when(feet.getType()).thenReturn(Material.SOUL_SAND);
    stride.tick();
    assertTrue(tags.contains("tfmc_soulstride"));

    when(feet.getType()).thenReturn(Material.STONE);
    stride.tick();
    assertFalse(tags.contains("tfmc_soulstride"));
  }

  @Test
  void quittingTakesTheEffectOffBeforeThePlayerIsSaved() {
    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(player);
    stride.onQuit(quit);
    verifyNoInteractions(efficiency, speed);

    tags.add("tfmc_soulstride");
    when(feet.getType()).thenReturn(Material.SOUL_SAND);
    stride.onQuit(quit);
    assertTrue(tags.isEmpty());
    verify(efficiency).removeModifier(SoulSandStride.KEY);
    verify(speed).removeModifier(SoulSandStride.KEY);
  }

  @Test
  void registerRunsEveryFiveTicksAndReadsTheConfigFlag() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    PluginManager manager = mock(PluginManager.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getScheduler()).thenReturn(scheduler);
    when(server.getPluginManager()).thenReturn(manager);
    boolean previous = Cache.soulSandStride;
    try {
      SoulSandStride registered = SoulSandStride.register(plugin);
      ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler).runTaskTimer(eq(plugin), task.capture(), eq(5L), eq(5L));
      assertNotNull(registered);
      verify(manager).registerEvents(registered, plugin);

      when(feet.getType()).thenReturn(Material.SOUL_SAND);
      Cache.soulSandStride = false;
      task.getValue().run();
      assertTrue(tags.isEmpty());
      Cache.soulSandStride = true;
      task.getValue().run();
      assertTrue(tags.contains("tfmc_soulstride"));
    } finally {
      Cache.soulSandStride = previous;
    }
  }
}
