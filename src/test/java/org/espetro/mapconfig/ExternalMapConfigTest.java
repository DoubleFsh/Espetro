package org.espetro.mapconfig;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.espetro.team.FactionDataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalMapConfigTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge's pure-JUnit bootstrap can fail after vanilla registries
            // are ready because no live mod event bus exists.
        }
        assertNotNull(BuiltInRegistries.ENTITY_TYPE.get(
            ResourceLocation.fromNamespaceAndPath("minecraft", "minecart")));
    }

    @Test
    void dimensionIdsAreStableAndReservedNamespacesAreRejected() {
        assertEquals("espetro:desert_map", DimensionIdUtil.generate("Desert Map").toString());
        assertEquals(DimensionIdUtil.generate("测试地图"), DimensionIdUtil.generate("测试地图"));
        assertTrue(DimensionIdUtil.validateManualId("minecraft:arena", Set.of()).isPresent());
        assertTrue(DimensionIdUtil.validateManualId("espetro:arena", Set.of("espetro:arena")).isPresent());
        assertTrue(DimensionIdUtil.validateManualId("example:arena", Set.of()).isEmpty());
    }

    @Test
    void vehicleSpawnAndUnicodeSquadMarkerPreserveConfigurationOrder() {
        var veh = VehSpawnSnapshot.parse(JsonParser.parseString("""
            {
              "VehTypes": ["tank"],
              "spawn_points": {
                "tank": {
                  "first": {
                    "attack": {"x": 1, "y": 64, "z": 2},
                    "defend": {"x": 3, "y": 64, "z": 4}
                  },
                  "second": {
                    "attack": {"x": 5, "y": 64, "z": 6},
                    "defend": {"x": 7, "y": 64, "z": 8}
                  }
                }
              }
            }
            """).getAsJsonObject());

        assertTrue(veh.isValid(), () -> String.join("; ", veh.errors));
        assertEquals(List.of("first", "second"),
            veh.spawnPointsByType.get("tank").stream().map(VehSpawnSnapshot.SpawnPoint::id).toList());

        var squads = SquadTypesSnapshot.parse(JsonParser.parseString("""
            {"types": [{"id": "armor", "display_name": "🚜载具队"}]}
            """).getAsJsonObject());
        assertTrue(squads.isValid());
        assertEquals("🚜", squads.find("armor").firstDisplayCodePoint());
        assertEquals("", squads.find("none").firstDisplayCodePoint());
    }

    @Test
    void completeMapLoadsAndMissingWorldDataIsRejected(@TempDir Path temp) throws Exception {
        Path complete = createMap(temp.resolve("complete"), 1);
        ActiveMapConfig loaded = ActiveMapConfig.loadFromTemplate(
            "Complete", "complete",
            ResourceLocation.fromNamespaceAndPath("espetro", "complete"), complete);
        assertTrue(loaded.usable, () -> String.join("; ", loaded.rejectionReasons));

        Path missingLevel = createMap(temp.resolve("missing_level"), 1);
        Files.delete(missingLevel.resolve("level.dat"));
        ActiveMapConfig rejected = ActiveMapConfig.loadFromTemplate(
            "Missing", "missing_level",
            ResourceLocation.fromNamespaceAndPath("espetro", "missing_level"), missingLevel);
        assertFalse(rejected.usable);
        assertTrue(rejected.rejectionReasons.stream().anyMatch(reason -> reason.contains("level.dat")));

        Path missingPoints = createMap(temp.resolve("missing_points"), 1);
        Files.delete(missingPoints.resolve("EsConfig/CapturePoints.json"));
        ActiveMapConfig missingPointsConfig = ActiveMapConfig.loadFromTemplate(
            "Missing points", "missing_points",
            ResourceLocation.fromNamespaceAndPath("espetro", "missing_points"), missingPoints);
        assertFalse(missingPointsConfig.usable);
        assertTrue(missingPointsConfig.rejectionReasons.stream()
            .anyMatch(reason -> reason.contains("CapturePoints.json")));
    }

    @Test
    void bundledFlatExampleProvidesItsOwnFlatDimensionGenerator(@TempDir Path temp) throws Exception {
        Path levelDat = temp.resolve("level.dat");
        copyBundledLevelDat(levelDat);
        var dimension = LevelDatDimensionReader.readDimensionJson(levelDat);
        assertEquals("minecraft:overworld", dimension.get("type").getAsString());
        var generator = dimension.getAsJsonObject("generator");
        assertEquals("minecraft:flat", generator.get("type").getAsString());

        var layers = generator.getAsJsonObject("settings").getAsJsonArray("layers");
        int totalHeight = 0;
        for (var layer : layers) {
            totalHeight += layer.getAsJsonObject().get("height").getAsInt();
        }
        // Overworld starts at Y=-64, so 129 blocks place the grass surface at Y=64.
        assertEquals(129, totalHeight);
        assertEquals("minecraft:bedrock",
            layers.get(0).getAsJsonObject().get("block").getAsString());
        assertEquals("minecraft:grass_block",
            layers.get(layers.size() - 1).getAsJsonObject().get("block").getAsString());
    }

    @Test
    void factionVehicleTypesNormalizeAndSlotOverflowIsFiltered(@TempDir Path temp) throws Exception {
        ActiveMapConfig map = ActiveMapConfig.loadFromTemplate(
            "Arena", "arena",
            ResourceLocation.fromNamespaceAndPath("espetro", "arena"),
            createMap(temp.resolve("arena"), 1));
        assertTrue(map.usable);

        Path compatible = temp.resolve("compatible.json");
        Files.writeString(compatible, factionJson(
            "[\"minecraft:minecart\"]", "TANK", "Tank"), StandardCharsets.UTF_8);
        FactionDataLoader loader = new FactionDataLoader();
        loader.loadExternalFrozen(Map.of("compatible", compatible));
        assertTrue(loader.isCompatibleWithMap("compatible", map));
        FactionDataLoader.ClassKitData[] classes =
            loader.getClassesForFaction("compatible");
        assertEquals(1, classes.length);
        assertEquals(4, classes[0].teammatesNeed);

        Path overflow = temp.resolve("overflow.json");
        Files.writeString(overflow, factionJson(
            "[\"minecraft:minecart\", \"minecraft:minecart\"]", "tank", "tank"),
            StandardCharsets.UTF_8);
        loader.loadExternalFrozen(Map.of("overflow", overflow));
        assertFalse(loader.isCompatibleWithMap("overflow", map));
    }

    private static Path createMap(Path root, int tankPoints) throws Exception {
        Files.createDirectories(root.resolve("region"));
        copyBundledLevelDat(root.resolve("level.dat"));
        Path config = Files.createDirectories(root.resolve("EsConfig"));
        write(config, "game.json", "{}");
        write(config, "spawn_points.json", """
            {"spawnPoints":{
              "ATTACK":{"x":1,"y":64,"z":1,"yaw":0},
              "DEFEND":{"x":10,"y":64,"z":10,"yaw":180}
            }}
            """);
        write(config, "outposts.json", "{\"outposts\":[]}");
        write(config, "bastion.json", "{\"bastion\":{}}");
        write(config, "logistics.json", "{\"logistics\":{}}");
        write(config, "team_pack.json", "{\"team_pack\":{}}");
        write(config, "SquadTypes.json", "{\"types\":[]}");
        write(config, "TacticalMap.json", """
            {
              "topLeftX":-512,"topLeftZ":-512,
              "bottomRightX":512,"bottomRightZ":512,
              "initialRange":512,"minimumRange":64,
              "backgroundImage":"","showGrid":true,"showLabels":true
            }
            """);
        write(config, "CapturePoints.json", """
            {
              "totalBatches":1,
              "endBehavior":"terminate",
              "teamReinforcements":{"ATTACK":280,"DEFEND":1200},
              "plannedPoints":[{
                "name":"A","batch":1,
                "pos1":{"x":-4,"y":60,"z":-4},
                "pos2":{"x":4,"y":72,"z":4}
              }]
            }
            """);

        StringBuilder points = new StringBuilder();
        for (int i = 0; i < tankPoints; i++) {
            if (i > 0) {
                points.append(',');
            }
            points.append("""
                {
                  "id":"tank_%d",
                  "attack":{"x":%d,"y":64,"z":1},
                  "defend":{"x":%d,"y":64,"z":10}
                }
                """.formatted(i, i + 1, i + 10));
        }
        write(config, "VehSpawn.json",
            "{\"VehTypes\":[\"tank\"],\"spawn_points\":{\"tank\":[" + points + "]}}");
        return root;
    }

    private static String factionJson(String entities, String declaredType, String vehicleKey) {
        return """
            {
              "VehTypes": ["%s"],
              "faction": {
                "name": "Test",
                "faction_id": "TEST_SIDE",
                "team": "ATTACK"
              },
              "vehicles": {
                "%s": {
                  "entity": %s,
                  "per_max_count": 1,
                  "respawn_minutes": 5
                }
              },
              "classes": {
                "rifle": {
                  "name": "Rifle",
                  "icon": "rifleman",
                  "maxPlayers": 1,
                  "teammates_need": 4,
                  "commands": ["minecraft:bread 1"]
                }
              }
            }
            """.formatted(declaredType, vehicleKey, entities);
    }

    private static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static void copyBundledLevelDat(Path target) throws Exception {
        try (InputStream input = ExternalMapConfigTest.class.getResourceAsStream(
            "/espetro_examples/EsWorld/test_flat/level.dat")) {
            assertNotNull(input, "bundled test_flat level.dat");
            Files.copy(input, target);
        }
    }
}
