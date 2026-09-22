package net.tfminecraft.tfmccore.stats.categories.advancedcrafting;

import java.util.Locale;
import java.util.UUID;

import net.tfminecraft.advancedcrafting.lifecycle.AlloyCraftedEvent;
import net.tfminecraft.advancedcrafting.lifecycle.AlloyDiscoveredEvent;
import net.tfminecraft.advancedcrafting.lifecycle.ItemCraftedEvent;
import net.tfminecraft.advancedcrafting.lifecycle.SmithingHitEvent;
import net.tfminecraft.tfmccore.stats.StatManager;

public final class AdvancedCraftingStatMain {
    private static final String CATEGORY_ID = "advancedcrafting";

    public void handle(AlloyDiscoveredEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        UUID playerUuid = event.getPlayerUuid();
        if (playerUuid == null) {
            return;
        }

        StatManager.getInstance().increment(playerUuid, CATEGORY_ID, "alloys_discovered", 1L);
    }

    public void handle(AlloyCraftedEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        UUID playerUuid = event.getPlayerUuid();
        if (playerUuid == null) {
            return;
        }

        StatManager.getInstance().increment(playerUuid, CATEGORY_ID, "alloys_crafted", 1L);
    }

    public void handle(ItemCraftedEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        UUID playerUuid = event.getPlayerUuid();
        if (playerUuid == null) {
            return;
        }

        StatManager manager = StatManager.getInstance();
        manager.increment(playerUuid, CATEGORY_ID, "items_crafted", 1L);

        String categoryId = event.getCategoryId();
        if (categoryId == null || categoryId.isBlank()) {
            return;
        }

        String categoryKey = "items_crafted_" + categoryId.toLowerCase(Locale.ROOT);
        manager.increment(playerUuid, CATEGORY_ID, categoryKey, 1L);
    }

    public void handle(SmithingHitEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        UUID playerUuid = event.getPlayerUuid();
        if (playerUuid == null) {
            return;
        }

        String hitId = event.getHitId();
        if (hitId == null || hitId.isBlank()) {
            return;
        }

        String statKey = "hits_" + hitId.toLowerCase(Locale.ROOT);
        StatManager.getInstance().increment(playerUuid, CATEGORY_ID, statKey, 1L);
    }
}
