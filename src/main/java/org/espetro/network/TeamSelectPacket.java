package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.team.GameStateManager;
import org.espetro.team.TeamManager;

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

            GameStateManager gsm = GameStateManager.getInstance();

            // 战局中加入 vs 正常流程
            if (gsm.isMidGameJoiner(player.getUUID())) {
                // 战局中加入：自动分配编制、沿用现有指挥官
                gsm.onMidGameTeamSelected(player, team);
                Espetro.LOGGER.info("玩家 {} 战局加入 {} 阵营", player.getName().getString(), team);
            } else {
                // 正常流程：根据阵营添加到对应的Minecraft队伍
                if ("ATTACK".equals(team)) {
                    TeamManager.joinAttackTeam(player.getServer(), player.getName().getString());
                } else {
                    TeamManager.joinDefendTeam(player.getServer(), player.getName().getString());
                }

                // 通知游戏状态管理器玩家已选择队伍
                gsm.onTeamSelected(player, team);

                Espetro.LOGGER.info("玩家 {} 选择了 {} 阵营", player.getName().getString(), team);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
