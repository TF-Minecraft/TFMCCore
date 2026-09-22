package net.tfminecraft.tfmccore.stats.categories.vehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.vehicleframework.enums.VehicleDeath;

class VehiclesStatConfigTest {
    @Test
    void resolveStatKeyAndLabels(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("vehiclestats.yml");
        Files.writeString(configPath, """
                groups:
                  plane:
                    vehicles:
                      - example_plane
                  ship:
                    vehicles:
                      - ironclad
                death-stats:
                  plane:
                    crash: planes_crashed
                  ship:
                    crash: ships_crashed
                    sink: ships_sunk
                labels:
                  planes_crashed: "Planes crashed"
                  ships_crashed: "Ships crashed"
                  ships_sunk: "Ships sunk"
                """);

        VehiclesStatConfig config = new VehiclesStatConfig();
        config.load(configPath.toFile());

        assertEquals("ships_sunk", config.resolveStatKey("ironclad", VehicleDeath.SINK).orElse(""));
        assertEquals("ships_crashed", config.resolveStatKey("ironclad", VehicleDeath.CRASH).orElse(""));
        assertTrue(config.resolveStatKey("unknown_vehicle", VehicleDeath.CRASH).isEmpty());
        assertEquals("Planes crashed", config.getLabel("planes_crashed"));
    }
}
