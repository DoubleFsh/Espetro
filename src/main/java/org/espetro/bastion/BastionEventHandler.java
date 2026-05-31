package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.bastion.BastionBuildingWandItem;
import org.espetro.bastion.BastionManager;
import org.espetro.network.BastionSelectionPacket;
import org.espetro.team.ClassCountManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.TroopCountManager;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 兵站相关事件处理器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public class BastionEventHandler {

    // 死亡限制时间（tick），防止多次触发
    private static final int DEATH_COOLDOWN_TICKS = 20;

    /**
     * 玩家死亡时触发
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 检查是否是对战阶段
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE) {
            return;
        }

        // 检查玩家是否选择了职业
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) {
            return;
        }

        // 保存玩家的原部署点位置（使用队伍配置的部署点）
        // 使用 Espetro.getPlayerTeam() 而非 getTeamFromFactionStatic()，
        // 防止战局中加入者因 factionId 解析出错误的队伍，导致部署点坐标错乱
        String team = Espetro.getPlayerTeam(player);
        if (team != null) {
            SpawnPointConfig.SpawnPoint spawnPoint = SpawnPointConfig.getSpawnPoint(team);
            BastionManager bastionManager = BastionManager.getInstance();
            bastionManager.savePlayerDeployPoint(player,
                new BlockPos((int) spawnPoint.x, (int) spawnPoint.y, (int) spawnPoint.z),
                player.server.overworld());
        }

        // 记录死亡状态
        BastionManager.getInstance().onPlayerDeath(player.server.overworld(), player.getUUID());

        Espetro.LOGGER.info("玩家 {} 死亡，进入兵站选择状态", player.getName().getString());
    }

    /**
     * 玩家复活时触发
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 检查玩家是否在等待兵站选择
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            // 立即设置旁观模式
            player.setGameMode(GameType.SPECTATOR);

            // 施加失明效果
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false));

            // 锁定玩家位置（不可移动）
            Vec3 lockPos = player.position();
            BastionManager.getInstance().lockPlayerPosition(player.getUUID(), lockPos);

            // 延迟发送兵站选择消息（等玩家完全重生）
            ServerPlayer finalPlayer = player;
            player.server.execute(() -> {
                // 再次检查状态
                if (BastionManager.getInstance().isWaitingForBastion(finalPlayer.getUUID())) {
                    // 发送兵站选择消息
                    BastionSelectionPacket.sendBastionSelectionMessage(finalPlayer);
                }
            });
        }
    }

    /**
     * 玩家登录时检查是否在等待复活状态
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 如果玩家在等待兵站选择状态
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            // 清除等待状态，让玩家正常游戏
            BastionManager.getInstance().clearWaiting(player.getUUID());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e你之前的复活选择已重置。"));
        }
    }

    /**
     * 盔甲架受伤事件 - 用于检测兵站核心受损
     */
    @SubscribeEvent
    public static void onArmorStandHurt(LivingHurtEvent event) {
        Entity entity = event.getEntity();

        // 检查是否是兵站盔甲架
        if (entity instanceof ArmorStand armorStand) {
            if (armorStand.getTags().contains("bastion_armor_stand")) {
                Espetro.LOGGER.debug("兵站盔甲架受伤: 伤害={}, 当前血量={}, 剩余血量={}",
                    event.getAmount(), armorStand.getHealth(), armorStand.getHealth() - event.getAmount());

                // 检查是否即将死亡
                if (armorStand.getHealth() - event.getAmount() <= 0) {
                    // 标记为即将被摧毁，下一个Tick处理
                    armorStand.addTag("bastion_about_to_destroy");
                }
            }
        }
    }

    /**
     * 盔甲架死亡事件 - 兵站失效
     */
    @SubscribeEvent
    public static void onArmorStandDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();

        // 检查是否是兵站盔甲架
        if (entity instanceof ArmorStand armorStand) {
            if (armorStand.getTags().contains("bastion_armor_stand")) {
                // 找到对应的兵站并失效
                UUID armorStandId = armorStand.getUUID();
                String bastionName = "";
                String bastionTeam = "";
                boolean found = false;
                for (BastionData bastion : BastionManager.getInstance().getAllBastions()) {
                    if (bastion.getArmorStandId() != null &&
                        bastion.getArmorStandId().equals(armorStandId)) {

                        bastion.setActive(false);
                        bastionName = bastion.getName();
                        bastionTeam = bastion.getTeam();
                        found = true;
                        Espetro.LOGGER.info("兵站 {} 被摧毁！", bastion.getName());

                        // 广播消息给队伍成员
                        Espetro.broadcastToTeam(bastion.getTeam(),
                            "§c[兵站] §e" + bastion.getName() + " §c已被摧毁！");

                        break;
                    }
                }

                if (found) {
                    // 扣除队伍兵力
                    int penalty = BastionManager.getInstance().getDestroyTroopPenalty();
                    TroopCountManager troopManager = TroopCountManager.getInstance();
                    if ("ATTACK".equals(bastionTeam)) {
                        troopManager.modifyAttackTroops(-penalty);
                    } else {
                        troopManager.modifyDefendTroops(-penalty);
                    }
                    Espetro.broadcastToTeam(bastionTeam, "§c[兵站] §e" + bastionName + " §c已被摧毁！- " + penalty + " 兵力");

                    // 通知在场的队伍玩家
                    ServerPlayer commander = findCommanderForTeam(bastionTeam);
                    if (commander != null) {
                        commander.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§c你的兵站 §e" + bastionName + " §c已被摧毁！"
                        ));
                    }
                }
            }
        }
    }

    /**
     * 查找队伍的指挥官
     */
    @Nullable
    private static ServerPlayer findCommanderForTeam(String team) {
        var server = Espetro.getServer();
        if (server == null) return null;

        var voteManager = org.espetro.team.VoteManager.getInstance();
        java.util.UUID commanderId = "ATTACK".equals(team) ?
            voteManager.getAttackCommander() : voteManager.getDefendCommander();

        if (commanderId != null) {
            return server.getPlayerList().getPlayer(commanderId);
        }
        return null;
    }

    /**
     * 玩家重生时的状态复制
     * 等待状态由 onPlayerRespawn 处理，这里不做清除
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // 不做任何操作，等待状态保留给 onPlayerRespawn 处理
        // 如果玩家在死亡画面断开连接，onPlayerLoggedIn 会清除状态
    }

    /**
     * 每 tick 检查等待复活选择的玩家，锁定其位置
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        Vec3 lockPos = BastionManager.getInstance().getPlayerLockPosition(player.getUUID());
        if (lockPos != null) {
            // 如果玩家移动了，将其传送回锁定位置
            if (player.distanceToSqr(lockPos) > 0.01) {
                player.teleportTo(lockPos.x, lockPos.y, lockPos.z);
                player.setDeltaMovement(0, 0, 0);
            }
        }
    }
}
