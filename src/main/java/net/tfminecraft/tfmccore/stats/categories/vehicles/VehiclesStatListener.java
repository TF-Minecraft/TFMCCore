package net.tfminecraft.tfmccore.stats.categories.vehicles;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.tfminecraft.vehicleframework.events.VehicleRemoveEvent;

public final class VehiclesStatListener implements Listener {
    private final VehiclesStatMain main;

    public VehiclesStatListener(VehiclesStatMain main) {
        this.main = main;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleRemove(VehicleRemoveEvent event) {
        main.handle(event);
    }
}
