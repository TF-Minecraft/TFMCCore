package net.tfminecraft.tfmccore.focus;

import java.util.ArrayList;
import java.util.List;

/** Read-only compatibility snapshot for released consumers; RPCharacters owns configuration. */
@Deprecated
public final class FocusConfig {

    public static int max = 150;
    public static int basePerHour = 10;
    public static long regenIntervalTicks = 72000L;
    public static boolean offlineRegen = true;
    public static final List<RegenBonus> regenBonuses = new ArrayList<>();

    private FocusConfig() {}

    public static void refresh() {
        max = net.tfminecraft.rpcharacters.focus.FocusConfig.max;
        basePerHour = net.tfminecraft.rpcharacters.focus.FocusConfig.basePerHour;
        regenIntervalTicks = net.tfminecraft.rpcharacters.focus.FocusConfig.regenIntervalTicks;
        offlineRegen = net.tfminecraft.rpcharacters.focus.FocusConfig.offlineRegen;
        regenBonuses.clear();
        for (var bonus : net.tfminecraft.rpcharacters.focus.FocusConfig.regenBonuses) {
            regenBonuses.add(new RegenBonus(bonus.mmocoreId, bonus.extraPerHourPerPoint));
        }
    }

    public static final class RegenBonus {
        public final String mmocoreId;
        public final double extraPerHourPerPoint;

        public RegenBonus(String mmocoreId, double extraPerHourPerPoint) {
            this.mmocoreId = mmocoreId;
            this.extraPerHourPerPoint = extraPerHourPerPoint;
        }
    }
}
