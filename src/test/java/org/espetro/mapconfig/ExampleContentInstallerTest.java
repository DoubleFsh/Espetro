package org.espetro.mapconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleContentInstallerTest {

    @Test
    void exportsCompleteBundleWithoutOverwritingOperatorFiles(@TempDir Path gameDir)
            throws Exception {
        String operatorConfig = "{\"operator_owned\":true}";
        Files.writeString(gameDir.resolve("EsDimensions.json"), operatorConfig,
            StandardCharsets.UTF_8);

        ExampleContentInstaller.Result first =
            ExampleContentInstaller.installMissing(gameDir);

        assertTrue(first.successful(), () -> String.join("; ", first.errors()));
        assertTrue(first.installed() > 0);
        assertEquals(operatorConfig,
            Files.readString(gameDir.resolve("EsDimensions.json"), StandardCharsets.UTF_8));
        assertTrue(Files.size(gameDir.resolve("EsWorld/test_flat/level.dat")) > 0);
        assertTrue(Files.size(gameDir.resolve(
            "EsWorld/test_flat/region/r.0.0.mca")) > 0);
        assertTrue(Files.isDirectory(gameDir.resolve("EsWorld/test_flat/entities")));
        assertTrue(Files.isRegularFile(gameDir.resolve(
            "EsWorld/test_flat/EsConfig/TacticalMap.json")));
        assertTrue(Files.isRegularFile(gameDir.resolve(
            "EsWorld/test_flat/EsConfig/CapturePoints.json")));
        assertEquals(10, Files.list(gameDir.resolve("EsFactions"))
            .filter(path -> path.getFileName().toString().endsWith(".json"))
            .count());
        assertFalse(Files.exists(gameDir.resolve("EsFactions/pla_rapid_force.json")));

        ExampleContentInstaller.Result second =
            ExampleContentInstaller.installMissing(gameDir);
        assertTrue(second.successful(), () -> String.join("; ", second.errors()));
        assertEquals(0, second.installed());
        assertTrue(second.skipped() > 0);
        assertEquals(operatorConfig,
            Files.readString(gameDir.resolve("EsDimensions.json"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsTraversalAndAbsoluteManifestPaths() {
        assertFalse(ExampleContentInstaller.isSafeRelativePath("../escape.json"));
        assertFalse(ExampleContentInstaller.isSafeRelativePath("/absolute.json"));
        assertFalse(ExampleContentInstaller.isSafeRelativePath(".hidden"));
        assertFalse(ExampleContentInstaller.isSafeRelativePath("a\\b.json"));
        assertTrue(ExampleContentInstaller.isSafeRelativePath(
            "EsWorld/test_flat/EsConfig/game.json"));
    }
}
