package net.tfminecraft.tfmccore.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.lib.MythicLib;
import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.StatInstance;
import io.lumine.mythic.lib.api.stat.StatMap;
import io.lumine.mythic.lib.api.stat.handler.StatHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MaxHealthRounderTest {

  private static final NamespacedKey MERCENARY = new NamespacedKey("simplefactions", "mercenary");

  private final Server server = mock(Server.class);
  private final Logger logger = mock(Logger.class);
  private final Player player = mock(Player.class);
  private final AttributeInstance attribute = mock(AttributeInstance.class);
  private StatHandler handler;
  private final MaxHealthRounder rounder =
      new MaxHealthRounder(server, logger, () -> handler, p -> p == player ? attribute : null);

  private static AttributeModifier modifier(NamespacedKey key, double amount, Operation operation) {
    return new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY);
  }

  private void attribute(double total, AttributeModifier... modifiers) {
    when(attribute.getValue()).thenReturn(total);
    when(attribute.getBaseValue()).thenReturn(20.0);
    when(attribute.getModifiers()).thenReturn(List.of(modifiers));
  }

  private AttributeModifier added() {
    ArgumentCaptor<AttributeModifier> captor = ArgumentCaptor.forClass(AttributeModifier.class);
    verify(attribute).addModifier(captor.capture());
    return captor.getValue();
  }

  @Test
  void rewritesMythicLibsModifierSoTheTotalIsFloatExact() {
    AttributeModifier mythicLib = modifier(MaxHealthRounder.MYTHICLIB_KEY, 3.6, Operation.ADD_NUMBER);
    attribute(25.1, mythicLib, modifier(MERCENARY, 1.5, Operation.ADD_NUMBER));

    assertTrue(rounder.round(player));

    verify(attribute).removeModifier(mythicLib);
    AttributeModifier replacement = added();
    assertEquals(MaxHealthRounder.MYTHICLIB_KEY, replacement.getKey());
    assertEquals(Operation.ADD_NUMBER, replacement.getOperation());
    assertEquals(EquipmentSlotGroup.ANY, replacement.getSlotGroup());
    double total = 20 + replacement.getAmount() + 1.5;
    assertEquals(ExactMaxHealth.round(25.1), total);
    assertTrue(ExactMaxHealth.isFloatExact(total));
    assertTrue(total < 25.1 && 25.1 - total < ExactMaxHealth.STEP);
  }

  @Test
  void leavesExactTotalsAndUnknownStatesAlone() {
    AttributeModifier mythicLib = modifier(MaxHealthRounder.MYTHICLIB_KEY, 3.5, Operation.ADD_NUMBER);
    attribute(23.5, mythicLib);
    assertFalse(rounder.round(player));

    attribute(Double.NaN, mythicLib);
    assertFalse(rounder.round(player));

    attribute(23.6, modifier(MERCENARY, 3.6, Operation.ADD_NUMBER));
    assertFalse(rounder.round(player));

    attribute(23.6, mythicLib, modifier(MERCENARY, 0.1, Operation.MULTIPLY_SCALAR_1));
    assertFalse(rounder.round(player));

    assertFalse(rounder.round(mock(Player.class)));
    verify(attribute, never()).removeModifier(any(AttributeModifier.class));
    verify(attribute, never()).addModifier(any());
  }

  @Test
  void doesNotRewriteTheSameAmountWhenAnOffGridModifierKeepsTheTotalInexact() {
    double amount = ExactMaxHealth.modifierAmount(23.7, 20, 0.1);
    attribute(23.7, modifier(MaxHealthRounder.MYTHICLIB_KEY, amount, Operation.ADD_NUMBER),
        modifier(MERCENARY, 0.1, Operation.ADD_NUMBER));

    assertFalse(rounder.round(player));
    verify(attribute, never()).addModifier(any());
  }

  @Test
  void checkAttachesOncePerHandlerAndRoundsOnlinePlayers() {
    AttributeModifier mythicLib = modifier(MaxHealthRounder.MYTHICLIB_KEY, 3.6, Operation.ADD_NUMBER);
    attribute(23.6, mythicLib);
    doReturn(List.of(player)).when(server).getOnlinePlayers();

    rounder.check();
    verify(attribute).addModifier(any());

    handler = mock(StatHandler.class);
    rounder.check();
    rounder.check();
    verify(handler).addUpdateListener(rounder);
    verify(logger).info(contains("1/1024"));

    StatHandler reloaded = mock(StatHandler.class);
    handler = reloaded;
    rounder.check();
    verify(reloaded).addUpdateListener(rounder);

    rounder.close();
    handler = mock(StatHandler.class);
    rounder.check();
    verify(handler, never()).addUpdateListener(any());
  }

  @Test
  void statUpdatesRoundOnlineMainThreadPlayersUntilClosed() {
    StatInstance instance = mock(StatInstance.class);
    StatMap map = mock(StatMap.class);
    MMOPlayerData data = mock(MMOPlayerData.class);
    when(instance.getMap()).thenReturn(map);
    when(map.getData()).thenReturn(data);
    when(data.getPlayer()).thenReturn(player);
    attribute(23.6, modifier(MaxHealthRounder.MYTHICLIB_KEY, 3.6, Operation.ADD_NUMBER));

    rounder.onUpdate(instance);
    when(server.isPrimaryThread()).thenReturn(true);
    rounder.onUpdate(instance);
    verify(attribute, never()).getValue();

    when(data.isOnline()).thenReturn(true);
    rounder.onUpdate(instance);
    verify(attribute).addModifier(any());

    rounder.close();
    rounder.onUpdate(instance);
    verify(attribute, times(1)).getValue();
  }

  @Test
  void registerHooksMythicLibAndChecksEveryThirtySeconds() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    PluginManager manager = mock(PluginManager.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getLogger()).thenReturn(logger);
    when(server.getPluginManager()).thenReturn(manager);
    when(server.getScheduler()).thenReturn(scheduler);
    Collection<Player> online = new ArrayList<>(List.of(player));
    doReturn(online).when(server).getOnlinePlayers();

    assertNotNull(MaxHealthRounder.register(plugin));
    verifyNoInteractions(scheduler);

    MythicLib previous = MythicLib.plugin;
    MythicLib mythicLib = mock(MythicLib.class);
    io.lumine.mythic.lib.manager.StatManager stats =
        mock(io.lumine.mythic.lib.manager.StatManager.class);
    StatHandler maxHealth = mock(StatHandler.class);
    when(mythicLib.getStats()).thenReturn(stats);
    when(stats.getHandler("MAX_HEALTH")).thenReturn(Optional.of(maxHealth));
    when(manager.isPluginEnabled("MythicLib")).thenReturn(true);
    when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(attribute);
    attribute(23.6, modifier(MaxHealthRounder.MYTHICLIB_KEY, 3.6, Operation.ADD_NUMBER));
    MythicLib.plugin = mythicLib;
    try {
      MaxHealthRounder registered = MaxHealthRounder.register(plugin);

      verify(maxHealth).addUpdateListener(registered);
      verify(attribute).addModifier(any());
      ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler)
          .runTaskTimer(eq(plugin), task.capture(), eq(MaxHealthRounder.CHECK_PERIOD),
              eq(MaxHealthRounder.CHECK_PERIOD));
      online.clear();
      task.getValue().run();
      verify(maxHealth, times(1)).addUpdateListener(any());
    } finally {
      MythicLib.plugin = previous;
    }
  }
}
