package org.espetro.client.audio;

import org.espetro.audio.AudioPackId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Safe filesystem resolver for {@code <client game dir>/EsAudio/<audio_pack>}. */
public final class ClientAudioSetResolver {
    public static final String ROOT_DIRECTORY = "EsAudio";

    public static final String ENTRY_MUSIC = "entry.ogg";
    public static final String VICTORY_MUSIC = "victory.ogg";
    public static final String VICTORY_EASTER_EGG_MUSIC = "victory_easter_egg.ogg";
    public static final String DEFEAT_MUSIC = "defeat.ogg";

    public static final String ENTRY_ATTACK_VOICE = "entry_attack";
    public static final String ENTRY_DEFEND_VOICE = "entry_defend";
    public static final String CAPTURING_VOICE = "capturing";
    public static final String LOSING_VOICE = "losing";
    public static final String CAPTURE_VOICE = "capture";
    public static final String LOST_VOICE = "lost";
    public static final String VICTORY_VOICE = "victory";
    public static final String DEFEAT_VOICE = "defeat";

    private ClientAudioSetResolver() {
    }

    public static Path resolvePackRoot(Path gameDirectory, String audioPack) {
        String packId = AudioPackId.normalize(audioPack);
        if (gameDirectory == null || packId == null) return null;
        try {
            Path root = gameDirectory.resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
            Path packRoot = root.resolve(packId).normalize();
            if (!packRoot.startsWith(root) || !root.equals(packRoot.getParent())) return null;
            return Files.isDirectory(packRoot, LinkOption.NOFOLLOW_LINKS) ? packRoot : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static Path resolveMusic(Path packRoot, String fileName) {
        if (packRoot == null || fileName == null || fileName.contains("/") || fileName.contains("\\")) {
            return null;
        }
        Path musicDirectory = packRoot.resolve("music").normalize();
        if (!packRoot.equals(musicDirectory.getParent())
            || !Files.isDirectory(musicDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path candidate = musicDirectory.resolve(fileName).normalize();
        if (!musicDirectory.equals(candidate.getParent()) || !isOgg(candidate)) return null;
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ? candidate : null;
    }

    public static Path resolveVictoryMusic(Path packRoot, boolean easterEgg) {
        if (easterEgg) {
            Path easter = resolveMusic(packRoot, VICTORY_EASTER_EGG_MUSIC);
            if (easter != null) return easter;
        }
        Path normal = resolveMusic(packRoot, VICTORY_MUSIC);
        if (normal != null) return normal;
        return easterEgg ? null : resolveMusic(packRoot, VICTORY_EASTER_EGG_MUSIC);
    }

    public static List<Path> listVoiceFiles(Path packRoot, String voiceGroup) {
        if (packRoot == null || voiceGroup == null || voiceGroup.contains("/")
            || voiceGroup.contains("\\") || voiceGroup.equals(".") || voiceGroup.equals("..")) {
            return List.of();
        }
        Path voiceRoot = packRoot.resolve("voice").normalize();
        Path group = voiceRoot.resolve(voiceGroup).normalize();
        if (!packRoot.equals(voiceRoot.getParent()) || !voiceRoot.equals(group.getParent())
            || !Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(group)) {
            return files.filter(ClientAudioSetResolver::isOgg)
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(path ->
                    path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static boolean isOgg(Path path) {
        if (path == null || path.getFileName() == null) return false;
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg");
    }
}
