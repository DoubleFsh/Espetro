package org.espetro.client.audio;

import com.mojang.blaze3d.audio.OggAudioStream;
import net.minecraft.Util;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.espetro.Espetro;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Streaming sound instance whose OGG stream comes from a client-local file. */
final class ExternalOggSoundInstance extends AbstractSoundInstance {
    private final Path file;
    private final Sound externalSound;
    private final WeighedSoundEvents resolvedEvent;

    ExternalOggSoundInstance(Path file, SoundSource source, String channelName) {
        super(new ResourceLocation(Espetro.MOD_ID, "external_audio/" + channelName),
            source, SoundInstance.createUnseededRandom());
        this.file = file;
        this.volume = 1.0F;
        this.pitch = 1.0F;
        this.looping = false;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.externalSound = new Sound(
            Espetro.MOD_ID + ":external_audio/stream",
            ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1,
            Sound.Type.FILE, true, false, 16);
        this.resolvedEvent = new WeighedSoundEvents(this.location, null);
        this.resolvedEvent.addSound(this.externalSound);
        this.sound = this.externalSound;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager soundManager) {
        this.sound = externalSound;
        return resolvedEvent;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(
            SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new OggAudioStream(Files.newInputStream(file));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, Util.backgroundExecutor());
    }
}
