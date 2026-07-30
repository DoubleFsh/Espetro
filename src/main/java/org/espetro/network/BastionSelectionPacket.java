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
import org.espetro.team.TeamPackManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 兵站选择 — 复活时优先传送到 HAB 核心实体（原版实体传送语义）。
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
     * 玩家选择兵站复活：优先传送到核心盔甲架实体；未加载时用记录坐标 + 临时 PORTAL。
     */
    public static boolean handleBastionSelect(ServerPlayer player, UUID bastionId) {
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) return false;

        String team = Espetro.getPlayerTeam(player);
        if (team == null) return false;

        String classId = ClassCountManager.getInstance().getPlayerClass(player.getUUID());
        if (classId == null || classId.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c请先选择职业后再选择部署点！"));
            NetworkManager.NET.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new ClassCountSyncPacket("§c请先选择职业后再选择部署点！", true));
            return false;
        }

        BastionData bastion = BastionManager.getInstance().getBastion(bastionId);
        if (bastion == null || !bastion.isActive() || !team.equals(bastion.getTeam())) {
            return TeamPackManager.getInstance().respawnAtTeamPack(player, bastionId);
        }

        // 准备阶段也允许部署（加局加入）
        org.espetro.team.GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != org.espetro.team.GamePhase.BATTLE
            && phase != org.espetro.team.GamePhase.DEPLOYING) {
            player.sendSystemMessage(Component.literal("§c只能在战斗或部署阶段复活！"));
            return false;
        }

        // 先校验等待态，避免非等待玩家反复触发 isHabOperational 的压制副作用
        if (!BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c你已经完成了复活选择！"));
            return false;
        }

        // applySuppression=true：真正选点时写入压制截止时间
        if (!BastionManager.getInstance().isHabOperational(bastion, true)) {
            String status = BastionManager.getInstance().getFobStatus(bastion);
            player.sendSystemMessage(Component.literal(
                "§c无法在该兵站部署：§e" + status
                    + "§c（需己方 Radio 覆盖且未被敌方压制）"));
            return false;
        }

        // 改选 HAB：取消未完成的 Rally 波次队列，避免冷却结束后误拉回队包。
        TeamPackManager.getInstance().cancelPendingRespawn(player.getUUID());

        BastionManager manager = BastionManager.getInstance();
        boolean queued = manager.teleportPlayerToHabAsync(player, bastion, success -> {
            if (!success) {
                player.sendSystemMessage(Component.literal(
                    "§c兵站区块加载失败、超时或已失效，请重新选择部署点。"));
                NetworkManager.sendUnifiedDeployScreen(player, -1);
                return;
            }
            completeHabDeployment(player, bastion, manager);
        });
        if (!queued) {
            if (manager.isHabTeleportPending(player.getUUID())) {
                player.sendSystemMessage(Component.literal("§e正在准备该兵站，请稍候。"));
                return true;
            }
            player.sendSystemMessage(Component.literal("§c该兵站缺少记录坐标或已失效，无法部署！"));
        }
        return queued;
    }

    private static void completeHabDeployment(
        ServerPlayer player,
        BastionData bastion,
        BastionManager manager
    ) {
        manager.clearWaiting(player.getUUID());
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.removeAllEffects();

        int invincibilityTicks = org.espetro.config.GameConfig.getRespawnInvincibilityTicks();
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
            invincibilityTicks,
            127,
            false, false, false
        ));
        GameStateManager.getInstance().applyBattlefieldMiningRestriction(player);
        GameStateManager.getInstance().onMidGameDeployComplete(player);
        player.sendSystemMessage(Component.literal("§a已在 §e" + bastion.getName() + " §a复活！"));
    }
}
