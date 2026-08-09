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

    @Test
    void loadsBuiltinProgressAndValidatedStructureBlueprint(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fortifications.json");
        Files.writeString(file, """
            {
              "builtin_construction": {
                "radio": {"required_progress": 600, "build_per_hit": 30, "remove_per_hit": 5},
                "hab": {"required_progress": 200, "build_per_hit": 5, "remove_per_hit": 5}
              },
              "fortifications": [{
                "id": "test_wall",
                "display_name": "测试墙",
                "place_type": "structure",
                "required_progress": 120,
                "build_per_hit": 6,
                "remove_per_hit": 3,
                "blocks": [
                  {"offset": [-1, 0, 0], "block_id": "minecraft:stone"},
                  {"offset": [0, 0, 0], "block_id": "minecraft:stone"},
                  {"offset": [1, 0, 0], "block_id": "minecraft:stone"}
                ],
                "usable_by": ["fireteam_leader"]
              }]
            }
            """);

        try {
            FortificationConfig.loadFromPath(file);
            assertEquals(600, FortificationConfig.radioConstruction().requiredProgress);
            assertEquals(30, FortificationConfig.radioConstruction().buildPerHit);
            assertEquals(200, FortificationConfig.habConstruction().requiredProgress);
            var wall = FortificationConfig.get("test_wall");
            assertNotNull(wall);
            assertEquals("structure", wall.placeType);
            assertEquals(3, wall.blocks.size());
            assertEquals(120, wall.requiredProgress);
            assertEquals(6, wall.buildPerHit);
            assertEquals(3, wall.removePerHit);
        } finally {
            FortificationConfig.loadDefaults();
        }
    }

    @Test
    void rejectsDuplicateStructureOffsets(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fortifications.json");
        Files.writeString(file, """
            {"fortifications": [{
              "id": "duplicate_wall",
              "place_type": "structure",
              "blocks": [
                {"offset": [0, 0, 0], "block_id": "minecraft:stone"},
                {"offset": [0, 0, 0], "block_id": "minecraft:dirt"}
              ]
            }]}
            """);
        try {
            FortificationConfig.loadFromPath(file);
            assertNull(FortificationConfig.get("duplicate_wall"));
            assertNotNull(FortificationConfig.get("sandbag_wall"));
        } finally {
            FortificationConfig.loadDefaults();
        }
    }
}
