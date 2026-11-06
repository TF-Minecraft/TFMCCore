package net.tfminecraft.tfmccore.indicators;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
 * target lost 6.21 and 3.70. This holds each damage indicator back for one
 * tick, measures how much health and absorption the target lost, rewrites the number, and then
 * shows it through MythicLib's own indicator code, so the icons, crit styling and motion stay
 * MythicLib's. When one hit shows several numbers (MythicLib's {@code group_by: packet}, on
 * separate lines or joined into one), each is scaled so they add up to what was lost. Hits on the same target
 * in one tick share one measurement. A hit that took nothing shows nothing.
 */
public final class RealDamageIndicators implements Listener {

    /** Below this much lost health no indicator is shown; MythicLib's own floor is 0.1. */
    static final double MIN_SHOWN = 0.05;

    /** Shows a rewritten indicator. MythicLibDisplay is the server's; tests use their own. */
    interface Display {
        String format(double value);

        void show(Entity entity, String line, Vector direction);
    }

    /** One target's hits in the current tick. */
    static final class Hits {
        final LivingEntity target;
        final double before;
        double predicted;
        int attacks;
        Entity attacker;
        final List<String> lines = new ArrayList<>();

        Hits(LivingEntity target, double before) {
            this.target = target;
            this.before = before;
        }
    }

    private final BooleanSupplier enabled;
    private final Consumer<Runnable> nextTick;
    private final Display display;
    private final DoubleSupplier random;
    private final Map<UUID, Hits> pending = new HashMap<>();
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
        UUID id = target.getUniqueId();
        Hits hits = pending.get(id);
        if (hits == null) {
            hits = new Hits(target, remaining(target));
            pending.put(id, hits);
            nextTick.accept(() -> flush(id));
        }
        EntityDamageEvent damage = event.toBukkit();
        hits.predicted += damage.getFinalDamage();
        hits.attacks++;
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

    /** One tick later: every reduction has been applied, so the health difference is the real damage. */
    void flush(UUID id) {
        Hits hits = pending.remove(id);
        if (hits == null || hits.lines.isEmpty()) return;
        double lost = hits.before - remaining(hits.target);
        if (lost < MIN_SHOWN) return;
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
        for (String line : hits.lines) lines.add(IndicatorNumbers.rewrite(line, value -> value * scale, display::format));
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
        return target.isDead() ? 0 : target.getHealth() + target.getAbsorptionAmount();
    }
}
