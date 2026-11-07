package net.tfminecraft.tfmccore.health;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.StatInstance;
import io.lumine.mythic.lib.api.stat.modifier.StatModifier;
import io.lumine.mythic.lib.player.modifier.ModifierType;
import net.tfminecraft.tfmccore.cache.Cache;

/**
 * Cuts the healing of anyone under the Wounded status.
 *
 * <p>Wounded is a MythicMobs aura that TFMC class skills give ({@code tfmc_status_skills.yml}).
 * Heals that reach {@link EntityRegainHealthEvent} use the amount it ends with: vanilla
 * regeneration and food, MythicLib's {@code heal} (lifesteal and spell vampirism) and MythicMobs'
 * {@code heal}, which fires its own heal event and then this one. So one listener covers them, and
 * none is cut twice.
 *
 * <p>MMOCore's health regeneration sets the health directly and fires no heal event, so for Wounded
 * players this also scales the two MythicLib stats it reads ({@link #REGEN_STATS}) by the same
 * multiplier, checked every {@link #PERIOD} ticks. The modifier is taken off when the status ends,
 * at quit and on disable. Absorption shields are not healing and stay as they are.
 *
 * <p>Regeneration is cut once only because MMOCore 1.13.1 never registers MythicLib's
 * {@code HealthUpdateEventSupplier}, so its bridge from regeneration to {@link EntityRegainHealthEvent}
 * never runs. Re-check this after an MMOCore update: with that bridge, the stat modifier and the
 * listener would both cut regeneration.
 */
public final class WoundedHealing implements Listener {

    /** The MythicMobs aura name of the status. */
    static final String AURA = "Wounded";
    /** The MythicLib stats MMOCore health regeneration reads: flat per second, and % of max health. */
    static final String[] REGEN_STATS = {"HEALTH_REGENERATION", "MAX_HEALTH_REGENERATION"};
    /** Key of the regeneration modifier. */
    static final String KEY = "tfmccore_wounded";
    /** 5 ticks: the regeneration cut starts and ends within a quarter of a second of the status. */
    static final long PERIOD = 5L;

    /** Scales a player's regeneration stats; factor 1 takes the scaling off. False if it could not yet. */
    interface RegenScale {
        boolean set(Player player, double factor);
    }

    private final Predicate<Entity> wounded;
    private final DoubleSupplier multiplier;
    private final Supplier<Collection<? extends Player>> players;
    private final RegenScale regen;
    private final Set<UUID> scaled = new HashSet<>();
    private double applied = 1.0;

    WoundedHealing(Predicate<Entity> wounded, DoubleSupplier multiplier,
            Supplier<Collection<? extends Player>> players, RegenScale regen) {
        this.wounded = wounded;
        this.multiplier = multiplier;
        this.players = players;
        this.regen = regen;
    }

    /** Listens for heals and checks regeneration. Needs MythicMobs (the status) and MythicLib (the stats). */
    public static WoundedHealing register(JavaPlugin plugin) {
        WoundedHealing healing = new WoundedHealing(WoundedHealing::hasAura, () -> Cache.woundedHealingMultiplier,
                () -> plugin.getServer().getOnlinePlayers(), WoundedHealing::scaleRegen);
        plugin.getServer().getPluginManager().registerEvents(healing, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, healing::tick, PERIOD, PERIOD);
        return healing;
    }

    /** Read-only: getAuraRegistry would create and keep a registry for every entity it is asked about. */
    static boolean hasAura(Entity entity) {
        return MythicBukkit.inst().getSkillManager().getAuraManager().getHasAura(BukkitAdapter.adapt(entity), AURA);
    }

    static boolean scaleRegen(Player player, double factor) {
        MMOPlayerData data = MMOPlayerData.getOrNull(player);
        if (data == null) return false; // MythicLib has not loaded the player yet; the next tick tries again
        for (String stat : REGEN_STATS) {
            StatInstance instance = data.getStatMap().getInstance(stat);
            instance.remove(KEY);
            if (factor < 1.0) instance.addModifier(new StatModifier(KEY, stat, (factor - 1.0) * 100.0, ModifierType.COMPOUND));
        }
        return true;
    }

    private double factor() {
        double value = multiplier.getAsDouble();
        if (Double.isNaN(value)) return 1.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** HIGH: after plugins that change heals at NORMAL, before MONITOR listeners read the result. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        double factor = factor();
        if (factor >= 1.0 || !wounded.test(event.getEntity())) return;
        event.setAmount(event.getAmount() * factor);
    }

    /** Gives every Wounded player the regeneration cut and takes it off the others that had it. */
    void tick() {
        double factor = factor();
        boolean changed = factor != applied; // a config reload changed the multiplier
        applied = factor;
        Set<UUID> online = new HashSet<>();
        for (Player player : players.get()) {
            UUID id = player.getUniqueId();
            online.add(id);
            boolean cut = factor < 1.0 && wounded.test(player);
            if (cut && (!scaled.contains(id) || changed)) {
                if (regen.set(player, factor)) scaled.add(id);
                else scaled.remove(id); // not applied: try again on the next tick
            } else if (!cut && scaled.remove(id)) regen.set(player, 1.0);
        }
        scaled.retainAll(online);
    }

    /** Takes the cut off at quit, so it never waits in MythicLib's player data for the next login. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (scaled.remove(event.getPlayer().getUniqueId())) regen.set(event.getPlayer(), 1.0);
    }

    /** Takes the regeneration cut off everyone online who has it. Runs on disable. */
    public void close() {
        for (Player player : players.get()) {
            if (scaled.remove(player.getUniqueId())) regen.set(player, 1.0);
        }
        scaled.clear();
    }
}
