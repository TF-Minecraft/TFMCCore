package net.tfminecraft.tfmccore.stats.categories.advancedcrafting;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.tfminecraft.advancedcrafting.lifecycle.AlloyCraftedEvent;
import net.tfminecraft.advancedcrafting.lifecycle.AlloyDiscoveredEvent;
import net.tfminecraft.advancedcrafting.lifecycle.ItemCraftedEvent;
import net.tfminecraft.advancedcrafting.lifecycle.SmithingHitEvent;

public final class AdvancedCraftingStatListener implements Listener {
    private final AdvancedCraftingStatMain main;

    public AdvancedCraftingStatListener(AdvancedCraftingStatMain main) {
        this.main = main;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAlloyDiscovered(AlloyDiscoveredEvent event) {
        main.handle(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAlloyCrafted(AlloyCraftedEvent event) {
        main.handle(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemCrafted(ItemCraftedEvent event) {
        main.handle(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithingHit(SmithingHitEvent event) {
        main.handle(event);
    }
}
