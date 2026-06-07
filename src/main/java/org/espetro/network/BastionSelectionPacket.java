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
     * 旧复活点选择入口的兼容转发：实际发送统一部署面板。
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
            player.sendSystemMessage(Component.literal("§c你已经完成了复活选择！"));
            return false;
        }

        // 立即清除等待状态，防止重复点击
        BastionManager.getInstance().clearWaiting(player.getUUID());

        // 远距离兵站所在区块可能尚未加载，先加载再延迟传送
        selectedBastion.ensureChunkLoaded();
        final BastionData finalBastion = selectedBastion;
        final ServerPlayer finalPlayer = player;

        // 延迟到下一个 tick 传送：等待区块内实体完全就绪
        player.server.execute(() -> doTeleport(finalPlayer, finalBastion));

        return true;
    }

    /**
     * 执行传送：区块已加载，实体已就绪，校验盔甲架后传送玩家
     */
    private static void doTeleport(ServerPlayer player, BastionData bastion) {
        // 二次校验盔甲架是否有效（此时区块实体应已就绪）
        if (!bastion.checkArmorStand()) {
            BastionManager.getInstance().setBastionActive(bastion, false);
            // 盔甲架失效，回退到原部署点
            if (BastionManager.getInstance().respawnAtDeployPoint(player.server.overworld(), player)) {
                player.sendSystemMessage(Component.literal("§e该兵站已失效，已自动在原部署点复活"));
            } else {
                player.sendSystemMessage(Component.literal("§c该兵站已失效，且无可用原部署点！"));
            }
            return;
        }

        // 传送玩家到兵站位置
        player.teleportTo(bastion.getLevel(),
            bastion.getPosition().getX() + 0.5,
            bastion.getPosition().getY() + 1,
            bastion.getPosition().getZ() + 0.5,
            0f, 0f);

        // 设置生存模式
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        // 移除所有效果
        player.removeAllEffects();

        // 给予短暂的无敌效果
        int invincibilityTicks = org.espetro.config.GameConfig.getRespawnInvincibilityTicks();
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
            invincibilityTicks,
            127,
            false, false, false
        ));

        player.sendSystemMessage(Component.literal("§a已在 §e" + bastion.getName() + " §a复活！"));
    }
}
