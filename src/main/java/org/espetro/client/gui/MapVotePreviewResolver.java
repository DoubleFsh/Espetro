package org.espetro.client.gui;

import org.espetro.mapconfig.PathSafety;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Resolves client-local map-vote preview images without trusting packet paths. */
final class MapVotePreviewResolver {
    private static final List<String> ROOT_NAMES = List.of("Esworld", "EsWorld");

    private MapVotePreviewResolver() {
    }

    /**
     * Resolve {@code <gameDir>/EsWorld/<mapFolder>.png}. The alternate
     * {@code Esworld} casing is accepted for installations that use it.
     */
    static Path resolve(Path gameDir, String mapFolder) {
        if (gameDir == null || PathSafety.validateMapFolderName(mapFolder).isPresent()) {
            return null;
        }
        String fileName = mapFolder.trim() + ".png";
        for (String rootName : ROOT_NAMES) {
            Path root = gameDir.resolve(rootName).toAbsolutePath().normalize();
            Path candidate = root.resolve(fileName).normalize();
            if (!candidate.startsWith(root) || !candidate.getParent().equals(root)) {
                continue;
            }
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        return null;
    }
}
