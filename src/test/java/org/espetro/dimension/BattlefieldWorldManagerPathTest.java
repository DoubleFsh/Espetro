package org.espetro.dimension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattlefieldWorldManagerPathTest {

    @Test
    void acceptsCustomDimensionInsideSelectedUnicodeSave(@TempDir Path temp) throws Exception {
        Path save = Files.createDirectories(temp.resolve("saves").resolve("新的世界"));
        Path dimension = Files.createDirectories(
            save.resolve("dimensions").resolve("espetro").resolve("test_flat"));

        assertEquals(
            dimension.toAbsolutePath().normalize(),
            BattlefieldWorldManager.validateDimensionDirectory(save, dimension));
    }

    @Test
    void rejectsContainerRootAndSiblingPaths(@TempDir Path temp) throws Exception {
        Path saves = Files.createDirectories(temp.resolve("saves"));
        Path save = Files.createDirectories(saves.resolve("新的世界"));
        Path dimension = Files.createDirectories(
            save.resolve("dimensions").resolve("espetro").resolve("test_flat"));

        // Reproduces the old getWorldDir() bug: it returns the saves container.
        assertThrows(IllegalStateException.class,
            () -> BattlefieldWorldManager.validateDimensionDirectory(saves, dimension));
        assertThrows(IllegalStateException.class,
            () -> BattlefieldWorldManager.validateDimensionDirectory(
                save, save.resolve("dimensions")));
        assertThrows(IllegalStateException.class,
            () -> BattlefieldWorldManager.validateDimensionDirectory(
                save, save.resolveSibling("other").resolve("dimensions/espetro/test_flat")));
    }

    @Test
    void rejectsDimensionSymlinkEscapingSelectedSave(@TempDir Path temp) throws Exception {
        Path save = Files.createDirectories(temp.resolve("saves").resolve("新的世界"));
        Path dimensions = Files.createDirectories(save.resolve("dimensions"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path namespaceLink = dimensions.resolve("espetro");
        Files.createSymbolicLink(namespaceLink, outside);

        assertThrows(IllegalStateException.class,
            () -> BattlefieldWorldManager.validateDimensionDirectory(
                save, namespaceLink.resolve("test_flat")));
    }

    @Test
    void replacesDestroyedSaveCopyWithoutChangingEsWorldTemplate(@TempDir Path temp)
        throws Exception {
        Path template = Files.createDirectories(temp.resolve("EsWorld").resolve("test_flat"));
        Map<String, String> pristineFiles = Map.of(
            "region/r.0.0.mca", "pristine terrain",
            "entities/r.0.0.mca", "pristine entities",
            "poi/r.0.0.mca", "pristine poi",
            "data/raids.dat", "pristine saved data",
            "EsConfig/map.json", "{\"name\":\"test_flat\"}"
        );
        for (var entry : pristineFiles.entrySet()) {
            write(template.resolve(entry.getKey()), entry.getValue());
        }
        write(template.resolve("level.dat"), "must not be imported");
        write(template.resolve("playerdata/player.dat"), "must not be imported");

        Path saveCopy = Files.createDirectories(
            temp.resolve("saves/world/dimensions/espetro/test_flat"));
        write(saveCopy.resolve("region/r.0.0.mca"), "destroyed terrain");
        write(saveCopy.resolve("entities/r.0.0.mca"), "round-only entities");
        write(saveCopy.resolve("region/orphan.mca"), "old round residue");
        write(saveCopy.resolve("round-only.tmp"), "old round residue");
        Path importing = saveCopy.resolveSibling("test_flat.importing");
        write(importing.resolve("stale.tmp"), "interrupted earlier import");

        Map<String, String> templateBefore = readFiles(template, pristineFiles);

        BattlefieldWorldManager.Result result =
            BattlefieldWorldManager.replaceSaveCopy(template, saveCopy, importing);

        assertTrue(result.success(), result.error());
        assertFalse(Files.exists(importing));
        assertFalse(Files.exists(saveCopy.resolve("region/orphan.mca")));
        assertFalse(Files.exists(saveCopy.resolve("round-only.tmp")));
        assertFalse(Files.exists(saveCopy.resolve("level.dat")));
        assertFalse(Files.exists(saveCopy.resolve("playerdata")));
        assertEquals(templateBefore, readFiles(template, pristineFiles));
        assertEquals("must not be imported", Files.readString(template.resolve("level.dat")));
        assertEquals("must not be imported",
            Files.readString(template.resolve("playerdata/player.dat")));
        for (var entry : pristineFiles.entrySet()) {
            assertEquals(entry.getValue(), Files.readString(saveCopy.resolve(entry.getKey())));
        }
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private static Map<String, String> readFiles(Path root, Map<String, String> expected)
        throws Exception {
        var result = new java.util.LinkedHashMap<String, String>();
        for (String relative : expected.keySet()) {
            result.put(relative, Files.readString(root.resolve(relative)));
        }
        return result;
    }
}
