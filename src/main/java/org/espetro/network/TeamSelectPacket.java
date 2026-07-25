package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.team.GameStateManager;

import java.util.function.Supplier;

/**
 * 队伍选择数据包
 * 玩家选择攻防方阵营时发送
 * 支持正常流程和战局中加入两种模式
 */
public class TeamSelectPacket {

    private final String team; // "ATTACK" 或 "DEFEND"

    public TeamSelectPacket(String team) {
        this.team = team;
    }

    public static TeamSelectPacket read(FriendlyByteBuf buf) {
        return new TeamSelectPacket(buf.readUtf());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(team);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!"ATTACK".equals(team) && !"DEFEND".equals(team)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c无效的阵营选择。"));
                return;
            }

            GameStateManager gsm = GameStateManager.getInstance();

            // 战局中加入 vs 正常流程
            if (gsm.isMidGameJoiner(player.getUUID())) {
                if (org.espetro.team.ClassCountManager.getInstance()
                    .getPlayerTeam(player.getUUID()) != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§c增援阵营已经确定，不能再次选择。"));
                    return;
                }
                // 战局中加入：自动分配编制、沿用现有指挥官
                gsm.onMidGameTeamSelected(player, team);
                NetworkManager.sendSquadSync(player);
                Espetro.LOGGER.info("玩家 {} 战局加入 {} 阵营", player.getName().getString(), team);
            } else {
                if (gsm.getCurrentPhase() != org.espetro.team.GamePhase.TEAM_SELECT) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§c当前不能选择阵营。"));
                    return;
                }
                // 通知游戏状态管理器玩家已选择队伍
                gsm.onTeamSelected(player, team);
                NetworkManager.sendSquadSync(player);

                Espetro.LOGGER.info("玩家 {} 选择了 {} 阵营", player.getName().getString(), team);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
