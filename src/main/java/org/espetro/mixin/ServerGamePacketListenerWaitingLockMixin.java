package org.espetro.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.espetro.bastion.BastionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejects movement before vanilla applies it while a player is in a deployment
 * or phase hold. A bounded correction replaces per-tick teleport spam.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerWaitingLockMixin {
    @Shadow public ServerPlayer player;

    @Shadow
    public abstract void teleport(double x, double y, double z, float yaw, float pitch);

    @Unique
    private long espetro$lastWaitingCorrectionTick = Long.MIN_VALUE;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void espetro$rejectWaitingMovement(
            ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        BastionManager manager = BastionManager.getInstance();
        Vec3 lock = manager.getPlayerLockPosition(player.getUUID());
        if (lock == null && !manager.isWaitingForBastion(player.getUUID())) {
            return;
        }

        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        if (lock != null) {
            double requestedX = packet.getX(player.getX());
            double requestedY = packet.getY(player.getY());
            double requestedZ = packet.getZ(player.getZ());
            double dx = requestedX - lock.x;
            double dy = requestedY - lock.y;
            double dz = requestedZ - lock.z;
            long tick = player.server.getTickCount();
            if ((dx * dx + dy * dy + dz * dz > 0.25D
                    || player.distanceToSqr(lock) > 0.25D)
                && (espetro$lastWaitingCorrectionTick == Long.MIN_VALUE
                    || tick - espetro$lastWaitingCorrectionTick >= 10L)) {
                espetro$lastWaitingCorrectionTick = tick;
                teleport(lock.x, lock.y, lock.z, player.getYRot(), player.getXRot());
            }
        }
        callback.cancel();
    }
}
