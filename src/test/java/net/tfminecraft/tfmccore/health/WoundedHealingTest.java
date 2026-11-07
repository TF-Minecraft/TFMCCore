package net.tfminecraft.tfmccore.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.skills.SkillExecutor;
import io.lumine.mythic.core.skills.auras.AuraExecutor;
import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.StatInstance;
import io.lumine.mythic.lib.api.stat.StatMap;
import io.lumine.mythic.lib.api.stat.modifier.StatModifier;
import net.tfminecraft.tfmccore.cache.Cache;

class WoundedHealingTest {

  private final Player player = mock(Player.class);
  private final UUID id = UUID.randomUUID();
  private final List<Player> online = new ArrayList<>(List.of(player));
  private final List<String> regenCalls = new ArrayList<>();
  private boolean regenLoaded = true;
  private final WoundedHealing.RegenScale regen = (p, factor) -> regenCalls.add(p.getName() + "=" + factor) && regenLoaded;

  WoundedHealingTest() {
    when(player.getUniqueId()).thenReturn(id);
    when(player.getName()).thenReturn("P");
  }

  @AfterEach
  void resetMultiplier() {
    Cache.woundedHealingMultiplier = 0.5;
  }

  private WoundedHealing healing(Predicate<Entity> wounded, double multiplier) {
    return new WoundedHealing(wounded, () -> multiplier, () -> online, regen);
  }

  private EntityRegainHealthEvent heal(double amount) {
    return new EntityRegainHealthEvent(player, amount, RegainReason.MAGIC);
  }

  @Test
  void woundedTargetsHealByTheMultiplier() {
    EntityRegainHealthEvent event = heal(6.0);

    healing(entity -> entity == player, 0.5).onRegainHealth(event);

    assertEquals(3.0, event.getAmount(), 1e-9);
  }

  @Test
  void healthyTargetsHealInFull() {
    EntityRegainHealthEvent event = heal(6.0);

    healing(entity -> false, 0.5).onRegainHealth(event);

    assertEquals(6.0, event.getAmount(), 1e-9);
  }

  @Test
  void aMultiplierOfOneTurnsItOffWithoutLookingUpTheAura() {
    AtomicInteger lookups = new AtomicInteger();
    WoundedHealing healing = healing(entity -> lookups.incrementAndGet() > 0, 1.0);
    EntityRegainHealthEvent event = heal(6.0);

    healing.onRegainHealth(event);
    healing.tick();

    assertEquals(6.0, event.getAmount(), 1e-9);
    assertEquals(0, lookups.get());
    assertTrue(regenCalls.isEmpty());
  }

  @Test
  void theMultiplierIsClampedBetweenZeroAndOne() {
    EntityRegainHealthEvent negative = heal(6.0);
    healing(entity -> true, -2.0).onRegainHealth(negative);
    assertEquals(0.0, negative.getAmount(), 1e-9);

    EntityRegainHealthEvent above = heal(6.0);
    healing(entity -> true, 3.0).onRegainHealth(above);
    assertEquals(6.0, above.getAmount(), 1e-9);
  }

  @Test
  void aNaNMultiplierTurnsItOff() {
    EntityRegainHealthEvent event = heal(6.0);
    WoundedHealing healing = healing(entity -> true, Double.NaN);

    healing.onRegainHealth(event);
    healing.tick();

    assertEquals(6.0, event.getAmount(), 1e-9);
    assertTrue(regenCalls.isEmpty());
  }

  @Test
  void aPlayerMythicLibHasNotLoadedIsRetriedOnTheNextTick() {
    WoundedHealing healing = healing(entity -> true, 0.5);
    regenLoaded = false;

    healing.tick();
    regenLoaded = true;
    healing.tick();
    healing.tick();

    assertEquals(List.of("P=0.5", "P=0.5"), regenCalls, "retried once, then kept");
  }

  @Test
  void aChangedMultiplierThatCannotApplyYetIsRetried() {
    double[] multiplier = {0.5};
    WoundedHealing healing = new WoundedHealing(entity -> true, () -> multiplier[0], () -> online, regen);

    healing.tick();
    multiplier[0] = 0.25;
    regenLoaded = false;
    healing.tick();
    regenLoaded = true;
    healing.tick();
    healing.tick();

    assertEquals(List.of("P=0.5", "P=0.25", "P=0.25"), regenCalls);
  }

  @Test
  void regenerationIsCutOnceWhileWoundedAndRestoredAfter() {
    AtomicReference<Boolean> wounded = new AtomicReference<>(true);
    WoundedHealing healing = healing(entity -> wounded.get(), 0.5);

    healing.tick();
    healing.tick();
    wounded.set(false);
    healing.tick();
    healing.tick();

    assertEquals(List.of("P=0.5", "P=1.0"), regenCalls);
  }

  @Test
  void aChangedMultiplierIsReappliedToPlayersWhoAreStillWounded() {
    double[] multiplier = {0.5};
    WoundedHealing healing = new WoundedHealing(entity -> true, () -> multiplier[0], () -> online, regen);

    healing.tick();
    multiplier[0] = 0.25;
    healing.tick();

    assertEquals(List.of("P=0.5", "P=0.25"), regenCalls);
  }

  @Test
  void quitAndCloseTakeTheCutOffAndForgetPlayersWhoLeft() {
    WoundedHealing healing = healing(entity -> true, 0.5);
    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(player);

    healing.tick();
    healing.onQuit(quit);
    healing.onQuit(quit);
    assertEquals(List.of("P=0.5", "P=1.0"), regenCalls);

    regenCalls.clear();
    healing.tick();
    healing.close();
    healing.close();
    assertEquals(List.of("P=0.5", "P=1.0"), regenCalls);

    regenCalls.clear();
    healing.tick();
    online.clear();
    healing.tick();
    online.add(player);
    healing.tick();
    assertEquals(List.of("P=0.5", "P=0.5"), regenCalls, "a player who left without a quit event is scaled again");
  }

  @Test
  void registerListensSchedulesAndReadsTheConfiguredMultiplier() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Server server = mock(Server.class);
    PluginManager manager = mock(PluginManager.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getPluginManager()).thenReturn(manager);
    when(server.getScheduler()).thenReturn(scheduler);
    doReturn(List.of()).when(server).getOnlinePlayers();
    Cache.woundedHealingMultiplier = 0.25;

    try (var mythic = mockStatic(MythicBukkit.class); var adapter = mockStatic(BukkitAdapter.class)) {
      AuraExecutor auras = mockAuras(mythic);
      AbstractEntity adapted = mock(AbstractEntity.class);
      adapter.when(() -> BukkitAdapter.adapt((Entity) player)).thenReturn(adapted); // the Entity overload, as in hasAura
      when(auras.getHasAura(adapted, WoundedHealing.AURA)).thenReturn(true);

      WoundedHealing healing = WoundedHealing.register(plugin);
      EntityRegainHealthEvent event = heal(8.0);
      healing.onRegainHealth(event);

      verify(manager).registerEvents(healing, plugin);
      ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler).runTaskTimer(eq(plugin), task.capture(), eq(WoundedHealing.PERIOD), eq(WoundedHealing.PERIOD));
      task.getValue().run();
      verify(server).getOnlinePlayers();
      assertEquals(2.0, event.getAmount(), 1e-9);
    }
  }

  @Test
  void theAuraLookupIsTheReadOnlyHasAura() {
    try (var mythic = mockStatic(MythicBukkit.class); var adapter = mockStatic(BukkitAdapter.class)) {
      AuraExecutor auras = mockAuras(mythic);
      AbstractEntity adapted = mock(AbstractEntity.class);
      adapter.when(() -> BukkitAdapter.adapt((Entity) player)).thenReturn(adapted); // the Entity overload, as in hasAura
      when(auras.getHasAura(adapted, WoundedHealing.AURA)).thenReturn(true, false);

      assertTrue(WoundedHealing.hasAura(player));
      assertFalse(WoundedHealing.hasAura(player));
      verify(auras, never()).getAuraRegistry(any(UUID.class));
      verify(auras, never()).getAuraRegistry(any(AbstractEntity.class));
    }
  }

  @Test
  void scaleRegenPutsACompoundModifierOnBothRegenerationStatsAndTakesItOff() {
    try (var players = mockStatic(MMOPlayerData.class)) {
      MMOPlayerData data = mock(MMOPlayerData.class);
      StatMap stats = mock(StatMap.class);
      players.when(() -> MMOPlayerData.getOrNull(player)).thenReturn(data);
      when(data.getStatMap()).thenReturn(stats);
      List<StatInstance> instances = new ArrayList<>();
      for (String stat : WoundedHealing.REGEN_STATS) {
        StatInstance instance = mock(StatInstance.class);
        when(stats.getInstance(stat)).thenReturn(instance);
        instances.add(instance);
      }

      assertTrue(WoundedHealing.scaleRegen(player, 0.5));
      for (StatInstance instance : instances) {
        verify(instance).remove(WoundedHealing.KEY);
        ArgumentCaptor<StatModifier> modifier = ArgumentCaptor.forClass(StatModifier.class);
        verify(instance).addModifier(modifier.capture());
        assertEquals(-50.0, modifier.getValue().getValue(), 1e-9);
        assertEquals(WoundedHealing.KEY, modifier.getValue().getKey());
        assertEquals(io.lumine.mythic.lib.player.modifier.ModifierType.COMPOUND, modifier.getValue().getType());
      }

      WoundedHealing.scaleRegen(player, 1.0);
      for (StatInstance instance : instances) {
        verify(instance, times(2)).remove(WoundedHealing.KEY);
        verify(instance, times(1)).addModifier(any(StatModifier.class));
      }
    }
  }

  @Test
  void scaleRegenSkipsPlayersMythicLibHasNotLoaded() {
    try (var players = mockStatic(MMOPlayerData.class)) {
      players.when(() -> MMOPlayerData.getOrNull(player)).thenReturn(null);

      assertFalse(WoundedHealing.scaleRegen(player, 0.5));
    }
  }

  private static AuraExecutor mockAuras(MockedStatic<MythicBukkit> mythic) {
    MythicBukkit inst = mock(MythicBukkit.class);
    SkillExecutor skills = mock(SkillExecutor.class);
    AuraExecutor auras = mock(AuraExecutor.class);
    mythic.when(MythicBukkit::inst).thenReturn(inst);
    when(inst.getSkillManager()).thenReturn(skills);
    when(skills.getAuraManager()).thenReturn(auras);
    return auras;
  }
}
