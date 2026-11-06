package net.tfminecraft.tfmccore.indicators;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.lumine.mythic.lib.api.event.AttackUnregisteredEvent;
import io.lumine.mythic.lib.api.event.IndicatorDisplayEvent;
import io.lumine.mythic.lib.api.event.IndicatorDisplayEvent.IndicatorType;
import net.tfminecraft.tfmccore.cache.Cache;

class RealDamageIndicatorsTest {

  /** Records what would be shown; format matches MythicLib's {@code decimal_format: '0.#'}. */
  static final class Shown implements RealDamageIndicators.Display {
    final List<String> lines = new ArrayList<>();
    final List<Vector> directions = new ArrayList<>();
    int refreshes;
    Runnable duringShow = () -> {};
    private final DecimalFormat format = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));

    @Override
    public void refresh() {
      refreshes++;
    }

    @Override
    public String format(double value) {
      return format.format(value);
    }

    @Override
    public void show(Entity entity, String line, Vector direction) {
      lines.add(line);
      directions.add(direction);
      duringShow.run();
    }
  }

  private final AtomicBoolean enabled = new AtomicBoolean(true);
  private final List<Runnable> ticks = new ArrayList<>();
  private final Shown shown = new Shown();
  private double random = 0.5;
  private final RealDamageIndicators indicators =
      new RealDamageIndicators(enabled::get, ticks::add, shown, () -> random);

  private static LivingEntity target(double health) {
    LivingEntity target = mock(Player.class);
    when(target.getUniqueId()).thenReturn(UUID.randomUUID());
    when(target.getHealth()).thenReturn(health);
    when(target.isValid()).thenReturn(true);
    when(target.getLocation()).thenReturn(new Location(null, 0, 64, 0));
    AttributeInstance max = mock(AttributeInstance.class);
    when(max.getValue()).thenReturn(40.0);
    when(target.getAttribute(Attribute.MAX_HEALTH)).thenReturn(max);
    return target;
  }

  private static Entity attackerAt(double x, double z) {
    Entity attacker = mock(Player.class);
    when(attacker.getLocation()).thenReturn(new Location(null, x, 64, z));
    return attacker;
  }

  private static AttackUnregisteredEvent attack(LivingEntity target, Entity attacker, double finalDamage) {
    EntityDamageEvent damage = attacker == null ? mock(EntityDamageEvent.class) : mock(EntityDamageByEntityEvent.class);
    when(damage.getFinalDamage()).thenReturn(finalDamage);
    when(damage.getEntity()).thenReturn(target);
    if (attacker != null) when(((EntityDamageByEntityEvent) damage).getDamager()).thenReturn(attacker);
    AttackUnregisteredEvent event = mock(AttackUnregisteredEvent.class);
    when(event.getEntity()).thenReturn(target);
    when(event.toBukkit()).thenReturn(damage);
    return event;
  }

  /** What MythicLib does: the attack event, its indicators inside it, then the MONITOR end. */
  private List<IndicatorDisplayEvent> hit(AttackUnregisteredEvent attack, String... lines) {
    indicators.onAttackStart(attack);
    List<IndicatorDisplayEvent> events = new ArrayList<>();
    for (String line : lines) {
      IndicatorDisplayEvent event = new IndicatorDisplayEvent(attack.getEntity(), line, IndicatorType.DAMAGE);
      indicators.onIndicator(event);
      events.add(event);
    }
    indicators.onAttackEnd(attack);
    // The damage event's own MONITOR pass, which also sees the reported hit
    indicators.onDamage(attack.toBukkit());
    return events;
  }

  private void tick() {
    List<Runnable> due = new ArrayList<>(ticks);
    ticks.clear();
    due.forEach(Runnable::run);
  }

  @Test
  void showsTheHealthReallyLostAfterArmour() {
    LivingEntity target = target(35.35);
    List<IndicatorDisplayEvent> events = hit(attack(target, attackerAt(0, -2), 6.4), "&c🗡&4✘ &f6.4");

    assertTrue(events.get(0).isCancelled());
    assertEquals(1, ticks.size());
    assertTrue(shown.lines.isEmpty());

    when(target.getHealth()).thenReturn(31.65);
    tick();

    assertEquals(List.of("&c🗡&4✘ &f3.7"), shown.lines);
    assertEquals(1, shown.refreshes);
  }

  @Test
  void absorptionCountsAsHealth() {
    LivingEntity target = target(20);
    when(target.getAbsorptionAmount()).thenReturn(4.0);
    hit(attack(target, null, 6), "&f6");

    when(target.getAbsorptionAmount()).thenReturn(0.0);
    when(target.getHealth()).thenReturn(18.5);
    tick();

    assertEquals(List.of("&f5.5"), shown.lines);
  }

  @Test
  void aKillShowsTheHealthThatWasLeft() {
    LivingEntity target = target(3.2);
    hit(attack(target, null, 10), "&f10");

    when(target.getHealth()).thenReturn(0.0);
    when(target.isValid()).thenReturn(false);
    tick();

    assertEquals(List.of("&f3.2"), shown.lines);
  }

  @Test
  void aTargetRemovedWhileAliveShowsNothing() {
    LivingEntity target = target(20);
    hit(attack(target, null, 4), "&f4");

    // The hit landed, then the target logged out or despawned in the same tick
    when(target.getHealth()).thenReturn(16.0);
    when(target.isValid()).thenReturn(false);
    tick();

    assertTrue(shown.lines.isEmpty());
  }

  @Test
  void healingBeforeTheFlushIsAddedBack() {
    LivingEntity target = target(30);
    hit(attack(target, null, 6), "&f6");

    when(target.getHealth()).thenReturn(24.0);
    indicators.onHeal(new EntityRegainHealthEvent(target, 1.0, RegainReason.SATIATED));
    when(target.getHealth()).thenReturn(25.0);
    tick();

    assertEquals(List.of("&f6"), shown.lines);
  }

  @Test
  void onlyHealingThatFitsUnderMaxHealthCounts() {
    LivingEntity target = target(40);
    hit(attack(target, null, 1), "&f1");

    when(target.getHealth()).thenReturn(39.5);
    indicators.onHeal(new EntityRegainHealthEvent(target, 4.0, RegainReason.CUSTOM));
    when(target.getHealth()).thenReturn(40.0);
    tick();

    assertEquals(List.of("&f0.5"), shown.lines);
  }

  @Test
  void healingWithoutAMaxHealthAttributeCountsInFull() {
    LivingEntity target = target(30);
    when(target.getAttribute(Attribute.MAX_HEALTH)).thenReturn(null);
    hit(attack(target, null, 6), "&f6");

    indicators.onHeal(new EntityRegainHealthEvent(target, 2.0, RegainReason.CUSTOM));
    when(target.getHealth()).thenReturn(26.0);
    tick();

    assertEquals(List.of("&f6"), shown.lines);
  }

  @Test
  void healingAndDamageOfTargetsWithNothingPendingAreIgnored() {
    LivingEntity target = target(30);
    assertDoesNotThrow(() -> indicators.onHeal(new EntityRegainHealthEvent(target, 2.0, RegainReason.CUSTOM)));
    EntityDamageEvent fire = mock(EntityDamageEvent.class);
    when(fire.getEntity()).thenReturn(target);
    assertDoesNotThrow(() -> indicators.onDamage(fire));
    assertTrue(ticks.isEmpty());
  }

  @Test
  void damageMythicLibDidNotReportIsTakenOut() {
    LivingEntity target = target(30);
    hit(attack(target, null, 4), "&f4");
    EntityDamageEvent other = mock(EntityDamageEvent.class);
    when(other.getEntity()).thenReturn(target);
    when(other.getFinalDamage()).thenReturn(2.0);
    indicators.onDamage(other);

    when(target.getHealth()).thenReturn(24.0);
    tick();

    assertEquals(List.of("&f4"), shown.lines);
  }

  @Test
  void splitIndicatorsAreScaledToAddUpToTheLoss() {
    LivingEntity target = target(30);
    hit(attack(target, null, 8), "&c🗡 &f6", "&9✩ &f2", "&fᜆ");

    when(target.getHealth()).thenReturn(26.0);
    tick();

    assertEquals(List.of("&c🗡 &f3", "&9✩ &f1", "&fᜆ"), shown.lines);
  }

  @Test
  void aPartThatScalesDownToNothingIsDropped() {
    LivingEntity target = target(30);
    hit(attack(target, null, 8.1), "&c🗡 &f8", "&9✩ &f0.1");

    when(target.getHealth()).thenReturn(26.0);
    tick();

    assertEquals(List.of("&c🗡 &f4"), shown.lines);
  }

  @Test
  void aJoinedLineHasEachOfItsNumbersScaled() {
    LivingEntity target = target(30);
    hit(attack(target, null, 8), "&c🗡 &f6 &9✩ &f2");

    when(target.getHealth()).thenReturn(26.0);
    tick();

    assertEquals(List.of("&c🗡 &f3 &9✩ &f1"), shown.lines);
  }

  @Test
  void splitIndicatorsWithNoPredictedDamageKeepTheirNumbers() {
    LivingEntity target = target(30);
    hit(attack(target, null, 0), "&f1", "&f2");

    when(target.getHealth()).thenReturn(27.0);
    tick();

    assertEquals(List.of("&f1", "&f2"), shown.lines);
  }

  @Test
  void hitsOnOneTargetInATickShareOneMeasurement() {
    LivingEntity target = target(30);
    hit(attack(target, null, 4), "&f4");
    hit(attack(target, null, 4), "&f4");

    when(target.getHealth()).thenReturn(26.0);
    tick();

    assertEquals(List.of("&f2", "&f2"), shown.lines);
  }

  @Test
  void aSecondHitWithoutAnIndicatorKeepsItsShareOutOfTheFirstsNumber() {
    LivingEntity target = target(30);
    hit(attack(target, null, 4), "&f4");
    // Below MythicLib's min_damage: no indicator, but it still took health
    hit(attack(target, null, 4));

    when(target.getHealth()).thenReturn(26.0);
    tick();

    assertEquals(List.of("&f2"), shown.lines);
  }

  @Test
  void everyTargetHitInATickSharesOneFlush() {
    LivingEntity first = target(30);
    LivingEntity second = target(30);
    LivingEntity quiet = target(30);
    hit(attack(first, null, 4), "&f4");
    hit(attack(second, null, 4), "&f4");
    hit(attack(quiet, null, 4));
    assertEquals(1, ticks.size());

    when(first.getHealth()).thenReturn(27.0);
    when(second.getHealth()).thenReturn(28.0);
    when(quiet.getHealth()).thenReturn(26.0);
    tick();

    assertEquals(List.of("&f3", "&f2"), shown.lines);
    assertEquals(1, shown.refreshes);
  }

  @Test
  void aSingleLineWithoutANumberIsShownAsItIs() {
    LivingEntity target = target(30);
    hit(attack(target, null, 4), "&fᜆᜊᜄ");

    when(target.getHealth()).thenReturn(27.0);
    tick();

    assertEquals(List.of("&fᜆᜊᜄ"), shown.lines);
  }

  @Test
  void aHitThatTookNothingShowsNothing() {
    LivingEntity target = target(30);
    hit(attack(target, null, 4), "&f4");

    tick();

    assertTrue(shown.lines.isEmpty());
    assertEquals(0, shown.refreshes);
  }

  @Test
  void anAttackWithoutIndicatorsShowsNothing() {
    LivingEntity target = target(30);
    hit(attack(target, null, 4));

    when(target.getHealth()).thenReturn(26.0);
    tick();

    assertTrue(shown.lines.isEmpty());
  }

  @Test
  void offLeavesMythicLibsIndicatorsAlone() {
    enabled.set(false);
    LivingEntity target = target(30);
    List<IndicatorDisplayEvent> events = hit(attack(target, null, 4), "&f4");

    assertFalse(events.get(0).isCancelled());
    assertTrue(ticks.isEmpty());
  }

  @Test
  void onlyDamageIndicatorsOfTheCurrentTargetAreHeld() {
    LivingEntity target = target(30);
    LivingEntity other = target(30);

    IndicatorDisplayEvent outside = new IndicatorDisplayEvent(target, "&f1", IndicatorType.DAMAGE);
    indicators.onIndicator(outside);
    assertFalse(outside.isCancelled());

    AttackUnregisteredEvent attack = attack(target, null, 4);
    indicators.onAttackStart(attack);
    IndicatorDisplayEvent regen = new IndicatorDisplayEvent(target, "&a+1", IndicatorType.REGENERATION);
    IndicatorDisplayEvent wrongTarget = new IndicatorDisplayEvent(other, "&f1", IndicatorType.DAMAGE);
    indicators.onIndicator(regen);
    indicators.onIndicator(wrongTarget);
    indicators.onAttackEnd(attack);

    assertFalse(regen.isCancelled());
    assertFalse(wrongTarget.isCancelled());
  }

  @Test
  void anAttackStartedWhileOffHoldsNothingEvenIfTurnedOnMidway() {
    enabled.set(false);
    LivingEntity target = target(30);
    AttackUnregisteredEvent attack = attack(target, null, 4);
    indicators.onAttackStart(attack);
    enabled.set(true);
    IndicatorDisplayEvent event = new IndicatorDisplayEvent(target, "&f4", IndicatorType.DAMAGE);
    indicators.onIndicator(event);
    indicators.onAttackEnd(attack);

    assertFalse(event.isCancelled());
  }

  @Test
  void anUnpairedEndIsIgnored() {
    assertDoesNotThrow(() -> indicators.onAttackEnd(attack(target(1), null, 1)));
  }

  @Test
  void theReplayedIndicatorIsNotHeldAgain() {
    LivingEntity target = target(30);
    hit(attack(target, null, 4), "&f4");
    List<IndicatorDisplayEvent> replays = new ArrayList<>();
    shown.duringShow = () -> {
      // MythicLib fires IndicatorDisplayEvent again inside displayIndicator, possibly inside another attack
      indicators.onAttackStart(attack(target, null, 1));
      IndicatorDisplayEvent replay = new IndicatorDisplayEvent(target, "&f2", IndicatorType.DAMAGE);
      indicators.onIndicator(replay);
      replays.add(replay);
    };

    when(target.getHealth()).thenReturn(28.0);
    tick();

    assertFalse(replays.get(0).isCancelled());
    // The attack inside the replay is measured on the following tick
    assertEquals(1, ticks.size());
  }

  @Test
  void flyOutHeadsAwayFromTheAttackerLikeMythicLibs() {
    LivingEntity target = target(30);
    hit(attack(target, attackerAt(0, -2), 4), "&f4");
    when(target.getHealth()).thenReturn(26.0);
    tick();

    Vector away = shown.directions.get(0);
    assertEquals(0, away.getX(), 1e-9);
    assertEquals(1, away.getZ(), 1e-9);
    assertEquals(0, away.getY(), 1e-9);
  }

  @Test
  void flyOutIsRandomWithoutAnAttackerOrWhenItStandsOnTheTarget() {
    random = 0.25;
    LivingEntity target = target(30);
    hit(attack(target, attackerAt(0, 0), 4), "&f4");
    when(target.getHealth()).thenReturn(26.0);
    tick();
    LivingEntity other = target(30);
    hit(attack(other, null, 4), "&f4");
    when(other.getHealth()).thenReturn(26.0);
    tick();

    for (Vector direction : shown.directions) {
      assertEquals(0, direction.getX(), 1e-9);
      assertEquals(1, direction.getZ(), 1e-9);
    }
  }

  /** The handlers only work in this order around MythicLib's NORMAL indicator listener. */
  @Test
  void handlersRunAroundMythicLibsIndicatorListener() throws Exception {
    assertHandler("onAttackStart", AttackUnregisteredEvent.class, EventPriority.LOWEST, false);
    assertHandler("onIndicator", IndicatorDisplayEvent.class, EventPriority.HIGHEST, true);
    assertHandler("onAttackEnd", AttackUnregisteredEvent.class, EventPriority.MONITOR, false);
    assertHandler("onHeal", EntityRegainHealthEvent.class, EventPriority.MONITOR, true);
    assertHandler("onDamage", EntityDamageEvent.class, EventPriority.MONITOR, true);
  }

  private static void assertHandler(String name, Class<?> event, EventPriority priority, boolean ignoreCancelled)
      throws Exception {
    Method method = RealDamageIndicators.class.getMethod(name, event);
    EventHandler handler = method.getAnnotation(EventHandler.class);
    assertNotNull(handler, name);
    assertEquals(priority, handler.priority(), name);
    assertEquals(ignoreCancelled, handler.ignoreCancelled(), name);
  }

  @Test
  void registerHooksTheServerSchedulerAndReadsTheSetting() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Server server = mock(Server.class);
    PluginManager manager = mock(PluginManager.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getPluginManager()).thenReturn(manager);
    when(server.getScheduler()).thenReturn(scheduler);
    boolean previous = Cache.accurateDamageIndicators;
    try {
      Cache.accurateDamageIndicators = true;
      RealDamageIndicators registered = RealDamageIndicators.register(plugin, new File("indicators.yml"));
      verify(manager).registerEvents(registered, plugin);

      registered.onAttackStart(attack(target(30), null, 4));
      ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler).runTask(eq(plugin), task.capture());
      task.getValue().run();

      Cache.accurateDamageIndicators = false;
      registered.onAttackStart(attack(target(30), null, 4));
      verifyNoMoreInteractions(scheduler);
    } finally {
      Cache.accurateDamageIndicators = previous;
    }
  }
}
