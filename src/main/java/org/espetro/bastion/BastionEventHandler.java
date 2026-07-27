package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.TeamPackManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.logistics.SupplyManager;
import org.espetro.mapconfig.BattlefieldContext;

import javax.annotation.Nullable;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.UUID;

/**
 * 兵站相关事件处理器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public class BastionEventHandler {

    /**
     * 玩家死亡时触发
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

    /** Radio 方块放置校验：不合法则取消（物品退回）。 */
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

        BastionManager manager = BastionManager.getInstance();
        LogisticsConfig.RadioPlacementSettings radio = LogisticsConfig.get().getRadio();
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        String phaseName = phase != null ? phase.name() : "";
        BlockPos pos = event.getPos();

        String error = null;
        String team = Espetro.getPlayerTeam(player);
        if (!radio.allowsPhase(phaseName)) {
            error = "§c当前阶段不能部署 Radio！允许阶段: " + String.join(", ", radio.allowedPhases);
        } else if (team == null) {
            error = "§c无法确定你的队伍！";
        } else {
            boolean commander = org.espetro.team.VoteManager.getInstance().isCommander(player.getUUID());
            boolean squadLeader = SquadManager.getInstance().isSquadLeader(player.getUUID());
            if (radio.requireCommander && !commander) {
                error = "§c只有指挥官才能部署 Radio！";
            } else if (!commander && !(radio.allowSquadLeader && squadLeader)) {
                error = radio.allowSquadLeader
                    ? "§c只有小队长或指挥官才能部署 Radio！"
                    : "§c只有指挥官才能部署 Radio！";
            }
        }
        if (error == null) {
            int cooldownSeconds = manager.getEffectiveRadioCooldownSeconds();
            error = manager.canBuildBastion(player.getUUID(), cooldownSeconds);
        }
        if (error == null && !manager.hasBastionCapacity(team)) {
            error = "§c本方生效 Radio 数量已达到上限（" + manager.getBastionLimitPerTeam() + "个）！";
        }
        if (error == null
            && manager.findNearestRadio(level, pos, null, radio.exclusionRadius) != null) {
            error = "§c附近已有 Radio，排斥半径为 " + (int) radio.exclusionRadius + " 格。";
        }
        if (error == null && radio.teammateCount > 0) {
            int nearby = countNearbyTeammates(player, team, pos, radio.teammateRadius);
            if (nearby < radio.teammateCount) {
                error = "§c部署 Radio 需要放置点 " + (int) radio.teammateRadius
                    + " 格内至少 " + radio.teammateCount + " 名队友！当前仅 " + nearby + " 名。";
            }
        }

        if (error != null) {
            event.setCanceled(true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(error));
            return;
        }

        String radioName = generateRadioName(team);
        BastionData bastion = manager.createRadio(level, pos, team, radioName);
        if (bastion == null) {
            event.setCanceled(true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cRadio 创建失败！"));
            return;
        }
        manager.setBastionCooldown(player.getUUID());
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§aRadio §e" + radioName + " §a已部署！右键存/取补给，潜行右键鱼竿在圈内建兵站。"));
        Espetro.broadcastToTeam(team, "§6[Radio] §a" + player.getName().getString()
            + " §a部署了 Radio §b" + radioName);
    }

    private static String generateRadioName(String team) {
        int number = 1;
        for (BastionData bastion : BastionManager.getInstance().getAllBastions()) {
            if (bastion.isActive() && team.equals(bastion.getTeam()) && bastion.isRadio()) {
                number++;
            }
        }
        return "ATTACK".equals(team) ? "进攻Radio-" + number : "防守Radio-" + number;
    }

    private static int countNearbyTeammates(ServerPlayer player, String team, BlockPos center, double radius) {
        double radiusSquared = radius * radius;
        int count = 0;
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other == player || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            if (team.equals(Espetro.getPlayerTeam(other))
                && other.blockPosition().distSqr(center) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    /** 己方指挥/小队长 Shift+左键收起 Radio 回物品栏。 */
    @SubscribeEvent
    public static void onRadioRetrieve(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
            || !player.isShiftKeyDown()
            || BastionItems.RADIO_BLOCK == null
            || !(event.getLevel() instanceof ServerLevel level)
            || !level.getBlockState(event.getPos()).is(BastionItems.RADIO_BLOCK)) {
            return;
        }
        BastionData bastion = BastionManager.getInstance().findRadioByBlockPos(event.getPos());
        if (bastion == null) {
            return;
        }
        String team = Espetro.getPlayerTeam(player);
        if (team == null || !team.equals(bastion.getTeam())) {
            return; // 敌方走正常破坏
        }
        boolean commander = org.espetro.team.VoteManager.getInstance().isCommander(player.getUUID());
        boolean squadLeader = SquadManager.getInstance().isSquadLeader(player.getUUID());
        if (!commander && !squadLeader) {
            return; // 普通队员无收起权限，走正常破坏
        }

        event.setCanceled(true);

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

    /** Radio 方块破坏：己方拆不扣兵力，敌方拆扣兵力。 */
    @SubscribeEvent
    public static void onRadioBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        BastionData bastion = BastionManager.getInstance().findRadioByBlockPos(event.getPos());
        if (bastion == null) {
            return;
        }
        boolean enemyAction = true;
        if (event.getPlayer() instanceof ServerPlayer player) {
            String playerTeam = Espetro.getPlayerTeam(player);
            enemyAction = playerTeam == null || !playerTeam.equals(bastion.getTeam());
        }
        BastionManager.getInstance().destroyBastionWithManpower(bastion,
            event.getPlayer(), enemyAction);
        if (!enemyAction && event.getPlayer() instanceof ServerPlayer player) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e已拆除己方 Radio（不扣兵力）。"));
        }
    }

    /** Radio 方块挖掘速度锁定：任何工具/效果下都约 30 秒。 */
    @SubscribeEvent
    public static void onRadioBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (BastionItems.RADIO_BLOCK == null || !event.getState().is(BastionItems.RADIO_BLOCK)) {
            return;
        }
        // destroyProgress += speed / hardness / 30（徒手无工具惩罚），每 tick；
        // 需要 30s=600tick：speed = hardness * 30 / 600
        float hardness = event.getState().getDestroySpeed(
            event.getEntity().level(), event.getPosition().orElse(BlockPos.ZERO));
        event.setNewSpeed(hardness * 30.0f / (RadioBlock.BREAK_SECONDS * 20.0f));
    }

    /** 爆炸摧毁 Radio：按敌方行为扣兵力。 */
    @SubscribeEvent
    public static void onExplosionDetonateRadio(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            BastionData bastion = BastionManager.getInstance().findRadioByBlockPos(pos);
            if (bastion != null) {
                BastionManager.getInstance().destroyBastionWithManpower(bastion,
                    event.getExplosion().getExploder(), true);
            }
        }
    }

    /** Radio 方块交互：潜行右键=存补给，右键=领职业弹药。 */
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

        if (player.isShiftKeyDown()) {
            SupplyManager.DepositResult result = SupplyManager.getInstance().depositAll(player, bastion);
            if (result.success()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a已存入 Radio §7| §6建材 +" + result.construction()
                        + " §7| §b弹药 +" + result.ammunition()
                        + " §7| 库存 §6" + bastion.getConstructionSupplies()
                        + "§7/§b" + bastion.getAmmunitionSupplies()
                        + " §7| " + BastionManager.getInstance().getFobStatus(bastion)));
            } else {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    result.error() + " §7| §6" + bastion.getConstructionSupplies()
                        + " 建材 §7| §b" + bastion.getAmmunitionSupplies()
                        + " 弹药 §7| " + BastionManager.getInstance().getFobStatus(bastion)));
            }
            return;
        }

        performAmmoResupply(player, bastion);
    }

    @SubscribeEvent
    public static void onSupplySourceInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (SupplyManager.getInstance().handleSourceInteraction(player, level, event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * 玩家右击兵站潜影盒 - 弹药补给
     */
    @SubscribeEvent
    public static void onShulkerBoxInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        BlockPos clickedPos = event.getPos();
        BlockState state = level.getBlockState(clickedPos);

        // 检查是否是潜影盒
        if (!state.is(Blocks.RED_SHULKER_BOX) && !state.is(Blocks.BLUE_SHULKER_BOX)) return;

        // 根据潜影盒位置查找兵站
        BastionData bastion = BastionManager.getInstance().findBastionByShulkerPos(clickedPos);
        if (bastion == null) return;

        // 检查是否是同一个队伍
        String team = Espetro.getPlayerTeam(player);
        if (team == null || !team.equals(bastion.getTeam())) {
            event.setCanceled(true);
            return; // 静默，不给敌方提示
        }

        if (player.isShiftKeyDown()) {
            SupplyManager.DepositResult deposit = SupplyManager.getInstance().depositAll(player, bastion);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(deposit.success()
                ? "§a已向 FOB 存入 §6" + deposit.construction() + " 建材 §a与 §b"
                    + deposit.ammunition() + " 弹药。"
                : deposit.error()));
            event.setCanceled(true);
            return;
        }

        performAmmoResupply(player, bastion);
        event.setCanceled(true);
    }

    /**
     * 从 Radio 领取职业弹药补给（原潜影盒逻辑，供潜影盒与 Radio 实体两个入口复用）。
     */
    static void performAmmoResupply(ServerPlayer player, BastionData bastion) {
        if (!bastion.isAmmoCrateBuilt()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c该 Radio 尚未建成弹药箱，需要继续存入建材。"));
            return;
        }

        // 获取玩家职业配置
        String classId = ClassCountManager.getInstance().getPlayerClass(player.getUUID());
        String variantId = ClassCountManager.getInstance().getPlayerVariant(player.getUUID());
        if (classId == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c你没有选择职业，无法补给弹药！"));
            return;
        }

        // 加载补给配置
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        MinecraftServer server = player.getServer();
        if (server != null) loader.ensureLoaded(server.getResourceManager());
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        FactionDataLoader.ClassVariantData variant = kit != null ? kit.getVariant(variantId) : null;
        FactionDataLoader.ResupplyData resupply = variant != null ? variant.resupply : null;
        if (resupply == null || resupply.items == null || resupply.items.length == 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c该职业装备变体没有配置弹药补给！"));
            return;
        }

        int ammoCost = resupply.ammoCost != null
            ? Math.max(0, resupply.ammoCost)
            : LogisticsConfig.get().defaultResupplyAmmoCost;
        if (bastion.getAmmunitionSupplies() <= 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§cRadio 弹药库存为 0，无法补给。"));
            return;
        }

        String errorMsg = BastionManager.getInstance().tryResupply(player.getUUID());
        if (errorMsg != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(errorMsg));
            return;
        }

        // 智能补给：检查背包已有数量，补充到上限
        int givenItems = 0;
        StringBuilder detail = new StringBuilder();
        for (FactionDataLoader.ResupplyItem ri : resupply.items) {
            if (ri.id == null || ri.id.isBlank()) continue;
            ItemStack template = createResupplyStack(ri);
            if (template.isEmpty()) {
                continue;
            }
            Item item = template.getItem();
            if (item == net.minecraft.world.item.Items.AIR) {
                Espetro.LOGGER.warn("补给物品不存在: {}", ri.id);
                continue;
            }
            int maxCap = ri.max > 0 ? ri.max : 64;
            int giveCount = ri.count > 0 ? ri.count : 16;

            // 统计背包中已有数量
            int current = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (matchesResupplyItem(stack, template)) current += stack.getCount();
            }

            // 计算可补充数量（不超过上限）
            int canGive = Math.min(giveCount, maxCap - current);
            if (canGive > 0) {
                ItemStack giveStack = template.copy();
                giveStack.setCount(canGive);
                if (!player.getInventory().add(giveStack)) {
                    player.drop(giveStack, false);
                }
                givenItems++;
                if (!detail.isEmpty()) detail.append(", ");
                detail.append(giveStack.getHoverName().getString()).append(" ×").append(canGive);
            }
        }

        if (givenItems == 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e你的弹药已满，无需补给！"));
            return;
        }

        // 记录补给
        int chargedAmmo = 0;
        int availableAmmo = Math.min(ammoCost, bastion.getAmmunitionSupplies());
        if (availableAmmo > 0
            && BastionManager.getInstance().tryConsumeFobAmmunition(bastion, availableAmmo)) {
            chargedAmmo = availableAmmo;
        }
        BastionManager.getInstance().recordResupply(player.getUUID());
        String chargeDetail = chargedAmmo == ammoCost
            ? "消耗 §b" + chargedAmmo + " Radio 弹药"
            : "Radio 弹药不足 §7(需要 §b" + ammoCost + "§7)，已扣除剩余 §b"
                + chargedAmmo + " §7并归零";
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a▸ 已补充: §f" + detail + "  §7| " + chargeDetail + " §7| 冷却5分钟"));
    }

    private static ItemStack createResupplyStack(FactionDataLoader.ResupplyItem ri) {
        String id = ri.id.trim();
        String nbt = ri.nbt;
        int tagStart = id.indexOf('{');
        if (tagStart >= 0) {
            if (nbt == null || nbt.isBlank()) {
                nbt = id.substring(tagStart);
            }
            id = id.substring(0, tagStart);
        }

        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == net.minecraft.world.item.Items.AIR) {
            Espetro.LOGGER.warn("补给物品不存在: {}", id);
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(item);
        if (nbt != null && !nbt.isBlank()) {
            try {
                CompoundTag tag = TagParser.parseTag(nbt);
                stack.setTag(tag);
            } catch (CommandSyntaxException e) {
                Espetro.LOGGER.warn("补给物品 NBT 格式错误: id={}, nbt={}, error={}", id, nbt, e.getMessage());
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    private static boolean matchesResupplyItem(ItemStack stack, ItemStack template) {
        if (template.hasTag()) {
            return ItemStack.isSameItemSameTags(stack, template);
        }
        return stack.is(template.getItem());
    }

    private static boolean isBastionCore(ArmorStand armorStand) {
        return armorStand.getTags().contains("bastion_armor_stand");
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

    /**
     * 每 tick：位置锁内的玩家强制固定；等待部署点选择的玩家刷新失明/旁观等待态。
     * 失明客户端同步：每秒 force resync 一次（跨维后客户端可能丢效果）。
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        BastionManager bastionManager = BastionManager.getInstance();
        net.minecraft.world.phys.Vec3 lock = bastionManager.getPlayerLockPosition(player.getUUID());
        if (lock != null) {
            enforceLockedPosition(player, lock);
            // 每秒强制向客户端重推失明，避免跨维 Respawn 后客户端无效果。
            boolean forceBlindPacket = (player.tickCount % 20) == 0;
            GameStateManager.enforceSpectatorBlindness(player, forceBlindPacket);
            if (bastionManager.isWaitingForBastion(player.getUUID())) {
                applyWaitingDeployState(player);
            }
            return;
        }

        if (bastionManager.isWaitingForBastion(player.getUUID())) {
            applyWaitingDeployState(player);
        }
    }

    private static void enforceLockedPosition(ServerPlayer player, net.minecraft.world.phys.Vec3 lock) {
        if (player.distanceToSqr(lock) > 0.04) {
            player.teleportTo(player.serverLevel(), lock.x, lock.y, lock.z,
                player.getYRot(), player.getXRot());
        }
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0f;
    }

    private static void applyWaitingDeployState(ServerPlayer player) {
        GameStateManager.getInstance().applyDeploymentWaitingState(player);
    }
}
