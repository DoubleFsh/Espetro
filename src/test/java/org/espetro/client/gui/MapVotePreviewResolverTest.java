package org.espetro.client.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MapVotePreviewResolverTest {

    @Test
    void resolvesMapFolderNamedPngFromClientRoot(@TempDir Path gameDir) throws Exception {
        Path image = gameDir.resolve("EsWorld/test_flat.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2, 3});

        assertEquals(image.toAbsolutePath().normalize(),
            MapVotePreviewResolver.resolve(gameDir, "test_flat"));
    }

    @Test
    void acceptsRequestedEsworldDirectoryCasing(@TempDir Path gameDir) throws Exception {
        Path image = gameDir.resolve("Esworld/arena.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1});

        assertEquals(image.toAbsolutePath().normalize(),
            MapVotePreviewResolver.resolve(gameDir, "arena"));
    }

    @Test
    void rejectsTraversalAndMissingFiles(@TempDir Path gameDir) {
        assertNull(MapVotePreviewResolver.resolve(gameDir, "../secret"));
        assertNull(MapVotePreviewResolver.resolve(gameDir, "missing"));
    }
}
