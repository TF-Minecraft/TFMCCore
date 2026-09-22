package net.tfminecraft.tfmccore.stats.categories.vehicles;

import java.util.Optional;

import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.data.VehicleRemovePayload;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.events.VehicleRemoveEvent;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import net.tfminecraft.tfmccore.stats.StatManager;

public final class VehiclesStatMain {
    private static final String CATEGORY_ID = "vehicles";

    private final VehiclesStatConfig config;

    public VehiclesStatMain(VehiclesStatConfig config) {
        this.config = config;
    }

    public void handle(VehicleRemoveEvent event) {
        if (!StatManager.isInitialized()) {
            return;
        }

        ActiveVehicle vehicle = event.getVehicle();
        if (vehicle == null) {
            return;
        }

        VehicleRemovePayload payload = event.getPayload();
        if (payload == null || !payload.isDeath()) {
            return;
        }

        Optional<VehicleDeath> deathCause = payload.getDeathCause();
        if (deathCause.isEmpty()) {
            return;
        }

        Optional<String> statKey = config.resolveStatKey(vehicle.getId(), deathCause.get());
        if (statKey.isEmpty()) {
            return;
        }

        Player captain = findCaptainPlayer(vehicle);
        if (captain == null) {
            return;
        }

        StatManager.getInstance().increment(captain.getUniqueId(), CATEGORY_ID, statKey.get(), 1L);
    }

    private static Player findCaptainPlayer(ActiveVehicle vehicle) {
        if (vehicle.getSeatHandler() == null) {
            return null;
        }

        for (Seat seat : vehicle.getSeatHandler().getSeats()) {
            if (!seat.isOccupied() || !seat.getType().equals(SeatType.CAPTAIN)) {
                continue;
            }
            if (seat.getEntity() instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
