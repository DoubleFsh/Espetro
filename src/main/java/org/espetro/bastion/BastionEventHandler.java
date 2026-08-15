package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.TeamPackManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.logistics.SupplyManager;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.network.RadioRadialPacket;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 兵站相关事件处理器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public class BastionEventHandler {
    private static final double RADIO_DISMANTLE_DISTANCE_SQR = 6.0 * 6.0;
    private static final Map<UUID, RadioDismantleAttempt> RADIO_DISMANTLE_ATTEMPTS = new HashMap<>();
    private static int radioDismantleTickCounter;

    private record RadioDismantleAttempt(UUID radioId, ResourceKey<Level> dimension,
                                         BlockPos pos, long completesAtMillis) {
    }

    /**
     * 兵站核心盔甲架死亡：立即销毁对应兵站（替代每秒轮询）。
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ArmorStand armorStand && isBastionCore(armorStand)) {
            if (!armorStand.level().isClientSide) {
                BastionData bastion = BastionManager.getInstance().findBastionByArmorStand(armorStand.getUUID());
                if (bastion != null && bastion.isActive()) {
                    BastionManager.getInstance().onCoreArmorStandDestroyed(
                        bastion, event.getSource().getEntity());
                }
            }
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 对战阶段正常复活；布防阶段阵亡/重新部署也允许重选部署点。
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE && phase != GamePhase.DEPLOYING) {
            return;
        }
        ServerLevel battlefield = player.serverLevel();
        if (!BattlefieldContext.isActiveBattlefield(battlefield)) {
            return;
        }

        if (phase == GamePhase.DEPLOYING) {
            org.espetro.team.OutpostManager.getInstance()
                .prepareDeployTargets(battlefield);
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
                battlefield);
        }

        // 记录死亡状态
        BastionManager.getInstance().onPlayerDeath(battlefield, player.getUUID());
        TeamPackManager.getInstance().onPlayerDeath(player.getUUID());

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

        TeamPackManager.getInstance().syncTeamPackItem(player);

        // 检查玩家是否在等待兵站选择
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            applyWaitingDeployState(player);

            // 延迟发送统一部署界面（等玩家完全重生）
            ServerPlayer finalPlayer = player;
            player.server.execute(() -> {
                // 再次检查状态
                if (BastionManager.getInstance().isWaitingForBastion(finalPlayer.getUUID())) {
                    // 发送统一部署主界面（集成职业选择、复活点选择、载具部署、地图）
                    int remaining = GameStateManager.getInstance().getCurrentPhase() == GamePhase.DEPLOYING
                        ? GameStateManager.getInstance().getDeployTimeRemainingSeconds()
                        : -1;
                    org.espetro.network.NetworkManager.sendUnifiedDeployScreen(finalPlayer, remaining);
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

        TeamPackManager.getInstance().syncTeamPackItem(player);

        // 如果玩家在等待兵站选择状态
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            // 重新登录不能绕过部署选择：继续保持统一等待状态。
            applyWaitingDeployState(player);
            player.server.execute(() -> {
                if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
                    int remaining = GameStateManager.getInstance().getCurrentPhase() == GamePhase.DEPLOYING
                        ? GameStateManager.getInstance().getDeployTimeRemainingSeconds()
                        : -1;
                    org.espetro.network.NetworkManager.sendUnifiedDeployScreen(player, remaining);
                }
            });
        }
    }

    @SubscribeEvent
    public static void onTeamPackBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        TeamPackManager.TeamPackData teamPack = TeamPackManager.getInstance().findByPos(event.getPos());
        if (teamPack == null) {
            return;
        }

        event.setCanceled(true);

        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        String playerTeam = Espetro.getPlayerTeam(player);
        boolean isEnemy = playerTeam != null && !playerTeam.equals(teamPack.team);
        if (isEnemy) {
            TeamPackManager.getInstance().damageTeamPack(teamPack, player, 1, true);
            return;
        }

        boolean isLeaderOfThisSquad = SquadManager.getInstance().isSquadLeader(player.getUUID())
            && SquadManager.getInstance().getPlayerSquadId(player.getUUID()) == teamPack.squadId;
        if (!isLeaderOfThisSquad) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c只有本小队队长才能拆除队包！"));
            return;
        }

        TeamPackManager.getInstance().destroyTeamPack(teamPack, player, true, false);
    }

    @SubscribeEvent
    public static void onTeamPackEnemyLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        TeamPackManager.TeamPackData teamPack = TeamPackManager.getInstance().findByPos(event.getPos());
        if (teamPack == null) {
            return;
        }

        String playerTeam = Espetro.getPlayerTeam(player);
        boolean isEnemy = playerTeam != null && !playerTeam.equals(teamPack.team);
        if (isEnemy) {
            event.setCanceled(true);
            TeamPackManager.getInstance().damageTeamPack(teamPack, player, 1, true);
        }
    }

    @SubscribeEvent
    public static void onTeamPackRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        TeamPackManager.TeamPackData teamPack = TeamPackManager.getInstance().findByPos(event.getPos());
        if (teamPack == null) {
            return;
        }

        event.setCanceled(true);

        String playerTeam = Espetro.getPlayerTeam(player);
        boolean isEnemy = playerTeam != null && !playerTeam.equals(teamPack.team);
        if (isEnemy) {
            TeamPackManager.getInstance().damageTeamPack(teamPack, player, 1, true);
        }
    }

    @SubscribeEvent
    public static void onTeamPackBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        BlockPos pos = event.getPosition().orElse(null);
        if (pos == null || TeamPackManager.getInstance().findByPos(pos) == null) {
            return;
        }

        float multiplier = TeamPackManager.getInstance().getBreakSpeedMultiplier();
        event.setNewSpeed(event.getNewSpeed() * multiplier);
    }

    @SubscribeEvent
    public static void onExplosionDetonateTeamPack(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            TeamPackManager.TeamPackData teamPack = TeamPackManager.getInstance().findByPos(pos);
            if (teamPack != null) {
                TeamPackManager.getInstance().destroyTeamPackByExplosion(teamPack);
            }
        }
    }

    @SubscribeEvent
    public static void onBastionCoreInteract(PlayerInteractEvent.EntityInteract event) {
        cancelBastionCoreInteract(event, event.getTarget());
    }

    @SubscribeEvent
    public static void onBastionCoreInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        cancelBastionCoreInteract(event, event.getTarget());
    }

    private static void cancelBastionCoreInteract(PlayerInteractEvent event, Entity target) {
        if (!(target instanceof ArmorStand armorStand) || !isBastionCore(armorStand)) {
            return;
        }
        // HAB 盔甲架：取消交互并提示（补给在 Radio 方块上）
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) {
            return;
        }
        BastionData bastion = BastionManager.getInstance().findBastionByArmorStand(armorStand.getUUID());
        if (bastion != null && !bastion.isRadio()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§7这是兵站核心，请向己方 Radio 方块存取补给。"));
        }
    }

    // ==================== Rally 部署包放置 ====================

    /** 玩家放下 Rally 部署包（带 NBT 的信标）：跑 rally 校验，不合法则取消（物品退回）。 */
    @SubscribeEvent
    public static void onRallyItemPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()
            || !event.getPlacedBlock().is(Blocks.BEACON)
            || !(event.getEntity() instanceof ServerPlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        TeamPackManager manager = TeamPackManager.getInstance();
        // 放置瞬间手里仍握着该物品（shrink 在事件之后）
        boolean fromRallyItem = manager.isTeamPackItem(player.getMainHandItem())
            || manager.isTeamPackItem(player.getOffhandItem());
        if (!fromRallyItem) {
            return;
        }

        BlockPos pos = event.getPos();
        String precheck = manager.canPlaceTeamPack(player, level, pos);
        if (precheck != null) {
            event.setCanceled(true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(precheck));
            return;
        }
        String error = manager.placeTeamPack(player, level, pos);
        if (error != null) {
            event.setCanceled(true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(error));
        }
    }

    // ==================== Radio 方块生命周期 ====================

    /** Radio 只能由统一工事系统完成，普通方块放置一律拒绝。 */
    @SubscribeEvent
    public static void onRadioBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()
            || BastionItems.RADIO_BLOCK == null
            || !event.getPlacedBlock().is(BastionItems.RADIO_BLOCK)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) {
            event.setCanceled(true);
            return;
        }

        // Radio 只能通过建造轮盘进入统一施工流程；禁止物品/命令入口绕过进度与占位校验。
        event.setCanceled(true);
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
            "§e请从建造工事轮盘选择 Radio，并左键确认位置。"), true);
    }

    /**
     * Radio 左键：
     * 己方指挥/小队长潜行左键立即收起；敌方潜行左键开始 30 秒拆除。
     */
    @SubscribeEvent
    public static void onRadioRetrieve(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
            || BastionItems.RADIO_BLOCK == null
            || !(event.getLevel() instanceof ServerLevel level)
            || !level.getBlockState(event.getPos()).is(BastionItems.RADIO_BLOCK)) {
            return;
        }
        BastionData bastion = BastionManager.getInstance().findRadioByBlockPos(event.getPos());
        if (bastion == null) {
            return;
        }
        if (FortificationManager.getInstance().contains(level, event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (player.getMainHandItem().getItem() != Items.IRON_SHOVEL) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§e使用工兵铲右键拆除，左键修建或修复。"), true);
            }
            return;
        }
        // Radio 不再使用原版挖掘流程，避免挖掘疲劳、工具和客户端速度差异绕过规则。
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§c尚未加入阵营，无法拆除 Radio。"), true);
            return;
        }

        if (!team.equals(bastion.getTeam())) {
            if (!player.isShiftKeyDown()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§e潜行并左键点击可开始拆除敌方 Radio。"), true);
                return;
            }
            startRadioDismantle(player, bastion);
            return;
        }

        if (!player.isShiftKeyDown()) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§e小队长或指挥官可潜行左键收起己方 Radio。"), true);
            return;
        }
        boolean commander = org.espetro.team.VoteManager.getInstance().isCommander(player.getUUID());
        boolean squadLeader = SquadManager.getInstance().isSquadLeader(player.getUUID());
        if (!commander && !squadLeader) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§c只有小队长或指挥官才能收起己方 Radio。"), true);
            return;
        }

        int lostConstruction = bastion.getConstructionSupplies();
        int lostAmmunition = bastion.getAmmunitionSupplies();

        level.setBlock(event.getPos(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        BastionManager.getInstance().retrieveRadio(bastion, player.getUUID());

        if (BastionItems.RADIO_BLOCK_ITEM != null) {
            ItemStack stack = new ItemStack(BastionItems.RADIO_BLOCK_ITEM);
            if (DeployActions.hasRadioItem(player) || !player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a已收起 Radio §e" + bastion.getName() + "§a，可重新放置。"));
        if (lostConstruction > 0 || lostAmmunition > 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§7库存 §6" + lostConstruction + " 建材§7 / §b" + lostAmmunition + " 弹药§7 已丢失。"));
        }
    }

    /** Radio 禁止进入原版破坏流程；拆除统一由潜行左键计时完成。 */
    @SubscribeEvent
    public static void onRadioBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        BastionData bastion = BastionManager.getInstance().findRadioByBlockPos(event.getPos());
        if (bastion == null) {
            return;
        }
        event.setCanceled(true);
    }

    /** 客户端裂纹显示的兜底：Radio 的实际拆除只由服务端计时流程完成。 */
    @SubscribeEvent
    public static void onRadioBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (BastionItems.RADIO_BLOCK == null || !event.getState().is(BastionItems.RADIO_BLOCK)) {
            return;
        }
        event.setNewSpeed(0.0f);
    }

    private static void startRadioDismantle(ServerPlayer player, BastionData bastion) {
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if ((phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE)
            || !BattlefieldContext.isActiveBattlefield(player.serverLevel())
            || player.isSpectator() || !player.isAlive()) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§c当前无法拆除 Radio。"), true);
            return;
        }
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(bastion.getPosition()))
            > RADIO_DISMANTLE_DISTANCE_SQR) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§c请靠近 Radio 后再开始拆除。"), true);
            return;
        }

        long now = System.currentTimeMillis();
        RadioDismantleAttempt existing = RADIO_DISMANTLE_ATTEMPTS.get(player.getUUID());
        if (existing != null && existing.radioId().equals(bastion.getBastionId())) {
            long remaining = Math.max(1L, (existing.completesAtMillis() - now + 999L) / 1000L);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§e正在拆除 Radio，剩余 §c" + remaining + " §e秒。"), true);
            return;
        }

        RADIO_DISMANTLE_ATTEMPTS.put(player.getUUID(), new RadioDismantleAttempt(
            bastion.getBastionId(), player.serverLevel().dimension(), bastion.getPosition().immutable(),
            now + RadioBlock.BREAK_SECONDS * 1000L));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§e开始拆除敌方 Radio。请在附近等待 §c" + RadioBlock.BREAK_SECONDS + " §e秒。"));
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
            "§e拆除 Radio：§c" + RadioBlock.BREAK_SECONDS + " 秒"), true);
    }

    /**
     * 仅在存在拆除任务时每秒校验一次，不扫描全体玩家或全体 Radio。
     */
    @SubscribeEvent
    public static void onRadioDismantleServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || RADIO_DISMANTLE_ATTEMPTS.isEmpty()) {
            return;
        }
        radioDismantleTickCounter++;
        if (radioDismantleTickCounter < 20) {
            return;
        }
        radioDismantleTickCounter = 0;

        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            clearRadioDismantleAttempts();
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, RadioDismantleAttempt>> iterator =
            RADIO_DISMANTLE_ATTEMPTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RadioDismantleAttempt> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            RadioDismantleAttempt attempt = entry.getValue();
            ServerLevel level = server.getLevel(attempt.dimension());
            BastionData bastion = level == null ? null
                : BastionManager.getInstance().findRadioByBlockPos(attempt.pos());

            String cancelReason = validateRadioDismantle(player, level, bastion, attempt);
            if (cancelReason != null) {
                iterator.remove();
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "§cRadio 拆除已取消：" + cancelReason), true);
                }
                continue;
            }

            long remainingMillis = attempt.completesAtMillis() - now;
            if (remainingMillis > 0L) {
                long remainingSeconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§e拆除 Radio：§c" + remainingSeconds + " 秒"), true);
                continue;
            }

            iterator.remove();
            level.setBlock(attempt.pos(), Blocks.AIR.defaultBlockState(), 3);
            BastionManager.getInstance().destroyBastionWithManpower(bastion, player, true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§a敌方 Radio 已拆除。"));
        }
    }

    @Nullable
    private static String validateRadioDismantle(@Nullable ServerPlayer player,
                                                  @Nullable ServerLevel level,
                                                  @Nullable BastionData bastion,
                                                  RadioDismantleAttempt attempt) {
        if (player == null || level == null || bastion == null || !bastion.isActive()
            || !bastion.getBastionId().equals(attempt.radioId())) {
            return "目标已不存在";
        }
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if ((phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE)
            || player.serverLevel() != level || player.isSpectator() || !player.isAlive()) {
            return "当前状态不允许拆除";
        }
        String playerTeam = Espetro.getPlayerTeam(player);
        if (playerTeam == null || playerTeam.equals(bastion.getTeam())) {
            return "阵营状态已改变";
        }
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(attempt.pos()))
            > RADIO_DISMANTLE_DISTANCE_SQR) {
            return "离开了 Radio 附近";
        }
        return null;
    }

    public static void clearRadioDismantleAttempts() {
        RADIO_DISMANTLE_ATTEMPTS.clear();
        radioDismantleTickCounter = 0;
    }

    /** 爆炸摧毁 Radio：按敌方行为扣兵力。 */
    @SubscribeEvent
    public static void onExplosionDetonateRadio(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            if (event.getLevel() instanceof ServerLevel level
                && FortificationManager.getInstance().contains(level, pos)) {
                continue; // 统一工事完整度由 FortificationEventHandler 按结构比例处理。
            }
            BastionData bastion = BastionManager.getInstance().findRadioByBlockPos(pos);
            if (bastion != null) {
                BastionManager.getInstance().destroyBastionWithManpower(bastion,
                    event.getExplosion().getExploder(), true);
            }
        }
    }

    /**
     * Radio 方块交互（服务端）：
     * <ul>
     *   <li>普通右键：不再打开职业轮盘（已移至弹药箱），此处只拦截原版交互。</li>
     *   <li>潜行右键不再存入任何物资。</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onRadioBlockRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
            || BastionItems.RADIO_BLOCK == null) {
            return;
        }
        ServerLevel level = (ServerLevel) event.getLevel();
        if (!level.getBlockState(event.getPos()).is(BastionItems.RADIO_BLOCK)) {
            return;
        }
        BastionData bastion = BastionManager.getInstance().findRadioByBlockPos(event.getPos());
        if (bastion == null) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        String team = Espetro.getPlayerTeam(player);
        if (team == null || !team.equals(bastion.getTeam())) {
            return; // 敌方静默
        }

        // 背包→Radio 存入已取消；补给仅通过 F 键载具装卸。换职已移至弹药箱。
    }

    @SubscribeEvent
    public static void onSupplySourceInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (player.getMainHandItem().getItem() == Items.IRON_SHOVEL
            && FortificationManager.getInstance().contains(level, event.getPos())) {
            return;
        }
        try {
            if (SupplyManager.getInstance().handleSourceInteraction(player, level, event.getPos())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        } catch (Throwable t) {
            Espetro.LOGGER.error("补给站交互失败 at {}", event.getPos(), t);
        }
    }

    /**
     * 右击已建成弹药箱：打开更换职业根菜单；潜行右击打开逐项补给会话。
     * 识别以工事 behavior=ammo_crate 为准，原版潜影盒只作为旧世界形状兼容。
     */
    @SubscribeEvent
    public static void onShulkerBoxInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getLevel().isClientSide()) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        BlockPos clickedPos = event.getPos();
        if (player.getMainHandItem().getItem() == Items.IRON_SHOVEL
            && FortificationManager.getInstance().contains(level, clickedPos)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        BlockState state = level.getBlockState(clickedPos);
        boolean legacyShulker = state.is(Blocks.SHULKER_BOX)
            || state.is(Blocks.RED_SHULKER_BOX)
            || state.is(Blocks.BLUE_SHULKER_BOX);

        String team = Espetro.getPlayerTeam(player);
        if (team == null) return;

        BastionData radio = FortificationManager.getInstance()
            .findRadioForAmmoCrate(level, clickedPos, team);
        boolean registeredCrate = FortificationManager.getInstance()
            .isAmmoCrateAt(level, clickedPos, team);
        if (radio == null && !registeredCrate && !legacyShulker) return;
        if (radio == null) {
            if (registeredCrate
                || BastionManager.getInstance().findBastionByShulkerPos(clickedPos) != null) {
                event.setCanceled(true);
            }
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player.isShiftKeyDown()) {
            org.espetro.logistics.resupply.ResupplySessionManager.open(player,
                org.espetro.logistics.resupply.ResupplySourceRef.radio(clickedPos));
        } else {
            RadioRadialPacket.openClassMenuAt(player, clickedPos);
        }
    }

    private static boolean isBastionCore(ArmorStand armorStand) {
        return armorStand.getTags().contains("bastion_armor_stand");
    }

    /**
     * 核心盔甲架离开世界。
     * <p>
     * <b>绝不</b>在此处因区块卸载摧毁兵站。卸载时 RemovalReason 在部分环境下
     * 不是 {@link Entity.RemovalReason#UNLOADED_TO_CHUNK}（甚至为 null），
     * 旧逻辑会把卸载误判为摧毁（聊天「已被摧毁！不扣兵力」+ attacker=unknown）。
     * <p>
     * 真摧毁仅走：
     * <ul>
     *   <li>{@link #onPlayerDeath} → LivingDeathEvent（被打掉）</li>
     *   <li>方块/命令/己方拆除等显式 {@code destroyBastion*}</li>
     * </ul>
     * LeaveLevel 对 KILLED 也只做幂等兜底（死亡事件通常已处理）。
     */
    @SubscribeEvent
    public static void onBastionCoreLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof ArmorStand armorStand) || !isBastionCore(armorStand)) {
            return;
        }
        Entity.RemovalReason reason = entity.getRemovalReason();
        // 白名单：只有明确击杀才可能摧毁。卸载 / 换维 / null / DISCARD 卸载路径一律忽略。
        // DISCARDED 也不在此摧毁：显式 dismantle 会先 destroyBastion 再 discard（已 isActive=false）。
        if (reason != Entity.RemovalReason.KILLED) {
            return;
        }
        BastionData bastion = BastionManager.getInstance().findBastionByArmorStand(armorStand.getUUID());
        if (bastion != null && bastion.isActive()) {
            BastionManager.getInstance().onCoreArmorStandDestroyed(bastion, null);
        }
    }

    /**
     * 玩家重生时的状态复制
     * 等待状态由 onPlayerRespawn 处理，这里不做清除
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // 不做任何操作，等待状态保留给 onPlayerRespawn 处理
        // 重新登录也不会清除等待状态，避免绕过部署点选择
    }

    /** 每秒分片兜底；正常移动由服务端移动包拦截器直接拒绝。 */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        BastionManager bastionManager = BastionManager.getInstance();
        java.util.UUID playerId = player.getUUID();
        net.minecraft.world.phys.Vec3 lock = bastionManager.getPlayerLockPosition(playerId);
        boolean waiting = bastionManager.isWaitingForBastion(playerId);
        // 所有已选阵营的部署等待（含首次部署）都使用冒险模式。
        // 非 Bastion waiting 的位置锁属于未选阵营/阶段 hold，仍维持旁观模式。
        boolean adventureDeployWaiting = waiting;
        if (lock == null && !waiting) {
            return;
        }

        // 尚无位置锁的等待态：每秒补一次完整 hold，避免每 tick 走重路径
        if (lock == null) {
            if (player.tickCount % 20 == 0) {
                applyWaitingDeployState(player);
            } else {
                maintainHoldWithoutTeleport(player, false, adventureDeployWaiting);
            }
            return;
        }

        int shard = Math.floorMod(playerId.hashCode(), 20);
        if (Math.floorMod(player.tickCount, 20) != shard) {
            return;
        }
        boolean needModeFix = adventureDeployWaiting
            ? player.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.ADVENTURE
            : !player.isSpectator();

        boolean teleported = enforceLockedPosition(player, lock);
        boolean forceBlind = teleported || needModeFix
            || Math.floorMod(player.tickCount, 60) == shard;
        maintainHoldWithoutTeleport(player, forceBlind, adventureDeployWaiting);
    }

    /**
     * @return true 若执行了回锚传送（调用方应强制失明重推）
     */
    private static boolean enforceLockedPosition(ServerPlayer player, net.minecraft.world.phys.Vec3 lock) {
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0f;
        // 略放宽阈值，减少无意义 teleport（0.5 格²）
        if (player.distanceToSqr(lock) > 0.25) {
            player.teleportTo(player.serverLevel(), lock.x, lock.y, lock.z,
                player.getYRot(), player.getXRot());
            player.setDeltaMovement(0, 0, 0);
            return true;
        }
        return false;
    }

    private static void maintainHoldWithoutTeleport(
            ServerPlayer player, boolean forceBlindResync, boolean adventureDeployWaiting) {
        if (adventureDeployWaiting) {
            GameStateManager.enforceAdventureBlindness(player, forceBlindResync);
        } else {
            GameStateManager.enforceSpectatorBlindness(player, forceBlindResync);
        }
    }

    private static void applyWaitingDeployState(ServerPlayer player) {
        GameStateManager.getInstance().applyDeploymentWaitingState(player);
    }
}
