package net.tfminecraft.tfmccore.movement;

import java.util.function.BooleanSupplier;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.tfmccore.cache.Cache;

/**
 * Soul Speed I for everyone standing on soul sand, removed once they leave it.
 *
 * <p>This replaces Main's old ConditionalEvents {@code soul_sand_stride} event, which ran six
 * {@code execute as @a ...} console commands every 5 ticks. It keeps that event's modifier key,
 * amounts and scoreboard tag, so players who had the effect when the event was removed lose it
 * the normal way. Stepping on needs soul sand at the feet; the effect stays while the feet, or
 * one or two blocks below them, are soul sand, so it is not lost mid-jump.
 */
public final class SoulSandStride {

    /** The modifier key the ConditionalEvents commands used ({@code tfmc:soulstride}). */
    static final NamespacedKey KEY = new NamespacedKey("tfmc", "soulstride");
    /** Marks players who have the modifiers; the ConditionalEvents commands used the same tag. */
    static final String TAG = "tfmc_soulstride";
    static final double EFFICIENCY = 1.0;
    static final double SPEED = 0.0405;
    /** 5 ticks (0.25 s), like the old event. */
    static final long PERIOD = 5L;

    private final Server server;
    private final BooleanSupplier enabled;

    SoulSandStride(Server server, BooleanSupplier enabled) {
        this.server = server;
        this.enabled = enabled;
    }

    /** Starts the check. With {@code soul-sand-stride: false} it only takes the effect off players who still have it. */
    public static SoulSandStride register(JavaPlugin plugin) {
        SoulSandStride stride = new SoulSandStride(plugin.getServer(), () -> Cache.soulSandStride);
        plugin.getServer().getScheduler().runTaskTimer(plugin, stride::tick, PERIOD, PERIOD);
        return stride;
    }

    /**
     * Takes the effect off everyone who has it. Runs on disable, before players are saved, so the
     * bonus never stays in player data without TFMCCore; the next start gives it back on soul sand.
     */
    public void close() {
        for (Player player : server.getOnlinePlayers()) {
            if (player.getScoreboardTags().contains(TAG)) remove(player);
        }
    }

    void tick() {
        boolean on = enabled.getAsBoolean();
        for (Player player : server.getOnlinePlayers()) {
            update(player, on);
        }
    }

    void update(Player player, boolean on) {
        boolean has = player.getScoreboardTags().contains(TAG);
        if (!has && !on) return;
        Block feet = player.getLocation().getBlock();
        if (!has) {
            if (feet.getType() == Material.SOUL_SAND) apply(player);
        } else if (!on || !(soulSand(feet) || soulSand(feet.getRelative(BlockFace.DOWN))
                || soulSand(feet.getRelative(BlockFace.DOWN, 2)))) {
            remove(player);
        }
    }

    private static boolean soulSand(Block block) {
        return block.getType() == Material.SOUL_SAND;
    }

    private static void apply(Player player) {
        set(player.getAttribute(Attribute.MOVEMENT_EFFICIENCY), EFFICIENCY);
        set(player.getAttribute(Attribute.MOVEMENT_SPEED), SPEED);
        player.addScoreboardTag(TAG);
    }

    private static void remove(Player player) {
        clear(player.getAttribute(Attribute.MOVEMENT_EFFICIENCY));
        clear(player.getAttribute(Attribute.MOVEMENT_SPEED));
        player.removeScoreboardTag(TAG);
    }

    private static void set(AttributeInstance attribute, double amount) {
        if (attribute == null) return;
        // A modifier left behind (a crash between the two writes) would make addModifier throw
        attribute.removeModifier(KEY);
        attribute.addModifier(new AttributeModifier(KEY, amount, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.ANY));
    }

    private static void clear(AttributeInstance attribute) {
        if (attribute != null) attribute.removeModifier(KEY);
    }
}
