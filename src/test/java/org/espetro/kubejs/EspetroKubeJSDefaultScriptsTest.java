package org.espetro.kubejs;

import dev.latvian.mods.rhino.Context;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EspetroKubeJSDefaultScriptsTest {
    private static final List<String> SCRIPTS = List.of(
        "startup_scripts/00_espetro_drone_detection.js",
        "startup_scripts/00_espetro_vehicle_supply_station.js",
        "startup_scripts/00_espetro_artillery_155.js",
        "server_scripts/00_espetro_drone_detection.js",
        "server_scripts/00_espetro_vehicle_supply_station.js",
        "server_scripts/00_espetro_artillery_155.js"
    );

    @Test
    void bundledCommanderScriptsExistAndParse() throws Exception {
        Context context = Context.enter();
        for (String script : SCRIPTS) {
            String source = read(script);
            assertNotNull(context.compileString(source, script, 1, null));
        }
    }

    @Test
    void bundledEffectsUseExpectedImplementations() throws Exception {
        String supply = read("server_scripts/00_espetro_vehicle_supply_station.js");
        // 技能用 /give + NBT 发放可放置物品，而不是 createEntity
        assertTrue(supply.contains("give ") && supply.contains("ammo_supply_station"),
            "vehicle supply script should use /give for the Dragonrise item");
        assertTrue(supply.contains("载具补给站"),
            "granted item should be named 载具补给站");
        assertTrue(supply.contains("display") || supply.contains("Name"),
            "give command should set display name NBT");
        assertFalse(supply.contains("level.createEntity") || supply.contains(".spawn()"),
            "vehicle supply must not spawn the station entity directly");
        assertFalse(supply.contains("commander.getDirection()"));

        String artillery = read("server_scripts/00_espetro_artillery_155.js");
        assertTrue(artillery.contains("ServerEvents.tick"));
        assertTrue(artillery.contains("EspetroArtilleryTasks"));
        assertTrue(artillery.contains("getBattlefieldSessionId"));
        assertFalse(artillery.contains("server.scheduleInTicks("));

        String drone = read("server_scripts/00_espetro_drone_detection.js");
        assertTrue(drone.contains("Espetro.isPlayerDeployed(target)"));
    }

    private static String read(String relativePath) throws Exception {
        String path = "/espetro_kubejs/" + relativePath;
        try (InputStream stream =
                 EspetroKubeJSDefaultScriptsTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
