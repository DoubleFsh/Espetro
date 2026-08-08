package org.espetro.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.vehicle.VehicleConfig;
import org.espetro.vehicle.VehicleManager;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 载具轮盘操作包（客户端→服务端）
 */
public class VehicleSupplyActionPacket {

    public enum Action {
        LOAD_AMMO,
        UNLOAD_AMMO,
        LOAD_CONSTRUCTION,
        UNLOAD_CONSTRUCTION,
        RESUPPLY_INFANTRY,
        CHANGE_CLASS
    }

    private final UUID vehicleId;
    private final Action action;

    public VehicleSupplyActionPacket(UUID vehicleId, Action action) {
        this.vehicleId = vehicleId;
        this.action = action;
    }

    public static VehicleSupplyActionPacket read(FriendlyByteBuf buf) {
        return new VehicleSupplyActionPacket(buf.readUUID(), buf.readEnum(Action.class));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(vehicleId);
        buf.writeEnum(action);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleServer(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private void handleServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;

        VehicleManager vm = VehicleManager.getInstance();
        BastionManager bm = BastionManager.getInstance();

        // 通过 VehicleManager 校验载具是否活跃（getEntity(UUID) 在 1.20.1 不可用）
        String factionId = vm.getVehicleFactionId(vehicleId);
        String vehicleType = vm.getVehicleType(vehicleId);
        if (factionId == null || vehicleType == null) return;

        VehicleManager.VehicleSupplyState supply =
            vm.getOrCreateVehicleSupply(vehicleId, factionId, vehicleType);
        if (supply == null) return;

        VehicleConfig.VehicleTypeConfig vcfg =
            VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (vcfg == null) return;

        boolean atSupplySource = isAtFobOrBase(player, bm);

        switch (action) {
            case LOAD_AMMO -> {
                if (!atSupplySource) return;
                int deducted = deductFromFobAmmo(player, bm, 100);
                if (deducted <= 0) {
                    player.displayClientMessage(Component.literal("§c已经没有可以装载的弹药了"), true);
                    return;
                }
                supply.addAmmo(deducted);
            }
            case UNLOAD_AMMO -> {
                if (!atSupplySource) return;
                int removed = supply.removeAmmo(100);
                if (removed > 0) addToFobAmmo(player, bm, removed);
            }
            case LOAD_CONSTRUCTION -> {
                if (!atSupplySource || !supply.canCarryConstruction()) return;
                int deducted = deductFromFobConstruction(player, bm, 100);
                if (deducted <= 0) {
                    player.displayClientMessage(Component.literal("§c已经没有可以装载的建材了"), true);
                    return;
                }
                supply.addConstruction(deducted);
            }
            case UNLOAD_CONSTRUCTION -> {
                if (!atSupplySource) return;
                int removed = supply.removeConstruction(100);
                if (removed > 0) addToFobConstruction(player, bm, removed);
            }
            case RESUPPLY_INFANTRY ->
                org.espetro.bastion.BastionEventHandler.performVehicleResupply(
                    player, supply, LogisticsConfig.get().defaultResupplyAmmoCost);
            case CHANGE_CLASS -> {
                if (!vcfg.canChangeClass()) return;
                NetworkManager.sendVehicleClassSelect(player, factionId);
            }
        }

        sendSupplySync(player, vehicleId, supply);
    }

    private static boolean isAtFobOrBase(ServerPlayer player, BastionManager bm) {
        if (player == null) return false;
        ServerLevel level = (ServerLevel) player.level();
        // FOB 范围内
        String team = Espetro.getPlayerTeam(player);
        if (team != null && !bm.findCoveringRadios(level, player.blockPosition(), team).isEmpty()) {
            return true;
        }
        // 主基地：双方初始重生点周围 40 格
        return isAtTeamDeployOrigin(player);
    }

    /** 检测是否在己方编制部署点 40 格内（主基地） */
    static boolean isAtTeamDeployOrigin(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return false;
        String factionId = org.espetro.team.ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) return false;
        var configs = VehicleConfig.getFactionVehicles(factionId);
        if (configs == null) return false;
        BlockPos playerPos = player.blockPosition();
        double range = 40;
        for (var vcfg : configs.values()) {
            var dp = vcfg.deployment.forTeam(team);
            if (dp != null && dp.position != null && dp.position.length >= 3) {
                double dx = playerPos.getX() - dp.position[0];
                double dy = playerPos.getY() - dp.position[1];
                double dz = playerPos.getZ() - dp.position[2];
                if (dx * dx + dy * dy + dz * dz <= range * range) return true;
            }
        }
        return false;
    }

    static boolean isAtMainBase(ServerPlayer player) {
        return isAtTeamDeployOrigin(player);
    }

    private static int deductFromFobAmmo(ServerPlayer player, BastionManager bm, int amount) {
        if (isAtMainBase(player)) return amount;
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return 0;
        ServerLevel level = (ServerLevel) player.level();
        var radios = bm.findCoveringRadios(level, player.blockPosition(), team);
        int remaining = amount;
        for (BastionData d : radios) {
            int avail = d.getAmmunitionSupplies();
            int take = Math.min(remaining, avail);
            if (take > 0 && d.consumeAmmunitionSupplies(take)) remaining -= take;
            if (remaining <= 0) break;
        }
        return amount - remaining;
    }

    private static void addToFobAmmo(ServerPlayer player, BastionManager bm, int amount) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return;
        ServerLevel level = (ServerLevel) player.level();
        var radios = bm.findCoveringRadios(level, player.blockPosition(), team);
        if (!radios.isEmpty())
            radios.get(0).addAmmunitionSupplies(amount, LogisticsConfig.get().maxAmmunition);
    }

    private static int deductFromFobConstruction(ServerPlayer player, BastionManager bm, int amount) {
        if (isAtMainBase(player)) return amount;
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return 0;
        ServerLevel level = (ServerLevel) player.level();
        var radios = bm.findCoveringRadios(level, player.blockPosition(), team);
        int remaining = amount;
        for (BastionData d : radios) {
            int avail = d.getConstructionSupplies();
            int take = Math.min(remaining, avail);
            if (take > 0 && d.consumeConstructionSupplies(take)) remaining -= take;
            if (remaining <= 0) break;
        }
        return amount - remaining;
    }

    private static void addToFobConstruction(ServerPlayer player, BastionManager bm, int amount) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return;
        ServerLevel level = (ServerLevel) player.level();
        var radios = bm.findCoveringRadios(level, player.blockPosition(), team);
        if (!radios.isEmpty())
            radios.get(0).addConstructionSupplies(amount, LogisticsConfig.get().maxConstruction);
    }

    private static void sendSupplySync(ServerPlayer player, UUID vehicleId,
                                        VehicleManager.VehicleSupplyState supply) {
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new VehicleSupplySyncPacket(vehicleId,
                supply.getAmmo(), supply.getConstruction(),
                supply.getMaxCapacity(), supply.canCarryConstruction()));
    }
}
