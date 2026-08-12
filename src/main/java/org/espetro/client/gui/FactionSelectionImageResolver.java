package org.espetro.client.gui;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Safely resolves a selection_image relative to the client's EsFactions directory. */
final class FactionSelectionImageResolver {

    private FactionSelectionImageResolver() {
    }

    static Path resolveClientFile(Path gameDir, String selectionImage) {
        if (gameDir == null || selectionImage == null || selectionImage.isBlank()
            || selectionImage.contains(":")) {
            return null;
        }
        Path root = gameDir.resolve("EsFactions").toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = root.resolve(selectionImage.trim()).normalize();
        } catch (Exception ignored) {
            return null;
        }
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            return null;
        }
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ? candidate : null;
    }
}
