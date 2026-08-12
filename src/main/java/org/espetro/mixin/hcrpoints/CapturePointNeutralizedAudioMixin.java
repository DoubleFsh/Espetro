package org.espetro.mixin.hcrpoints;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Team;
import org.espetro.Espetro;
import org.espetro.api.EspetroAPI;
import org.espetro.audio.AudioCuePolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.List;

/** Optional ESPoints hook fired once when an owned point becomes neutral. */
@Pseudo
@Mixin(targets = {
    "com.example.hcrpoints.capturepoint.CapturePoint",
    "com.example.espoints.capturepoint.CapturePoint"
}, remap = false)
public abstract class CapturePointNeutralizedAudioMixin {
    @Shadow(remap = false)
    public abstract String getCaptorName();

    @Unique
    private static Method espetro$getStateMethod;
    @Unique
    private static boolean espetro$stateLookupWarningLogged;
    @Unique
    private String espetro$audioOldState;
    @Unique
    private String espetro$audioOldCaptor;

    @Inject(method = "updateStatus(Ljava/util/List;)V", at = @At("HEAD"),
        require = 0, remap = false)
    private void espetro$rememberOwnerBeforeUpdate(List<?> playersInPoint, CallbackInfo ci) {
        espetro$audioOldState = espetro$getStateName();
        espetro$audioOldCaptor = getCaptorName();
    }

    @Inject(method = "updateStatus(Ljava/util/List;)V", at = @At("RETURN"),
        require = 0, remap = false)
    private void espetro$onPointNeutralized(List<?> playersInPoint, CallbackInfo ci) {
        if (espetro$audioOldCaptor == null || espetro$audioOldCaptor.isBlank()
            || "NEUTRAL".equals(espetro$audioOldState)
            || !"NEUTRAL".equals(espetro$getStateName())) {
            return;
        }
        EspetroAPI.onCapturePointNeutralized(
            espetro$audioOldCaptor, espetro$findAttackingTeam(playersInPoint));
    }

    @Unique
    private String espetro$findAttackingTeam(List<?> playersInPoint) {
        String owner = AudioCuePolicy.normalizeTeam(espetro$audioOldCaptor);
        if (playersInPoint == null) return null;
        for (Object candidate : playersInPoint) {
            if (!(candidate instanceof ServerPlayer player)) continue;
            Team team = player.getTeam();
            if (team == null) continue;
            String teamName = team.getName();
            if (!java.util.Objects.equals(owner, AudioCuePolicy.normalizeTeam(teamName))) {
                return teamName;
            }
        }
        return null;
    }

    @Unique
    private String espetro$getStateName() {
        try {
            Method method = espetro$getStateMethod;
            if (method == null) {
                method = getClass().getMethod("getState");
                espetro$getStateMethod = method;
            }
            Object state = method.invoke(this);
            return state instanceof Enum<?> enumState
                ? enumState.name() : String.valueOf(state);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!espetro$stateLookupWarningLogged) {
                espetro$stateLookupWarningLogged = true;
                Espetro.LOGGER.warn("[据点语音] 无法读取 ESPoints 据点状态，中立化语音已跳过", exception);
            }
            return null;
        }
    }
}
