package org.espetro.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.espetro.Espetro;
import org.espetro.network.AudioCuePacket;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Client lifecycle for formation music and voice channels. */
public final class ClientFormationAudioManager {
    private static final int DELAYED_VOICE_TICKS = 3 * 20;
    private static final Set<String> WARNED_MISSING = ConcurrentHashMap.newKeySet();

    private static ExternalOggSoundInstance music;
    private static ExternalOggSoundInstance voice;
    private static PendingVoice pendingVoice;
    private static boolean hadClientSession;

    private ClientFormationAudioManager() {
    }

    public static void handle(AudioCuePacket packet) {
        if (packet == null) return;
        if (packet.getCue() == AudioCuePacket.Cue.STOP) {
            stopAll();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Path packRoot = ClientAudioSetResolver.resolvePackRoot(
            minecraft.gameDirectory.toPath(), packet.getAudioPack());
        if (packRoot == null) {
            stopAll();
            warnOnce(packet.getAudioPack(),
                "找不到客户端音频套装目录 EsAudio/" + packet.getAudioPack());
            return;
        }

        switch (packet.getCue()) {
            case ENTRY_ATTACK -> playMusicThenVoice(packRoot,
                ClientAudioSetResolver.ENTRY_MUSIC,
                ClientAudioSetResolver.ENTRY_ATTACK_VOICE);
            case ENTRY_DEFEND -> playMusicThenVoice(packRoot,
                ClientAudioSetResolver.ENTRY_MUSIC,
                ClientAudioSetResolver.ENTRY_DEFEND_VOICE);
            case CAPTURED -> playImmediateVoice(packRoot,
                ClientAudioSetResolver.CAPTURE_VOICE);
            case LOST -> playImmediateVoice(packRoot,
                ClientAudioSetResolver.LOST_VOICE);
            case CAPTURING_POINT -> playImmediateVoice(packRoot,
                ClientAudioSetResolver.CAPTURING_VOICE);
            case LOSING_POINT -> playImmediateVoice(packRoot,
                ClientAudioSetResolver.LOSING_VOICE);
            case VICTORY -> playResult(packRoot, false,
                ClientAudioSetResolver.VICTORY_VOICE);
            case VICTORY_EASTER_EGG -> playResult(packRoot, true,
                ClientAudioSetResolver.VICTORY_VOICE);
            case DEFEAT -> playDefeat(packRoot);
            case STOP -> stopAll();
        }
    }

    public static void tick(Minecraft minecraft) {
        boolean inSession = minecraft != null && minecraft.player != null && minecraft.level != null;
        if (!inSession) {
            if (hadClientSession) stopAll();
            hadClientSession = false;
            return;
        }
        hadClientSession = true;

        // Formation music uses the RECORDS channel so it can be adjusted independently
        // from Minecraft's ambient background music.
        if (music != null) {
            minecraft.getMusicManager().stopPlaying();
            if (!minecraft.getSoundManager().isActive(music)) {
                music = null;
            }
        }

        if (pendingVoice != null && --pendingVoice.ticksRemaining <= 0) {
            PendingVoice ready = pendingVoice;
            pendingVoice = null;
            playRandomVoice(ready.packRoot, ready.voiceGroup);
        }
    }

    public static void stopAll() {
        pendingVoice = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (music != null) {
            minecraft.getSoundManager().stop(music);
            music = null;
        }
        if (voice != null) {
            minecraft.getSoundManager().stop(voice);
            voice = null;
        }
    }

    private static void playMusicThenVoice(Path packRoot, String musicFile, String voiceGroup) {
        pendingVoice = null;
        stopVoice();
        playMusic(ClientAudioSetResolver.resolveMusic(packRoot, musicFile),
            packRoot, "music/" + musicFile);
        pendingVoice = new PendingVoice(DELAYED_VOICE_TICKS, packRoot, voiceGroup);
    }

    private static void playResult(Path packRoot, boolean easterEgg, String voiceGroup) {
        pendingVoice = null;
        stopVoice();
        Path selected = ClientAudioSetResolver.resolveVictoryMusic(packRoot, easterEgg);
        playMusic(selected, packRoot, easterEgg
            ? "music/victory_easter_egg.ogg（缺失时回退 victory.ogg）"
            : "music/victory.ogg");
        pendingVoice = new PendingVoice(DELAYED_VOICE_TICKS, packRoot, voiceGroup);
    }

    private static void playDefeat(Path packRoot) {
        pendingVoice = null;
        stopVoice();
        playMusic(ClientAudioSetResolver.resolveMusic(
            packRoot, ClientAudioSetResolver.DEFEAT_MUSIC),
            packRoot, "music/" + ClientAudioSetResolver.DEFEAT_MUSIC);
        pendingVoice = new PendingVoice(DELAYED_VOICE_TICKS,
            packRoot, ClientAudioSetResolver.DEFEAT_VOICE);
    }

    private static void playImmediateVoice(Path packRoot, String voiceGroup) {
        pendingVoice = null;
        playRandomVoice(packRoot, voiceGroup);
    }

    private static void playMusic(Path file, Path packRoot, String expectedPath) {
        Minecraft minecraft = Minecraft.getInstance();
        if (music != null) minecraft.getSoundManager().stop(music);
        music = null;
        if (file == null) {
            warnOnce(packRoot + "/" + expectedPath,
                "音频套装缺少 " + expectedPath + ": " + packRoot);
            return;
        }
        minecraft.getMusicManager().stopPlaying();
        music = new ExternalOggSoundInstance(file, SoundSource.RECORDS, "music");
        minecraft.getSoundManager().play(music);
    }

    private static void playRandomVoice(Path packRoot, String voiceGroup) {
        stopVoice();
        List<Path> files = ClientAudioSetResolver.listVoiceFiles(packRoot, voiceGroup);
        if (files.isEmpty()) {
            warnOnce(packRoot + "/voice/" + voiceGroup,
                "语音目录为空或不存在: " + packRoot.resolve("voice").resolve(voiceGroup));
            return;
        }
        Path selected = files.get(ThreadLocalRandom.current().nextInt(files.size()));
        voice = new ExternalOggSoundInstance(selected, SoundSource.VOICE, "voice");
        Minecraft.getInstance().getSoundManager().play(voice);
    }

    private static void stopVoice() {
        if (voice != null) {
            Minecraft.getInstance().getSoundManager().stop(voice);
            voice = null;
        }
    }

    private static void warnOnce(String key, String message) {
        if (WARNED_MISSING.add(key)) {
            Espetro.LOGGER.warn("[客户端编制音频] {}", message);
        }
    }

    private static final class PendingVoice {
        private int ticksRemaining;
        private final Path packRoot;
        private final String voiceGroup;

        private PendingVoice(int ticksRemaining, Path packRoot, String voiceGroup) {
            this.ticksRemaining = ticksRemaining;
            this.packRoot = packRoot;
            this.voiceGroup = voiceGroup;
        }
    }
}
