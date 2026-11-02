package net.tfminecraft.tfmccore.health;

/**
 * Max health values that survive Paper's float health storage.
 *
 * <p>Paper keeps a player's health as a float but reports max health as a double. MythicLib's
 * {@code Resources.setHealth} counts a player as full only when the stored health equals the
 * double max, so a max such as 23.6 (no exact float) never counts as full: MMOCore's regen task
 * then sets health every 2 ticks and each set sends an attribute packet. A max that a float holds
 * exactly stops that.
 */
public final class ExactMaxHealth {

    /**
     * 1/1024. Paper caps max health at 1024, so any multiple of this step up to the cap has at most
     * 21 significant bits and fits a float exactly. Sums and differences of such values (and of
     * coarser ones such as the 20 base or half-heart bonuses) are exact in double, whatever the order.
     */
    public static final double STEP = 0x1p-10;

    /** Sums such as 0.1 + 0.35 + 0.05 land a hair under a grid point; treat those as on it. */
    private static final double NOISE = 1e-6;

    private ExactMaxHealth() {
    }

    /** True when a float holds this value exactly, so Paper's stored health can equal it. */
    public static boolean isFloatExact(double value) {
        return (float) value == value;
    }

    /**
     * The multiple of {@link #STEP} at or just below the value: less than 1/1024 health lower, never
     * higher (bar float noise). Rounding up would let MMOCore's clamp listener, which runs before ours,
     * pull a full player down to the unrounded max and cost a heal on every stat update.
     */
    public static double round(double value) {
        return Math.floor(value / STEP + NOISE) * STEP;
    }

    /**
     * Amount for the one modifier we rewrite so that {@code base + others + amount} equals the
     * rounded {@code total}. With base and the other modifiers on the step grid the sum is exact.
     */
    public static double modifierAmount(double total, double base, double others) {
        return round(total) - base - others;
    }
}
