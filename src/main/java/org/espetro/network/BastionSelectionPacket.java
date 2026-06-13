package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
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
 * 兵站选择 — 纯编号驱动，不依赖区块加载/盔甲架实体检查
 */
public class BastionSelectionPacket {

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
        ctx.get().enqueueWork(() -> { /* 客户端已由 UnifiedDeployScreen 处理 */ });
        ctx.get().setPacketHandled(true);
    }

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
     * 旧复活点选择入口的兼容转发。
     */
    public static void sendBastionSelectionMessage(ServerPlayer player) {
        NetworkManager.sendDeployPointSelectScreen(player);
    }

    /**
     * 玩家选择兵站复活：只看存储的编号和坐标，不查区块、不查实体。
     */
    public static boolean handleBastionSelect(ServerPlayer player, UUID bastionId) {
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) return false;

        String team = Espetro.getPlayerTeam(player);
        if (team == null) return false;

        BastionData bastion = BastionManager.getInstance().getBastion(bastionId);
        if (bastion == null) {
            player.sendSystemMessage(Component.literal("§c无效的兵站选择！"));
            return false;
        }

        // 准备阶段也允许部署（加局加入）
        org.espetro.team.GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != org.espetro.team.GamePhase.BATTLE
            && phase != org.espetro.team.GamePhase.DEPLOYING) {
            player.sendSystemMessage(Component.literal("§c只能在战斗或部署阶段复活！"));
            return false;
        }

        // 检查是否在等待复活
        if (!BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c你已经完成了复活选择！"));
            return false;
        }

        // 只检查编号：读取记录的盔甲架坐标。getRecordedArmorStandPosition
        // 内部通过 isOwnedSlot 校验编号仍属于该兵站，失败返回 null。
        BlockPos targetPos = BastionManager.getInstance().getRecordedArmorStandPosition(bastion);
        if (targetPos == null) {
            player.sendSystemMessage(Component.literal("§c该兵站缺少记录坐标或已失效，无法部署！"));
            return false;
        }

        // 传送 — 不清除等待让重试，clearWaiting 在 teleport 成功后调用
        BastionManager.getInstance().clearWaiting(player.getUUID());

        player.teleportTo(bastion.getLevel(),
            targetPos.getX() + 0.5,
            targetPos.getY(),
            targetPos.getZ() + 0.5,
            0f, 0f);

        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.removeAllEffects();

        int invincibilityTicks = org.espetro.config.GameConfig.getRespawnInvincibilityTicks();
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
            invincibilityTicks,
            127,
            false, false, false
        ));

        player.sendSystemMessage(Component.literal("§a已在 §e" + bastion.getName() + " §a复活！"));

        return true;
    }
}
