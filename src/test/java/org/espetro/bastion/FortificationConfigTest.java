package org.espetro.bastion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FortificationConfigTest {

    @Test
    void loadsBoundedServiceSettingsAndRejectsUnsafeEntries(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fortifications.json");
        Files.writeString(file, """
            {
              "vehicle_service": {
                "main_base_radius": 9999,
                "station_radius": -5,
                "transfer_amount": 0,
                "transfer_interval_ticks": 999
              },
              "fortifications": [
                {
                  "id": "valid_station",
                  "display_name": "测试站",
                  "place_type": "block",
                  "block_id": "minecraft:barrel",
                  "usable_by": ["squad_leader"]
                },
                {
                  "id": "../invalid",
                  "place_type": "block",
                  "block_id": "minecraft:stone"
                }
              ]
            }
            """);

        try {
            FortificationConfig.loadFromPath(file);

            assertEquals(256.0, FortificationConfig.vehicleService().mainBaseRadius);
            assertEquals(1.0, FortificationConfig.vehicleService().stationRadius);
            assertEquals(1, FortificationConfig.vehicleService().transferAmount);
            assertEquals(200, FortificationConfig.vehicleService().transferIntervalTicks);
            assertNotNull(FortificationConfig.get("valid_station"));
            assertNull(FortificationConfig.get("../invalid"));
        } finally {
            FortificationConfig.loadDefaults();
        }
    }
}
