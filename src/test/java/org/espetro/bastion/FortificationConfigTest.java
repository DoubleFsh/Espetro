package org.espetro.bastion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FortificationConfigTest {

    @Test
    void parsesV2AndCanonicalizesAlias(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fortifications.json");
        Files.writeString(file, root(definition("espetro:test_wall", "generic")));
        try {
            FortificationConfig.loadFromPath(file);
            var wall = FortificationConfig.get("test_wall");
            assertNotNull(wall);
            assertEquals("espetro:test_wall", wall.id);
            assertEquals("structure", wall.placement.type);
            assertEquals(120, wall.construction.requiredProgress);
            assertEquals(120, wall.durability.structuralValue);
            assertEquals(FortificationConfig.Behavior.GENERIC, wall.behaviorType);
        } finally {
            FortificationConfig.loadDefaults();
        }
    }

    @Test
    void anyInvalidOrDuplicateDefinitionRejectsWholeTransaction(@TempDir Path dir)
        throws Exception {
        Path invalid = dir.resolve("invalid.json");
        Files.writeString(invalid, root(definition("espetro:ok", "generic") + ","
            + definition("../escape", "generic")));
        FortificationConfig.loadFromPath(invalid);
        assertTrue(FortificationConfig.all().isEmpty());
        assertNotNull(FortificationConfig.getFailure());

        Path duplicate = dir.resolve("duplicate.json");
        Files.writeString(duplicate, root(definition("espetro:same", "generic") + ","
            + definition("same", "generic")));
        FortificationConfig.loadFromPath(duplicate);
        assertTrue(FortificationConfig.all().isEmpty());
        assertNotNull(FortificationConfig.getFailure());
        FortificationConfig.loadDefaults();
    }

    @Test
    void migratesKnownV1EntriesButNeverKeepsInlineShapeAsRuntimeTruth(@TempDir Path dir)
        throws Exception {
        Path file = dir.resolve("legacy.json");
        Files.writeString(file, """
            {
              "builtin_construction": {
                "radio": {"required_progress": 600, "build_per_hit": 30, "remove_per_hit": 5},
                "hab": {"required_progress": 200, "build_per_hit": 5, "remove_per_hit": 5}
              },
              "fortifications": [{
                "id": "sandbag_wall",
                "display_name": "旧沙袋",
                "place_type": "structure",
                "construction_cost": 99,
                "required_progress": 120,
                "blocks": [{"offset":[0,0,0],"block_id":"minecraft:stone"}]
              }]
            }
            """);
        try {
            FortificationConfig.loadFromPath(file);
            var wall = FortificationConfig.get("sandbag_wall");
            assertNotNull(wall);
            assertEquals("espetro:fortifications/sandbag_wall", wall.placement.template);
            assertEquals(99, wall.cost.construction);
            assertEquals(120, wall.construction.requiredProgress);
            assertEquals(600, FortificationConfig.radioConstruction().requiredProgress);
            assertEquals(200, FortificationConfig.habConstruction().requiredProgress);
        } finally {
            FortificationConfig.loadDefaults();
        }
    }

    @Test
    void bundledRegistryContainsExactlyTheFiveMigratedFortificationsAndNoRally() {
        FortificationConfig.loadDefaults();
        assertEquals(5, FortificationConfig.all().size());
        for (String id : new String[]{"radio", "hab", "ammo_crate",
            "vehicle_supply_station", "sandbag_wall"}) {
            assertNotNull(FortificationConfig.get(id), id);
        }
        assertNull(FortificationConfig.get("rally"));
        assertFalse(FortificationConfig.all().values().stream()
            .anyMatch(def -> def.placement == null));
        assertEquals("电台", FortificationConfig.get("radio").displayName);
        var station = FortificationConfig.get("vehicle_supply_station");
        assertEquals("entity", station.placement.type);
        assertEquals("dragonrise_reforge:ammo_supply_station", station.placement.entityId);
        assertEquals("player_facing", station.placement.yaw);
        assertEquals(0.5D, station.placement.spawnOffset[0]);
        assertEquals(0.0D, station.placement.spawnOffset[1]);
        assertEquals(0.5D, station.placement.spawnOffset[2]);
    }

    @Test
    void entityTypeIsAcceptedAsEntityIdSynonym(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("entity.json");
        Files.writeString(file, """
            {
              "schema_version": 2,
              "fortifications": [{
                "id": "espetro:custom_station",
                "display_name": "自定义站",
                "behavior": "generic",
                "placement": {
                  "type": "entity",
                  "entity_type": "minecraft:armor_stand",
                  "virtual_damageable_part": true,
                  "fallback_template": "espetro:fortifications/vehicle_supply_station_fallback"
                },
                "cost": {"construction": 1, "ammunition": 0},
                "construction": {"required_progress": 10, "build_per_hit": 5, "remove_per_hit": 5},
                "durability": {
                  "structural_value": 10,
                  "repair_per_hit": 5,
                  "damageable_structure_entities": [],
                  "damage_reduction": {"explosion": 0.0, "projectile": 0.0, "direct_break": 0.0}
                },
                "requirements": {"require_radio_range": false, "usable_by": ["commander"]}
              }]
            }
            """);
        try {
            FortificationConfig.loadFromPath(file);
            var def = FortificationConfig.get("custom_station");
            assertNotNull(def);
            assertEquals("minecraft:armor_stand", def.placement.entityId);
            assertEquals("player_facing", def.placement.yaw);
            assertEquals(3, def.placement.spawnOffset.length);
        } finally {
            FortificationConfig.loadDefaults();
        }
    }

    private static String root(String definitions) {
        return """
            {
              "schema_version": 2,
              "limits": {
                "max_template_blocks": 4096,
                "max_template_entities": 32,
                "max_template_axis": 64,
                "max_template_nbt_bytes": 2097152,
                "max_passenger_depth": 4
              },
              "fortifications": [%s]
            }
            """.formatted(definitions);
    }

    private static String definition(String id, String behavior) {
        return """
            {
              "id": "%s",
              "legacy_ids": ["test_wall"],
              "display_name": "测试墙",
              "icon": {"item":"minecraft:stone"},
              "behavior": "%s",
              "placement": {
                "type":"structure",
                "template":"espetro:fortifications/sandbag_wall",
                "origin_offset":[0,0,0],
                "pivot":[0,0,0],
                "rotation":"player_facing",
                "mirror":"none",
                "air_policy":"reject_non_replaceable",
                "include_entities":true,
                "palette_index":0
              },
              "cost":{"construction":10,"ammunition":0},
              "construction":{"required_progress":120,"build_per_hit":6,"remove_per_hit":3},
              "durability":{
                "structural_value":120,
                "repair_per_hit":6,
                "damageable_structure_entities":[],
                "damage_reduction":{"explosion":0.9,"projectile":0.9,"direct_break":0.0}
              },
              "requirements":{
                "require_radio_range":true,
                "usable_by":["fireteam_leader"]
              }
            }
            """.formatted(id, behavior);
    }
}
