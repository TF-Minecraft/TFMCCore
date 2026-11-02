package net.tfminecraft.tfmccore.health;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import io.lumine.mythic.lib.MythicLib;
import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.StatInstance;
import io.lumine.mythic.lib.api.stat.handler.StatHandler;
import io.lumine.mythic.lib.api.stat.handler.StatUpdateListener;

/**
 * Rounds every player's max health to a value a float holds exactly (see {@link ExactMaxHealth}).
 *
 * <p>MythicLib sums the whole MAX_HEALTH stat (class, attributes, items, gems, skill trees) and
 * puts {@code total - 20} on the vanilla attribute as one modifier, {@code mythiclib:main}. After
 * each MythicLib update this rewrites that modifier so the vanilla total lands on the 1/1024 grid,
 * which covers every source at once. Modifiers from other plugins stay as they are.
 */
public final class MaxHealthRounder implements StatUpdateListener {

    static final String STAT = "MAX_HEALTH";
    /** MythicLib's AttributeStatHandler key; MythicLib removes and re-adds it on every stat update. */
    static final NamespacedKey MYTHICLIB_KEY = new NamespacedKey("mythiclib", "main");
    /** 30 seconds: re-attach after a MythicLib reload and round changes made outside MythicLib. */
    static final long CHECK_PERIOD = 600L;

    private final Server server;
    private final Logger logger;
    private final Supplier<StatHandler> handlers;
    private final Function<Player, AttributeInstance> maxHealth;
    private StatHandler attached;
    private boolean closed;

    MaxHealthRounder(Server server, Logger logger, Supplier<StatHandler> handlers,
            Function<Player, AttributeInstance> maxHealth) {
        this.server = server;
        this.logger = logger;
        this.handlers = handlers;
        this.maxHealth = maxHealth;
    }

    public static MaxHealthRounder register(JavaPlugin plugin) {
        MaxHealthRounder rounder = new MaxHealthRounder(plugin.getServer(), plugin.getLogger(),
                () -> MythicLib.plugin.getStats().getHandler(STAT).orElse(null),
                player -> player.getAttribute(Attribute.MAX_HEALTH));
        if (plugin.getServer().getPluginManager().isPluginEnabled("MythicLib")) {
            rounder.check();
            plugin.getServer().getScheduler().runTaskTimer(plugin, rounder::check, CHECK_PERIOD, CHECK_PERIOD);
        }
        return rounder;
    }

    /** Listens on MythicLib's current MAX_HEALTH handler and rounds every online player. */
    void check() {
        if (closed) return;
        StatHandler handler = handlers.get();
        if (handler != null && handler != attached) {
            // A MythicLib reload builds new handlers; listeners on the old one no longer run
            handler.addUpdateListener(this);
            attached = handler;
            logger.info("Max health: rounding MythicLib MAX_HEALTH totals to 1/1024");
        }
        for (Player player : server.getOnlinePlayers()) round(player);
    }

    /** Runs after MythicLib's own listener has put the new total on the vanilla attribute. */
    @Override
    public void onUpdate(StatInstance instance) {
        // MythicLib cannot remove a listener, so after a TFMCCore disable this one stays and does nothing
        if (closed || !server.isPrimaryThread()) return;
        MMOPlayerData data = instance.getMap().getData();
        if (data.isOnline()) round(data.getPlayer());
    }

    public void close() {
        closed = true;
    }

    /** Rewrites MythicLib's modifier so the total is float-exact. True when it changed it. */
    boolean round(Player player) {
        AttributeInstance attribute = maxHealth.apply(player);
        if (attribute == null) return false;
        double total = attribute.getValue();
        if (!Double.isFinite(total) || ExactMaxHealth.isFloatExact(total)) return false;
        AttributeModifier mythicLib = null;
        double others = 0;
        for (AttributeModifier modifier : attribute.getModifiers()) {
            // A multiplier would scale the rounded sum again; leave such players alone
            if (modifier.getOperation() != AttributeModifier.Operation.ADD_NUMBER) return false;
            if (MYTHICLIB_KEY.equals(modifier.getKey())) mythicLib = modifier;
            else others += modifier.getAmount();
        }
        if (mythicLib == null) return false;
        double amount = ExactMaxHealth.modifierAmount(total, attribute.getBaseValue(), others);
        // Off-grid modifiers from elsewhere can keep the total inexact; do not rewrite the same amount again
        if (amount == mythicLib.getAmount()) return false;
        attribute.removeModifier(mythicLib);
        attribute.addModifier(new AttributeModifier(MYTHICLIB_KEY, amount,
                AttributeModifier.Operation.ADD_NUMBER, mythicLib.getSlotGroup()));
        return true;
    }
}
