package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.team.ClassCountManager;
import org.espetro.team.GameStateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 兵站选择数据包
 * 用于向死亡玩家发送可用兵站列表
 */
public class BastionSelectionPacket {

    // 兵站信息列表
    private final List<BastionInfo> bastions;

    public BastionSelectionPacket(List<BastionInfo> bastions) {
        this.bastions = bastions;
    }

    public BastionSelectionPacket(FriendlyByteBuf buf) {
        int size = buf.readInt();
        this.bastions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            UUID id = buf.readUUID();
            String name = buf.readUtf();
            String team = buf.readUtf();
            bastions.add(new BastionInfo(id, name, team));
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(bastions.size());
        for (BastionInfo info : bastions) {
            buf.writeUUID(info.id);
            buf.writeUtf(info.name);
            buf.writeUtf(info.team);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 客户端收到后显示兵站选择界面
            // 这个在客户端处理
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 兵站信息
     */
    public static class BastionInfo {
        public final UUID id;
        public final String name;
        public final String team;

        public BastionInfo(UUID id, String name, String team) {
            this.id = id;
            this.name = name;
            this.team = team;
        }
    }

    /**
     * 向死亡玩家发送复活点选择 GUI 界面
     */
    public static void sendBastionSelectionMessage(ServerPlayer player) {
        org.espetro.network.NetworkManager.sendDeployPointSelectScreen(player);
    }

    /**
     * 玩家选择复活点
     */
    public static boolean handleBastionSelect(ServerPlayer player, UUID bastionId) {
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) {
            return false;
        }

        String team = Espetro.getPlayerTeam(player);
        if (team == null) return false;
        List<BastionData> teamBastions = BastionManager.getInstance().getTeamBastions(team);

        // 找到对应的兵站
        BastionData selectedBastion = null;
        for (BastionData bastion : teamBastions) {
            if (bastion.getBastionId().equals(bastionId)) {
                selectedBastion = bastion;
                break;
            }
        }

        if (selectedBastion == null) {
            player.sendSystemMessage(Component.literal("§c无效的兵站选择！"));
            return false;
        }

        // 检查玩家是否在等待复活
        if (!BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            // 玩家已经复活，不再允许重复选择
            player.sendSystemMessage(Component.literal("§c你已经完成了复活选择！"));
            return false;
        }

        // 清除等待状态（必须在传送前清除，防止重复点击）
        BastionManager.getInstance().clearWaiting(player.getUUID());

        // 传送玩家到兵站位置
        org.espetro.team.SpawnPointConfig.SpawnPoint spawn = 
            new org.espetro.team.SpawnPointConfig.SpawnPoint(
                selectedBastion.getPosition().getX() + 0.5,
                selectedBastion.getPosition().getY() + 1,
                selectedBastion.getPosition().getZ() + 0.5,
                0f
            );
        
        player.teleportTo(selectedBastion.getLevel(), 
            spawn.x, spawn.y, spawn.z, spawn.yaw, 0f);

        // 设置生存模式
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        // 移除所有效果
        player.removeAllEffects();

        // 给予短暂的无敌效果
        int invincibilityTicks = org.espetro.config.GameConfig.getRespawnInvincibilityTicks();
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
            invincibilityTicks,
            127, // 最大等级
            false, false, false
        ));

        player.sendSystemMessage(Component.literal("§a已在 §e" + selectedBastion.getName() + " §a复活！"));

        return true;
    }
}
