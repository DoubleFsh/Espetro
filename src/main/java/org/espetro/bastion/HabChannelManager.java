package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.espetro.Espetro;
import org.espetro.logistics.LogisticsConfig;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 兵站（HAB）原地读条建造：玩家在己方 Radio 建造半径内站定 10 秒，
 * 期间位移即取消；完成后在其站立坐标生成兵站并从覆盖 Radio 扣建材。
 */
public final class HabChannelManager {

    public static final int CHANNEL_TICKS = 200; // 10s

    private static final HabChannelManager INSTANCE = new HabChannelManager();

    public static HabChannelManager getInstance() {
        return INSTANCE;
    }

    private static final class Channel {
        final UUID playerId;
        final ServerLevel level;
        final Vec3 anchor;
        final BlockPos targetPos;
        final String team;
        int ticksLeft = CHANNEL_TICKS;

        Channel(ServerPlayer player, String team) {
            this.playerId = player.getUUID();
            this.level = player.serverLevel();
            this.anchor = player.position();
            this.targetPos = player.blockPosition();
            this.team = team;
        }
    }

    private final Map<UUID, Channel> channels = new HashMap<>();

    private HabChannelManager() {
    }

    /** 尝试开始读条；失败时已向玩家发送原因。 */
    public void start(ServerPlayer player, String team) {
        cancel(player.getUUID(), null);

        BastionManager manager = BastionManager.getInstance();
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();

        if (!manager.isInsideFriendlyRadioBuildRadius(level, pos, team)) {
            player.sendSystemMessage(Component.literal(
                "§c兵站必须建在己方 Radio 建造半径（"
                    + (int) LogisticsConfig.get().radioBuildRadius + " 格）内！"));
            return;
        }
        int cost = manager.getHabConstructionCost();
        int available = manager.sumConstructionInCoveringRadios(level, pos, team);
        if (available < cost) {
            player.sendSystemMessage(Component.literal(
                "§c部署兵站需要 " + cost + " 点 Radio 建材库存！覆盖范围内合计仅 " + available + " 点。"));
            return;
        }

        channels.put(player.getUUID(), new Channel(player, team));
        player.sendSystemMessage(Component.literal(
            "§e开始建造兵站：原地不动 " + (CHANNEL_TICKS / 20) + " 秒…（移动会取消）"));
    }

    public void cancel(UUID playerId, @Nullable String reason) {
        Channel channel = channels.remove(playerId);
        if (channel != null && reason != null) {
            ServerPlayer player = playerOf(channel);
            if (player != null) {
                player.sendSystemMessage(Component.literal(reason));
            }
        }
    }

    public void reset() {
        channels.clear();
    }

    /** 每 tick 由 ServerRuntimeMaintenance 调用。 */
    public void tick() {
        if (channels.isEmpty()) {
            return;
        }
        Iterator<Channel> iterator = channels.values().iterator();
        while (iterator.hasNext()) {
            Channel channel = iterator.next();
            ServerPlayer player = playerOf(channel);
            if (player == null || !player.isAlive() || player.serverLevel() != channel.level) {
                iterator.remove();
                continue;
            }
            if (player.position().distanceToSqr(channel.anchor) > 0.01D) {
                iterator.remove();
                player.sendSystemMessage(Component.literal("§c你移动了，兵站建造已取消。"));
                continue;
            }

            channel.ticksLeft--;
            if (channel.ticksLeft <= 0) {
                iterator.remove();
                complete(player, channel);
                continue;
            }
            if (channel.ticksLeft % 20 == 0) {
                player.displayClientMessage(Component.literal(
                    "§e建造兵站中… §f" + (channel.ticksLeft / 20 + 1) + "s"), true);
            }
        }
    }

    private void complete(ServerPlayer player, Channel channel) {
        BastionManager manager = BastionManager.getInstance();
        int cost = manager.getHabConstructionCost();
        boolean bypass = player.isCreative()
            && LogisticsConfig.get().getRadio().creativeBypassesPlanks;

        if (!manager.isInsideFriendlyRadioBuildRadius(channel.level, channel.targetPos, channel.team)) {
            player.sendSystemMessage(Component.literal("§c覆盖此处的己方 Radio 已失效，建造取消。"));
            return;
        }
        if (!bypass && cost > 0
            && !manager.tryDebitConstructionFromCoveringRadios(
                channel.level, channel.targetPos, channel.team, cost)) {
            player.sendSystemMessage(Component.literal("§c覆盖 Radio 建材不足，建造取消。"));
            return;
        }

        String habName = generateHabName(channel.team);
        BastionData hab = manager.createHab(channel.level, channel.targetPos, channel.team, habName);
        if (hab == null) {
            if (!bypass && cost > 0) {
                var covering = manager.findCoveringRadios(channel.level, channel.targetPos, channel.team);
                if (!covering.isEmpty()) {
                    covering.get(0).addConstructionSupplies(cost, LogisticsConfig.get().maxConstruction);
                }
            }
            player.sendSystemMessage(Component.literal("§c兵站创建失败！"));
            return;
        }

        // 建筑中心列不放方块（墙在四周、屋顶在 y+2），玩家原地即安全，无需传送
        DeployActions.buildHabStructure(channel.level, channel.targetPos, channel.team);

        channel.level.playSound(null, channel.targetPos, SoundEvents.EXPERIENCE_ORB_PICKUP,
            SoundSource.PLAYERS, 1.0f, 1.2f);
        int activation = LogisticsConfig.get().habActivationSeconds;
        player.sendSystemMessage(Component.literal("§a兵站 §e" + habName + " §a已建成！位置: "
            + channel.targetPos.getX() + ", " + channel.targetPos.getY() + ", " + channel.targetPos.getZ()));
        if (activation > 0) {
            player.sendSystemMessage(Component.literal("§7启用倒计时 " + activation + " 秒。"));
        }
        if (!bypass && cost > 0) {
            player.sendSystemMessage(Component.literal("§7已从覆盖 Radio 扣除 " + cost + " 点建材。"));
        }
        Espetro.broadcastToTeam(channel.team, "§6[兵站] §a" + player.getName().getString()
            + " §a建成了兵站 §b" + habName);
    }

    private static String generateHabName(String team) {
        int number = 1;
        for (BastionData bastion : BastionManager.getInstance().getAllBastions()) {
            if (bastion.isActive() && team.equals(bastion.getTeam()) && bastion.isHab()) {
                number++;
            }
        }
        return "ATTACK".equals(team) ? "进攻兵站-" + number : "防守兵站-" + number;
    }

    @Nullable
    private static ServerPlayer playerOf(Channel channel) {
        MinecraftServer server = Espetro.getServer();
        return server == null ? null : server.getPlayerList().getPlayer(channel.playerId);
    }
}
