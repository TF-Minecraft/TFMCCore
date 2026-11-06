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

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
 * how much health and absorption the target lost, rewrites the number, and then shows it through
 * MythicLib's own indicator code, so the icons, crit styling and motion stay MythicLib's.
 *
 * <p>Healing the target gets before then (regeneration, heal-on-hit: anything that fires
 * {@link EntityRegainHealthEvent}) and damage that MythicLib did not report are added back or taken
 * out, so only the reported hits are measured. Health set directly or absorption added in that
 * tick still lowers the number. When one hit
 * shows several numbers (MythicLib's {@code group_by: packet}, on separate lines or joined into
 * one), each is scaled so they add up to what was lost. Hits on the same target in one tick share
 * one measurement. A hit that took nothing shows nothing, and a killing blow shows the health
 * that was left.
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
        final double before;
        double predicted;
        int attacks;
        double healed;
        Entity attacker;
        final List<String> lines = new ArrayList<>();
        /** Damage events of the reported hits, so other damage to the target can be told apart. */
        final Set<EntityDamageEvent> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<EntityDamageEvent> damage = new ArrayList<>();

        Hits(LivingEntity target, double before) {
            this.target = target;
            this.before = before;
        }

        /** Damage the target took from events MythicLib did not report as attacks. */
        double unreported() {
            double total = 0;
            for (EntityDamageEvent event : damage) {
                if (!reported.contains(event)) total += Math.max(0, event.getFinalDamage());
            }
            return total;
        }
    }

    private final BooleanSupplier enabled;
    private final Consumer<Runnable> nextTick;
    private final Display display;
    private final DoubleSupplier random;
    private final Map<UUID, Hits> pending = new LinkedHashMap<>();
    /** Targets of the MythicLib attacks being handled right now; MythicLib shows indicators inside them. */
    private final Deque<LivingEntity> attacks = new ArrayDeque<>();
    private boolean replaying;

    RealDamageIndicators(BooleanSupplier enabled, Consumer<Runnable> nextTick, Display display, DoubleSupplier random) {
        this.enabled = enabled;
        this.nextTick = nextTick;
        this.display = display;
        this.random = random;
    }

    /** Needs MythicLib enabled; {@code indicators} is MythicLib's {@code indicators.yml}. */
    public static RealDamageIndicators register(JavaPlugin plugin, File indicators) {
        RealDamageIndicators listener = new RealDamageIndicators(
                () -> Cache.accurateDamageIndicators,
                task -> plugin.getServer().getScheduler().runTask(plugin, task),
                new MythicLibDisplay(indicators, IndicatorConfig::new),
                Math::random);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        return listener;
    }

    /** Before MythicLib's indicator listener: remember the target's health and the predicted damage. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackStart(AttackUnregisteredEvent event) {
        LivingEntity target = event.getEntity();
        // Always pushed, so the MONITOR pop stays paired even if the setting changes in between
        attacks.push(target);
        if (!enabled.getAsBoolean()) return;
        Hits hits = pending.get(target.getUniqueId());
        if (hits == null) {
            // One shared flush per tick for every target hit in it
            if (pending.isEmpty()) nextTick.accept(this::flush);
            hits = new Hits(target, remaining(target));
            pending.put(target.getUniqueId(), hits);
        }
        EntityDamageEvent damage = event.toBukkit();
        hits.predicted += damage.getFinalDamage();
        hits.attacks++;
        hits.reported.add(damage);
        if (damage instanceof EntityDamageByEntityEvent byEntity) hits.attacker = byEntity.getDamager();
    }

    /** Holds back the damage indicators MythicLib shows for the attack being handled. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIndicator(IndicatorDisplayEvent event) {
        if (replaying || event.getType() != IndicatorDisplayEvent.IndicatorType.DAMAGE) return;
        LivingEntity target = attacks.peek();
        if (target == null || !target.getUniqueId().equals(event.getEntity().getUniqueId())) return;
        Hits hits = pending.get(target.getUniqueId());
        if (hits == null) return;
        event.setCancelled(true);
        hits.lines.add(event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttackEnd(AttackUnregisteredEvent event) {
        if (!attacks.isEmpty()) attacks.pop();
    }

    /** Healing before the flush would hide damage, so it is added back. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        Hits hits = pending.get(event.getEntity().getUniqueId());
        if (hits == null) return;
        // Only what fits under max health is really regained
        AttributeInstance max = hits.target.getAttribute(Attribute.MAX_HEALTH);
        double room = max == null ? event.getAmount() : Math.max(0, max.getValue() - hits.target.getHealth());
        hits.healed += Math.max(0, Math.min(event.getAmount(), room));
    }

    /** Remembers every damage event on a pending target; the ones MythicLib did not report are taken out. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Hits hits = pending.get(event.getEntity().getUniqueId());
        if (hits != null) hits.damage.add(event);
    }

    /** The next tick: every reduction has been applied, so the health difference is the real damage. */
    void flush() {
        List<Hits> due = new ArrayList<>(pending.values());
        pending.clear();
        boolean refreshed = false;
        for (Hits hits : due) {
            if (hits.lines.isEmpty()) continue;
            // Removed while alive (despawned, logged out, changed world): no health was lost to show
            if (!hits.target.isValid() && hits.target.getHealth() > 0) continue;
            double lost = hits.before - remaining(hits.target) + hits.healed - hits.unreported();
            if (lost < MIN_SHOWN) continue;
            if (!refreshed) {
                display.refresh();
                refreshed = true;
            }
            replaying = true;
            try {
                for (String line : rewrite(hits, lost)) display.show(hits.target, line, direction(hits));
            } finally {
                replaying = false;
            }
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
