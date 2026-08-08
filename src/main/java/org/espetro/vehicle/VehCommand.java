package org.espetro.vehicle;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.espetro.Espetro;
import org.espetro.team.SquadManager;

import java.util.UUID;

/**
 * 载具认领命令（小队长用，无需 OP）
 * /veh pass   — 通过最近一条队员的载具认领申请
 * /veh passno — 否决最近一条队员的载具认领申请
 */
public class VehCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("veh")
                .requires(source -> source.hasPermission(0))
                .then(Commands.literal("pass")
                    .executes(ctx -> handlePass(ctx.getSource()))
                )
                .then(Commands.literal("passno")
                    .executes(ctx -> handlePassNo(ctx.getSource()))
                )
        );
    }

    private static int handlePass(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer leader)) {
            source.sendFailure(Component.literal("只有玩家可以使用此命令。"));
            return 0;
        }

        SquadManager sm = SquadManager.getInstance();
        String team = Espetro.getPlayerTeam(leader);
        if (team == null) {
            source.sendFailure(Component.literal("你不在任何阵营中。"));
            return 0;
        }
        team = team.toUpperCase();

        if (!sm.isSquadLeader(leader.getUUID())) {
            source.sendFailure(Component.literal("只有小队长可以使用此命令。"));
            return 0;
        }

        int squadId = sm.getPlayerSquadId(leader.getUUID());
        if (squadId == SquadManager.NO_SQUAD) {
            source.sendFailure(Component.literal("你不在任何小队中。"));
            return 0;
        }

        VehicleEventHandler.PendingClaim claim = VehicleEventHandler.PENDING_CLAIMS.remove(squadId);
        if (claim == null) {
            source.sendFailure(Component.literal("没有待处理的认领申请。"));
            return 0;
        }

        if (System.currentTimeMillis() > claim.expiryMs()) {
            leader.sendSystemMessage(Component.literal("§c该认领申请已过期。"));
            return 0;
        }

        // 查找载具实体
        Entity vehicle = null;
        for (var level : leader.getServer().getAllLevels()) {
            Entity e = level.getEntity(claim.vehicleUuid());
            if (e != null) {
                vehicle = e;
                break;
            }
        }
        if (vehicle == null) {
            leader.sendSystemMessage(Component.literal("§c申请认领的载具已不存在。"));
            return 0;
        }

        VehicleSquadOwnership.setOwner(vehicle, squadId, team);

        // 通过 → 告知双方
        leader.sendSystemMessage(Component.literal("§a通过申请"));
        ServerPlayer member = leader.serverLevel().getServer().getPlayerList()
            .getPlayer(claim.memberUuid());
        if (member != null) {
            member.sendSystemMessage(Component.literal("§a通过申请"));
        }
        return 1;
    }

    private static int handlePassNo(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer leader)) {
            source.sendFailure(Component.literal("只有玩家可以使用此命令。"));
            return 0;
        }

        SquadManager sm = SquadManager.getInstance();

        if (!sm.isSquadLeader(leader.getUUID())) {
            source.sendFailure(Component.literal("只有小队长可以使用此命令。"));
            return 0;
        }

        int squadId = sm.getPlayerSquadId(leader.getUUID());
        if (squadId == SquadManager.NO_SQUAD) {
            source.sendFailure(Component.literal("你不在任何小队中。"));
            return 0;
        }

        VehicleEventHandler.PendingClaim claim = VehicleEventHandler.PENDING_CLAIMS.remove(squadId);
        if (claim == null) {
            source.sendFailure(Component.literal("没有待处理的认领申请。"));
            return 0;
        }

        // passno 不向队员发送消息（用户未要求）
        leader.sendSystemMessage(Component.literal("§c已否决认领申请"));
        return 1;
    }
}
