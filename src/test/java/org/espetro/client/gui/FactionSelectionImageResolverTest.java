package org.espetro.client.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FactionSelectionImageResolverTest {

    @Test
    void resolvesRelativeImageFromClientEsFactions(@TempDir Path gameDir) throws Exception {
        Path image = gameDir.resolve("EsFactions/images/attack.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});

        assertEquals(image.toAbsolutePath().normalize(),
            FactionSelectionImageResolver.resolveClientFile(gameDir, "images/attack.png"));
    }

    @Test
    void ignoresResourceLocationsAndMissingFiles(@TempDir Path gameDir) {
        assertNull(FactionSelectionImageResolver.resolveClientFile(
            gameDir, "espetro:textures/gui/factions/example.png"));
        assertNull(FactionSelectionImageResolver.resolveClientFile(gameDir, "missing.png"));
    }

    @Test
    void rejectsPathsOutsideClientEsFactions(@TempDir Path gameDir) throws Exception {
        Path outside = gameDir.resolve("secret.png");
        Files.write(outside, new byte[]{1});

        assertNull(FactionSelectionImageResolver.resolveClientFile(gameDir, "../secret.png"));
        assertNull(FactionSelectionImageResolver.resolveClientFile(gameDir,
            outside.toAbsolutePath().toString()));
    }
}
