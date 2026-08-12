package org.espetro.mixin.hcrpoints;

import net.minecraft.server.MinecraftServer;
import org.espetro.api.EspetroAPI;
import org.espetro.audio.AudioCuePolicy;
import org.espetro.team.TroopCountManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional ESPoints hook fired once when its anti-spam guarded capture reward is confirmed. */
@Pseudo
@Mixin(targets = {
    "com.example.hcrpoints.capturepoint.CapturePointManager",
    "com.example.espoints.capturepoint.CapturePointManager"
}, remap = false)
public abstract class CapturePointAudioMixin {

    @Inject(
        method = "giveCaptureReward(Lnet/minecraft/server/MinecraftServer;Ljava/lang/String;Ljava/lang/String;)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void espetro$onCapturePointCaptured(MinecraftServer server, String captorName,
                                                 String pointName, CallbackInfo ci) {
        EspetroAPI.onCapturePointCaptured(captorName);
    }

    /**
     * ESPoints shows its own win/lose popup but does not enter Espetro's round
     * end phase. Hand the result back after ESPoints has finished its cleanup
     * so the authoritative result packet opens the full settlement screen.
     */
    @Inject(
        method = "endOperationModeWithResult(Ljava/lang/String;Ljava/lang/String;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void espetro$onOperationEnded(String winnerTeam, String loserTeam,
                                           CallbackInfo ci) {
        String winner = AudioCuePolicy.normalizeTeam(winnerTeam);
        if (winner != null) {
            // Capturing every point is an attacker objective victory. Persist
            // the defender ticket loss before endRound snapshots the scores.
            if ("ATTACK".equals(winner)) {
                TroopCountManager.getInstance().setDefendTroops(0);
            }
            EspetroAPI.endRound(winner);
        }
    }
}
