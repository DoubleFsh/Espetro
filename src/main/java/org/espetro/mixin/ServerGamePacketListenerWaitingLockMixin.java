package org.espetro.mixin;

import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
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
 * or phase hold. Also prevents mid-game rejoin kicks from
 * "multiplayer.disconnect.invalid_player_movement" by keeping
 * {@code awaitingPositionFromClient} non-null across dimension changes.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerWaitingLockMixin {
    @Shadow public ServerPlayer player;

    @Shadow
    public abstract void teleport(double x, double y, double z, float yaw, float pitch);

    /** 防止位置锁期间维度切换（teleportTo 改维）时，
     *  handleAcceptTeleportPacket 因 awaitingPositionFromClient==null
     *  而踢出玩家（"接受非法移动数据"）。 */
    @Shadow
    private Vec3 awaitingPositionFromClient;

    @Unique
    private long espetro$lastWaitingCorrectionTick = Long.MIN_VALUE;

    /**
     * 在接受传送包之前补设 awaitingPositionFromClient，防止因跨维
     * 传送后没有及时收到移动包而被踢。
     */
    @Inject(method = "handleAcceptTeleportPacket",
            at = @At("HEAD"))
    private void espetro$ensureAwaitingBeforeAccept(
            ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        if (awaitingPositionFromClient == null && player != null) {
            awaitingPositionFromClient = new Vec3(player.getX(), player.getY(), player.getZ());
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void espetro$rejectWaitingMovement(
            ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        BastionManager manager = BastionManager.getInstance();
        Vec3 lock = manager.getPlayerLockPosition(player.getUUID());
        if (lock == null && !manager.isWaitingForBastion(player.getUUID())) {
            return;
        }

        // 维持 awaitingPositionFromClient 为非 null，避免跨维 teleport
        // 时 handleAcceptTeleportPacket 判空踢人。
        if (awaitingPositionFromClient == null) {
            awaitingPositionFromClient = new Vec3(player.getX(), player.getY(), player.getZ());
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
