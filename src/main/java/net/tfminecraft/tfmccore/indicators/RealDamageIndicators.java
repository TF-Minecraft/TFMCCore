package net.tfminecraft.tfmccore.indicators;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import io.lumine.mythic.lib.api.event.AttackUnregisteredEvent;
import io.lumine.mythic.lib.api.event.IndicatorDisplayEvent;
import io.lumine.mythic.lib.util.IndicatorConfig;
import net.tfminecraft.tfmccore.cache.Cache;

/**
 * Makes MythicLib's damage indicators show the health the target really lost.
 *
 * <p>MythicLib's number leaves out armour and anything else that cuts the damage after its listener
 * reads the event: on Dev a sword hit read 6.4 on bare skin and in full Mythril alike, while the
 * target lost 6.21 and 3.70. This holds each damage indicator back until the next tick, measures
 * how much health and absorption the target lost to the reported hits, rewrites the number, and
 * then shows it through MythicLib's own indicator code, so the icons, crit styling and motion stay
 * MythicLib's.
 *
 * <p>The loss is measured in segments: every reported hit, other damage event and heal on a pending
 * target ends the running segment and starts its own, so each change of health is put down to the
 * event that caused it (armour, absorption and overkill included). Only the reported hits' segments
 * count, so a regeneration tick or other damage in the same tick does not move the number. Health
 * changed without an event in that tick still counts towards the last hit. When one hit shows
 * several numbers (MythicLib's {@code group_by: packet}, on separate lines or joined into one),
 * each is scaled so they add up to what was lost. Hits on the same target in one tick share one
 * measurement. A hit that took nothing shows nothing, and a killing blow shows the health that
 * was left.
 */
public final class RealDamageIndicators implements Listener {

    /** Below this much lost health no indicator is shown; MythicLib's own floor is 0.1. */
    static final double MIN_SHOWN = 0.05;

    /** Shows a rewritten indicator. MythicLibDisplay is the server's; tests use their own. */
    interface Display {
        /** Called once before each tick's indicators, to pick up an edited config. */
        void refresh();

        String format(double value);

        void show(Entity entity, String line, Vector direction);
    }

    /** One target's hits in the current tick. */
    static final class Hits {
        final LivingEntity target;
        double predicted;
        int attacks;
        Entity attacker;
        final List<String> lines = new ArrayList<>();
        /** Damage events of the reported hits, so other damage to the target can be told apart. */
        final Set<EntityDamageEvent> reportedEvents = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Health and absorption at the start of the running segment. */
        private double mark;
        /** Whether the running segment belongs to a reported hit. */
        private boolean reportedSegment;
        /** Health and absorption lost in reported segments so far. */
        private double lost;

        Hits(LivingEntity target) {
            this.target = target;
            this.mark = remaining(target);
        }

        /** Ends the running segment and starts one owned by a reported hit or not. */
        void segment(boolean reported) {
            double now = remaining(target);
            if (reportedSegment) lost += mark - now;
            mark = now;
            reportedSegment = reported;
        }

        double lost() {
            segment(false);
            return lost;
        }
    }

    /** Marks an attack on the stack whose indicators are not held (setting off). */
    private static final Object NOT_HELD = new Object();

    private final BooleanSupplier enabled;
    private final Consumer<Runnable> nextTick;
    private final Display display;
    private final DoubleSupplier random;
    private final Logger logger;
    private final Map<UUID, Hits> pending = new LinkedHashMap<>();
    /** The MythicLib attacks being handled right now (their Hits, or NOT_HELD); MythicLib shows indicators inside them. */
    private final Deque<Object> attacks = new ArrayDeque<>();
    private boolean replaying;
    private boolean warned;

    RealDamageIndicators(BooleanSupplier enabled, Consumer<Runnable> nextTick, Display display, DoubleSupplier random,
            Logger logger) {
        this.enabled = enabled;
        this.nextTick = nextTick;
        this.display = display;
        this.random = random;
        this.logger = logger;
    }

    /** Needs MythicLib enabled; {@code indicators} is MythicLib's {@code indicators.yml}. */
    public static RealDamageIndicators register(JavaPlugin plugin, File indicators) {
        RealDamageIndicators listener = new RealDamageIndicators(
                () -> Cache.accurateDamageIndicators,
                task -> plugin.getServer().getScheduler().runTask(plugin, task),
                new MythicLibDisplay(indicators, IndicatorConfig::new, plugin.getLogger()),
                Math::random,
                plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        return listener;
    }

    /** Before MythicLib's indicator listener: start the hit's segment and add its predicted damage. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackStart(AttackUnregisteredEvent event) {
        if (!enabled.getAsBoolean()) {
            // Not held. Its damage stays out of a pending measurement of this target: it is not a
            // reported event, so onDamage closes the running segment as unreported before it lands.
            attacks.push(NOT_HELD);
            return;
        }
        LivingEntity target = event.getEntity();
        Hits hits = pending.get(target.getUniqueId());
        boolean fresh = hits == null;
        if (fresh) {
            hits = new Hits(target);
            pending.put(target.getUniqueId(), hits);
        }
        // Pushed before anything that can throw, so the MONITOR pop stays paired
        attacks.push(hits);
        // One shared flush per tick for every target hit in it
        if (fresh && pending.size() == 1) nextTick.accept(this::flush);
        EntityDamageEvent damage = event.toBukkit();
        hits.segment(true);
        hits.predicted += damage.getFinalDamage();
        hits.attacks++;
        hits.reportedEvents.add(damage);
        if (damage instanceof EntityDamageByEntityEvent byEntity) hits.attacker = byEntity.getDamager();
    }

    /** Holds back the damage indicators MythicLib shows for the attack being handled. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIndicator(IndicatorDisplayEvent event) {
        if (replaying || event.getType() != IndicatorDisplayEvent.IndicatorType.DAMAGE) return;
        if (!(attacks.peek() instanceof Hits hits)) return;
        if (!hits.target.getUniqueId().equals(event.getEntity().getUniqueId())) return;
        event.setCancelled(true);
        hits.lines.add(event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttackEnd(AttackUnregisteredEvent event) {
        if (!attacks.isEmpty()) attacks.pop();
    }

    /** A heal on a pending target starts a segment that is not counted. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        Hits hits = pending.get(event.getEntity().getUniqueId());
        if (hits != null) hits.segment(false);
    }

    /** Damage on a pending target starts a segment, counted only for the reported hits. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Hits hits = pending.get(event.getEntity().getUniqueId());
        if (hits != null) hits.segment(hits.reportedEvents.contains(event));
    }

    /** The next tick: every reduction has been applied, so the reported segments hold the real damage. */
    void flush() {
        List<Hits> due = new ArrayList<>(pending.values());
        pending.clear();
        boolean refreshed = false;
        for (Hits hits : due) {
            try {
                if (hits.lines.isEmpty()) continue;
                // Removed while alive (despawned, logged out, changed world): nothing to show it on
                if (!hits.target.isValid() && hits.target.getHealth() > 0) continue;
                double lost = hits.lost();
                if (lost < MIN_SHOWN) continue;
                if (!refreshed) {
                    display.refresh();
                    refreshed = true;
                }
                show(hits, lost);
            } catch (RuntimeException e) {
                // One target's failure must not drop the others' indicators
                if (!warned) {
                    warned = true;
                    logger.log(Level.WARNING, "Damage indicator failed; further failures are not logged", e);
                }
            }
        }
    }

    private void show(Hits hits, double lost) {
        replaying = true;
        try {
            for (String line : rewrite(hits, lost)) display.show(hits.target, line, direction(hits));
        } finally {
            replaying = false;
        }
    }

    private List<String> rewrite(Hits hits, double lost) {
        List<String> lines = new ArrayList<>();
        // One attack, one number: write the loss itself, not a scaled rounded number
        if (hits.attacks == 1 && hits.lines.size() == 1 && IndicatorNumbers.count(hits.lines.get(0)) == 1) {
            lines.add(IndicatorNumbers.rewrite(hits.lines.get(0), value -> lost, display::format));
            return lines;
        }
        double scale = hits.predicted > 0 ? lost / hits.predicted : 1.0;
        for (String line : hits.lines) {
            List<Double> values = IndicatorNumbers.values(line);
            // A part that scales down to nothing would read "0"
            if (!values.isEmpty() && values.stream().allMatch(value -> value * scale < MIN_SHOWN)) continue;
            lines.add(IndicatorNumbers.rewrite(line, value -> value * scale, display::format));
        }
        return lines;
    }

    /** MythicLib's direction: away from the attacker with a quarter-turn of spread, else any way. */
    private Vector direction(Hits hits) {
        if (hits.attacker != null) {
            Vector away = hits.target.getLocation().toVector()
                    .subtract(hits.attacker.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 0) {
                double angle = Math.atan2(away.getZ(), away.getX()) + Math.PI / 2 * (random.getAsDouble() - 0.5);
                return new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
        }
        double angle = random.getAsDouble() * Math.PI * 2;
        return new Vector(Math.cos(angle), 0, Math.sin(angle));
    }

    private static double remaining(LivingEntity target) {
        return target.getHealth() + target.getAbsorptionAmount();
    }
}
