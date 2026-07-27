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
        assertTrue(supply.contains("dragonrise_reforge', 'ammo_supply_station"));

        String artillery = read("server_scripts/00_espetro_artillery_155.js");
        assertTrue(artillery.contains("scheduleInTicks"));
        assertTrue(artillery.contains("getBattlefieldSessionId"));
        assertFalse(artillery.contains("ServerEvents.tick"));

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
