package org.espetro.vehicle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.network.MountProgressPacket;
import org.espetro.network.NetworkManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Authoritative mount channel for SuperbWarfare vehicles. */
public final class VehicleMountServer {

    private static final Map<UUID, PendingMount> PENDING = new HashMap<>();

    private record PendingMount(UUID vehicleId, long startGameTime, int delayTicks) {
    }

    private VehicleMountServer() {
    }

    public static void begin(ServerPlayer player, UUID vehicleId) {
        if (player == null || vehicleId == null) {
            return;
        }
        if (player.getVehicle() != null) {
            cancel(player);
            return;
        }
        Entity entity = player.serverLevel().getEntity(vehicleId);
        if (!SbwVehicleSeatResolver.isSupportedVehicle(entity)) {
            cancel(player);
            return;
        }
        if (player.distanceTo(entity) > VehicleInteractionConfig.mountMaxDistance()) {
            cancel(player);
            return;
        }
        // 主城阶段：跳过读条，立即上车（原版 SBW 交互行为）。
        if (org.espetro.team.GameStateManager.getInstance().getCurrentPhase().isLobbyLike()) {
            tryMount(player, entity);
            return;
        }
        int delay = VehicleInteractionConfig.mountDelayTicks();
        if (delay <= 0) {
            tryMount(player, entity);
            return;
        }

        PendingMount existing = PENDING.get(player.getUUID());
        if (existing != null && vehicleId.equals(existing.vehicleId)) {
            long elapsed = player.serverLevel().getGameTime() - existing.startGameTime;
            float progress = Math.min(1f, elapsed / (float) Math.max(1, existing.delayTicks));
            sendProgress(player, new MountProgressPacket(true, progress, existing.delayTicks));
            return;
        }

        PENDING.put(player.getUUID(),
            new PendingMount(vehicleId, player.serverLevel().getGameTime(), delay));
        sendProgress(player, new MountProgressPacket(true, 0f, delay));
    }

    public static void cancel(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (PENDING.remove(player.getUUID()) != null) {
            sendProgress(player, new MountProgressPacket(false, 0f, 0));
        }
    }

    public static void complete(ServerPlayer player, UUID vehicleId) {
        if (player == null || vehicleId == null) {
            return;
        }
        PendingMount pending = PENDING.get(player.getUUID());
        if (pending == null || !vehicleId.equals(pending.vehicleId)) {
            cancel(player);
            return;
        }
        long elapsed = player.serverLevel().getGameTime() - pending.startGameTime;
        if (elapsed + 3 < pending.delayTicks) {
            return;
        }
        Entity entity = player.serverLevel().getEntity(vehicleId);
        PENDING.remove(player.getUUID());
        sendProgress(player, new MountProgressPacket(false, 1f, pending.delayTicks));
        tryMount(player, entity);
    }

    public static void tick(ServerPlayer player) {
        PendingMount pending = PENDING.get(player.getUUID());
        if (pending == null) {
            return;
        }
        Entity entity = player.serverLevel().getEntity(pending.vehicleId);
        if (!SbwVehicleSeatResolver.isSupportedVehicle(entity)
            || player.getVehicle() != null
            || player.distanceTo(entity) > VehicleInteractionConfig.mountMaxDistance() + 0.75) {
            cancel(player);
            return;
        }
        long elapsed = player.serverLevel().getGameTime() - pending.startGameTime;
        float progress = Math.min(1f, elapsed / (float) Math.max(1, pending.delayTicks));
        if ((elapsed & 1L) == 0L) {
            sendProgress(player, new MountProgressPacket(true, progress, pending.delayTicks));
        }
        if (elapsed >= pending.delayTicks) {
            complete(player, pending.vehicleId);
        }
    }

    public static void tickAll(Iterable<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            tick(player);
        }
    }

    public static void clear() {
        PENDING.clear();
    }

    private static void tryMount(ServerPlayer player, Entity entity) {
        if (player == null || !SbwVehicleSeatResolver.isSupportedVehicle(entity)) {
            return;
        }
        if (player.getVehicle() != null) {
            return;
        }
        if (player.distanceTo(entity) > VehicleInteractionConfig.mountMaxDistance() + 0.75) {
            return;
        }
        // 读条上车通道同样执行小队归属准入（主城阶段放行），防止非队长成员
        // 绕过右键交互拦截直接上未认领/非本队的载具。
        if (!VehicleEventHandler.isMountAllowed(player, entity)) {
            cancel(player);
            return;
        }
        player.startRiding(entity, true);
    }

    private static void sendProgress(ServerPlayer player, MountProgressPacket packet) {
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
