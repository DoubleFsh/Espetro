package org.espetro.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientAudioSetResolverTest {

    @TempDir
    Path tempDirectory;

    @Test
    void resolvesMusicAndSortedOggVoicesInsideSelectedPack() throws Exception {
        Path pack = Files.createDirectories(tempDirectory.resolve("EsAudio/test_pack"));
        Path music = Files.createDirectories(pack.resolve("music"));
        Path voice = Files.createDirectories(pack.resolve("voice/capture"));
        Path capturingVoice = Files.createDirectories(pack.resolve("voice/capturing"));
        Path losingVoice = Files.createDirectories(pack.resolve("voice/losing"));
        Files.write(music.resolve("entry.ogg"), new byte[] {1});
        Files.write(voice.resolve("b.OGG"), new byte[] {1});
        Files.write(voice.resolve("a.ogg"), new byte[] {1});
        Files.write(voice.resolve("ignored.mp3"), new byte[] {1});
        Files.write(capturingVoice.resolve("advance.ogg"), new byte[] {1});
        Files.write(losingVoice.resolve("warning.ogg"), new byte[] {1});

        Path resolvedPack = ClientAudioSetResolver.resolvePackRoot(tempDirectory, "test_pack");
        assertEquals(pack.toAbsolutePath().normalize(), resolvedPack);
        assertEquals(music.resolve("entry.ogg"), ClientAudioSetResolver.resolveMusic(
            resolvedPack, ClientAudioSetResolver.ENTRY_MUSIC));
        assertEquals(List.of(voice.resolve("a.ogg"), voice.resolve("b.OGG")),
            ClientAudioSetResolver.listVoiceFiles(resolvedPack,
                ClientAudioSetResolver.CAPTURE_VOICE));
        assertEquals(List.of(capturingVoice.resolve("advance.ogg")),
            ClientAudioSetResolver.listVoiceFiles(resolvedPack,
                ClientAudioSetResolver.CAPTURING_VOICE));
        assertEquals(List.of(losingVoice.resolve("warning.ogg")),
            ClientAudioSetResolver.listVoiceFiles(resolvedPack,
                ClientAudioSetResolver.LOSING_VOICE));
    }

    @Test
    void rejectsTraversalAndFilesOutsideAudioRoot() throws Exception {
        Files.createDirectories(tempDirectory.resolve("EsAudio/safe"));
        Files.createDirectories(tempDirectory.resolve("outside"));

        assertNull(ClientAudioSetResolver.resolvePackRoot(tempDirectory, "../outside"));
        assertNull(ClientAudioSetResolver.resolvePackRoot(tempDirectory, "safe/../../outside"));
        assertNull(ClientAudioSetResolver.resolveMusic(
            tempDirectory.resolve("EsAudio/safe"), "../outside.ogg"));
    }

    @Test
    void easterEggMusicFallsBackToNormalVictoryMusic() throws Exception {
        Path pack = Files.createDirectories(tempDirectory.resolve("EsAudio/test_pack"));
        Path normal = Files.createDirectories(pack.resolve("music")).resolve("victory.ogg");
        Files.write(normal, new byte[] {1});

        assertEquals(normal, ClientAudioSetResolver.resolveVictoryMusic(pack, true));
    }
}
