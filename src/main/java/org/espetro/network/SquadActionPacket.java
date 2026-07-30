package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.Fireteam;
import org.espetro.team.SquadManager;
import org.espetro.team.TeamPackManager;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 班组小队操作包（C→S）。
 * 火力组相关动作通过 {@link #targetUuid} / {@link #fireteamIndex} 传参。
 */
public class SquadActionPacket {

    public enum Action {
        CREATE,
        JOIN,
        LEAVE,
        DELETE,
        /** 小队长：转移队长给 targetUuid */
        TRANSFER_SQUAD_LEADER,
        /** 火力组组长：转移组长给同组 targetUuid */
        TRANSFER_FIRETEAM_LEADER,
        /** 小队长：将 targetUuid 分配到 fireteamIndex (0=A,1=B,2=C) */
        ASSIGN_FIRETEAM
    }

    private final Action action;
    private final int squadId;
    private final String squadName;
    private final UUID targetUuid;
    private final byte fireteamIndex;

    public SquadActionPacket(Action action, int squadId, String squadName) {
        this(action, squadId, squadName, null, (byte) -1);
    }

    public SquadActionPacket(Action action, int squadId, String squadName,
                             UUID targetUuid, byte fireteamIndex) {
        this.action = action;
        this.squadId = squadId;
        this.squadName = squadName == null ? "" : squadName;
        this.targetUuid = targetUuid;
        this.fireteamIndex = fireteamIndex;
    }

    public static SquadActionPacket create(String squadName) {
        return new SquadActionPacket(Action.CREATE, SquadManager.NO_SQUAD, squadName);
    }

    public static SquadActionPacket join(int squadId) {
        return new SquadActionPacket(Action.JOIN, squadId, "");
    }

    public static SquadActionPacket leave() {
        return new SquadActionPacket(Action.LEAVE, SquadManager.NO_SQUAD, "");
    }

    public static SquadActionPacket delete(int squadId) {
        return new SquadActionPacket(Action.DELETE, squadId, "");
    }

    public static SquadActionPacket transferSquadLeader(UUID targetUuid) {
        return new SquadActionPacket(Action.TRANSFER_SQUAD_LEADER, SquadManager.NO_SQUAD, "",
            targetUuid, (byte) -1);
    }

    public static SquadActionPacket transferFireteamLeader(UUID targetUuid) {
        return new SquadActionPacket(Action.TRANSFER_FIRETEAM_LEADER, SquadManager.NO_SQUAD, "",
            targetUuid, (byte) -1);
    }

    public static SquadActionPacket assignFireteam(UUID targetUuid, Fireteam fireteam) {
        return new SquadActionPacket(Action.ASSIGN_FIRETEAM, SquadManager.NO_SQUAD, "",
            targetUuid, fireteam != null ? fireteam.toNetwork() : (byte) -1);
    }

    public static SquadActionPacket read(FriendlyByteBuf buf) {
        Action action;
        try {
            action = Action.valueOf(buf.readUtf());
        } catch (IllegalArgumentException ignored) {
            action = Action.JOIN;
        }
        int squadId = buf.readVarInt();
        String squadName = buf.readUtf();
        UUID targetUuid = buf.readBoolean() ? buf.readUUID() : null;
        byte fireteamIndex = buf.readByte();
        return new SquadActionPacket(action, squadId, squadName, targetUuid, fireteamIndex);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(action.name());
        buf.writeVarInt(squadId);
        buf.writeUtf(squadName);
        if (targetUuid != null) {
            buf.writeBoolean(true);
            buf.writeUUID(targetUuid);
        } else {
            buf.writeBoolean(false);
        }
        buf.writeByte(fireteamIndex);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            String previousTeam = Espetro.getPlayerTeam(player);
            int previousSquadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
            boolean wasLeader = SquadManager.getInstance().isSquadLeader(player.getUUID());

            SquadManager.ActionResult result = switch (action) {
                case CREATE -> SquadManager.getInstance().createSquad(player, squadName);
                case JOIN -> SquadManager.getInstance().joinSquad(player, squadId);
                case LEAVE -> SquadManager.getInstance().leaveSquad(player);
                case DELETE -> SquadManager.getInstance().deleteSquad(player, squadId);
                case TRANSFER_SQUAD_LEADER -> {
                    if (targetUuid == null) {
                        yield SquadManager.ActionResult.failure(previousTeam, "缺少目标玩家。");
                    }
                    yield SquadManager.getInstance().transferSquadLeader(player, targetUuid);
                }
                case TRANSFER_FIRETEAM_LEADER -> {
                    if (targetUuid == null) {
                        yield SquadManager.ActionResult.failure(previousTeam, "缺少目标玩家。");
                    }
                    yield SquadManager.getInstance().transferFireteamLeader(player, targetUuid);
                }
                case ASSIGN_FIRETEAM -> {
                    if (targetUuid == null || fireteamIndex < 0 || fireteamIndex > 2) {
                        yield SquadManager.ActionResult.failure(previousTeam, "无效的火力组分配。");
                    }
                    yield SquadManager.getInstance().assignFireteam(
                        player, targetUuid, Fireteam.fromIndex(fireteamIndex));
                }
            };

            player.sendSystemMessage(Component.literal((result.success ? "\u00a7a" : "\u00a7c") + result.message));

            if (result.success) {
                String currentTeam = Espetro.getPlayerTeam(player);
                int currentSquadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
                boolean isLeaderNow = SquadManager.getInstance().isSquadLeader(player.getUUID());
                TeamPackManager.getInstance().handleSquadLeaderTransition(
                    player,
                    previousTeam,
                    previousSquadId,
                    wasLeader,
                    currentTeam,
                    currentSquadId,
                    isLeaderNow
                );
                // 新队长需获得队长包；旧队长已在上方 wasLeader→!isLeader 时卸包
                if (action == Action.TRANSFER_SQUAD_LEADER && targetUuid != null) {
                    MinecraftServerBridge.promoteNewLeaderPack(
                        targetUuid, previousTeam, previousSquadId);
                    // 新队长 usableBy 技能列表需立即刷新
                    MinecraftServerBridge.refreshSkillSync(targetUuid);
                } else if ((action == Action.TRANSFER_FIRETEAM_LEADER
                    || action == Action.ASSIGN_FIRETEAM) && targetUuid != null) {
                    MinecraftServerBridge.reconcileTargetPack(targetUuid);
                }
                // 创建/离队/转移后：队长身份变化 → 重推技能入口（无人机/补给站等）
                if (wasLeader != isLeaderNow
                    || action == Action.CREATE
                    || action == Action.LEAVE
                    || action == Action.DELETE
                    || action == Action.TRANSFER_SQUAD_LEADER) {
                    NetworkManager.sendCommanderSkillSync(player);
                }
                NetworkManager.broadcastMatchStats(
                    org.espetro.stats.PlayerMatchStatsManager.getInstance());
            }

            if (result.team != null) {
                TeamPackManager.getInstance().reconcileTeam(result.team);
                NetworkManager.syncSquadsToTeam(result.team);
                NetworkManager.broadcastClassCounts(
                    result.team,
                    ClassCountManager.getInstance().getPlayerFaction(player.getUUID())
                );
            } else {
                TeamPackManager.getInstance().syncTeamPackItem(player);
                NetworkManager.sendSquadSync(player);
                NetworkManager.syncUnifiedDeployScreen(player, -1);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** 避免在 packet 里直接依赖过多 import 的小助手 */
    private static final class MinecraftServerBridge {
        static void reconcileTargetPack(UUID targetUuid) {
            var server = Espetro.getServer();
            if (server == null) return;
            ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
            if (target != null) {
                TeamPackManager.getInstance().syncTeamPackItem(target);
            }
        }

        static void promoteNewLeaderPack(UUID targetUuid, String team, int squadId) {
            var server = Espetro.getServer();
            if (server == null) return;
            ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
            if (target == null) return;
            TeamPackManager.getInstance().handleSquadLeaderTransition(
                target, team, squadId, false, team, squadId, true);
            NetworkManager.syncUnifiedDeployScreen(target, -1);
        }

        static void refreshSkillSync(UUID targetUuid) {
            var server = Espetro.getServer();
            if (server == null || targetUuid == null) return;
            ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
            if (target != null) {
                NetworkManager.sendCommanderSkillSync(target);
            }
        }

    }
}
