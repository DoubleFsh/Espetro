package org.espetro.team;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.espetro.Espetro;
import org.espetro.bastion.BastionManager;
import org.espetro.config.GameConfig;
import org.espetro.network.NetworkManager;
import org.espetro.vehicle.VehicleManager;
import org.espetro.stats.PlayerMatchStatsManager;
import org.espetro.mapconfig.ExternalConfigBootstrap;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.mapconfig.ActiveMapConfig;
import org.espetro.governance.CommanderGovernanceManager;
import org.espetro.dimension.BattlefieldWorldManager;
import org.espetro.logistics.SupplyManager;
import org.espetro.stamina.StaminaManager;
import net.minecraftforge.common.MinecraftForge;
import org.espetro.api.event.GamePhaseChangedEvent;

import java.util.*;
import java.util.function.Consumer;

/**
 * 游戏状态管理器
 * 管理游戏各阶段流程
 *
 * 阶段流转（按流程图）：
 * WAITING → DEFEND_COMMANDER_VOTE(20s) → ATTACK_COMMANDER_VOTE(20s)
 * → DEFEND_FACTION_SELECT(30s) → ATTACK_FACTION_SELECT(30s) → FACTION_REVEAL(5s)
 * → DEPLOYING(240s, 守方部署防线/攻方屏障内等待) → BATTLE
 */
public class GameStateManager {

    private static GameStateManager INSTANCE;

    // 当前游戏阶段
    private GamePhase currentPhase = GamePhase.LOBBY;

    // 部署阶段计时器
    private int deployTickCounter = 0;
    // 双方编制揭示阶段计时器
    private int factionRevealTickCounter = 0;
    private static final int TICKS_PER_SECOND = 20;
    private static final int ATTACK_WAITING_BARRIER_SIDE = 200;
    private static final int ATTACK_WAITING_BARRIER_HEIGHT = 20;

    // 攻方等待防守部署时临时放置的屏障，记录原方块以便开战后恢复
    private final Map<BlockPos, BlockState> attackWaitingBarrierBlocks = new HashMap<>();
    private ResourceKey<Level> attackWaitingBarrierDimension;

    // 等待选择队伍的玩家
    private final Set<UUID> waitingForTeam = new HashSet<>();
    // 已选择队伍的玩家
    private final Set<UUID> teamSelectedPlayers = new HashSet<>();
    // 战局中加入的玩家（部署点选择完成前）
    private final Set<UUID> midGameJoiners = new HashSet<>();
    // 已在部署阶段选择过职业的玩家（防止重复选择）
    private final Set<UUID> deployClassSelected = new HashSet<>();
    private int teamSelectTickCounter = 0;
    private int roundEndTickCounter = 0;
    private String pendingRoundWinner = null;
    private ActiveMapConfig pendingMap = null;
    private boolean forceStopInProgress = false;
    /** 主城状态广播：上次推送的在线人数与 tick，用于降频 */
    private int lastHubBroadcastPlayerCount = -1;
    private int lastHubBroadcastTick = Integer.MIN_VALUE;

    private GameStateManager() {
        INSTANCE = this;
    }

    public static GameStateManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GameStateManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new GameStateManager();
    }

    public GamePhase getCurrentPhase() {
        return currentPhase;
    }

    public void setPhase(GamePhase phase) {
        GamePhase previous = this.currentPhase;
        this.currentPhase = phase;
        Espetro.LOGGER.info("游戏阶段切换: {}", phase.getDisplayName());
        NetworkManager.broadcastGamePhase(phase);
        MinecraftForge.EVENT_BUS.post(new GamePhaseChangedEvent(previous, phase));
    }

    /**
     * Administrator entry point for a new round. There is intentionally no
     * ready-player gate: one online player is enough to begin the global map
     * vote, while later joins are handled by the phase-specific join flow.
     */
    public boolean prestart(MinecraftServer server) {
        if (server == null || !currentPhase.isLobbyLike()) {
            return false;
        }
        if (server.getPlayerCount() < 1) {
            return false;
        }
        if (ExternalConfigBootstrap.getUsableMaps().isEmpty()) {
            Espetro.broadcastToAll("§c[Espetro] 没有通过校验的地图，无法开始。请查看服务端日志。");
            return false;
        }

        clearRoundRuntime(false);
        PlayerMatchStatsManager.getInstance().resetMatch(
            server.getPlayerList().getPlayers());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            TeamManager.removeFromAllTeams(server.getScoreboard(), player.getName().getString());
            ClassEquipment.clearEquipment(player);
        }

        setPhase(GamePhase.MAP_VOTE);
        if (!MapVoteManager.getInstance().start(server)) {
            setPhase(GamePhase.LOBBY);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                applyHubState(player);
            }
            return false;
        }
        // 地图投票起即进入统一 MatchHold：旁观 + 失明 + 高空禁移（勿 applyHubState）。
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            applyMatchHoldState(player, HoldAnchor.HUB_HIGH);
        }
        Espetro.broadcastToAll("§6地图投票开始，所有在线玩家均可投票。");
        return true;
    }

    /** Called by the global MapVoteManager on the server thread. */
    public void onMapVoteFinished(ActiveMapConfig winner) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || currentPhase != GamePhase.MAP_VOTE || winner == null || !winner.usable) {
            return;
        }
        pendingMap = winner;
        setPhase(GamePhase.MAP_LOADING);
        // 装载阶段保持 hold；禁止 applyHubState（会摘失明并允许活动）。
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            applyMatchHoldState(player, HoldAnchor.AUTO);
            player.sendSystemMessage(Component.literal("§e正在装载战场地图：" + winner.displayName));
        }
        BattlefieldWorldManager.getInstance().importAndLoad(server, winner, result -> {
            if (!result.success()) {
                pendingMap = null;
                setPhase(GamePhase.LOBBY);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    applyHubState(player);
                }
                Espetro.broadcastToAll("§c地图装载失败：" + result.error());
                broadcastHubStatus();
                return;
            }
            startTeamSelect();
        });
    }

    /**
     * 统一阶段等待态：SPECTATOR + BLINDNESS + 零速度 + Bastion 位置锁。
     * 从地图投票到部署落地前的禁锢路径应全部走此方法。
     */
    public enum HoldAnchor {
        /** 战场高空等待点（要求 BattlefieldContext 已 active）。 */
        BATTLEFIELD_WAIT,
        /** 主城出生点上方 waitingY。 */
        HUB_HIGH,
        /** 仅维持旁观/失明/零速，位置锁沿用已有 lock（若无则按 AUTO 解析）。 */
        CURRENT_LOCK,
        /** 战场已 active 则战场等待点，否则主城高空。 */
        AUTO
    }

    public void applyMatchHoldState(ServerPlayer player, HoldAnchor anchor) {
        if (player == null || player.connection == null) {
            return;
        }
        HoldAnchor resolved = anchor == null ? HoldAnchor.AUTO : anchor;
        if (resolved == HoldAnchor.AUTO) {
            resolved = BattlefieldContext.isActive()
                ? HoldAnchor.BATTLEFIELD_WAIT : HoldAnchor.HUB_HIGH;
        }
        if (resolved == HoldAnchor.CURRENT_LOCK) {
            Vec3 existing = BastionManager.getInstance().getPlayerLockPosition(player.getUUID());
            if (existing == null) {
                resolved = BattlefieldContext.isActive()
                    ? HoldAnchor.BATTLEFIELD_WAIT : HoldAnchor.HUB_HIGH;
            } else {
                enforceSpectatorBlindness(player);
                player.setDeltaMovement(0, 0, 0);
                player.fallDistance = 0f;
                if (player.distanceToSqr(existing) > 0.01) {
                    player.teleportTo(player.serverLevel(),
                        existing.x, existing.y, existing.z, 0f, 0f);
                }
                return;
            }
        }

        enforceSpectatorBlindness(player);
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0f;

        if (resolved == HoldAnchor.BATTLEFIELD_WAIT) {
            if (!BattlefieldContext.isActive()) {
                resolved = HoldAnchor.HUB_HIGH;
            } else {
                ServerLevel battlefield = BattlefieldContext.requireBattlefield(player.server);
                Vec3 waitingPosition = getWaitingPosition();
                if (player.serverLevel() != battlefield
                    || player.distanceToSqr(waitingPosition) > 0.01) {
                    player.teleportTo(battlefield,
                        waitingPosition.x, waitingPosition.y, waitingPosition.z, 0f, 0f);
                }
                BastionManager.getInstance().lockPlayerPosition(player.getUUID(), waitingPosition);
                // 跨维 Respawn 后客户端丢药水：传送后再强制同步失明包。
                enforceSpectatorBlindness(player, true);
                return;
            }
        }

        // HUB_HIGH：主城出生点 XZ，高度用 waitingY（高空黑底）。
        ServerLevel hub = player.server.overworld();
        BlockPos spawn = hub.getSharedSpawnPos();
        double x = spawn.getX() + 0.5;
        double y = GameConfig.getWaitingY();
        double z = spawn.getZ() + 0.5;
        Vec3 hold = new Vec3(x, y, z);
        if (player.serverLevel() != hub || player.distanceToSqr(hold) > 0.25) {
            player.teleportTo(hub, x, y, z, 0f, 0f);
        }
        BastionManager.getInstance().lockPlayerPosition(player.getUUID(), hold);
        // 同维也可能需刷新；跨维到 hub 时同样必须重推失明。
        enforceSpectatorBlindness(player, true);
    }

    /**
     * MatchHold 强制旁观 + 失明。
     * <p>跨维 {@code teleportTo} 会发 Respawn 包，客户端重建 LocalPlayer 且
     * <b>不复制</b>药水效果；若服务端仍 {@code hasEffect(BLINDNESS)} 而只做
     * {@code addEffect}，可能不发更新包，客户端无失明、透明阶段 UI 会透出世界。
     * {@code forceClientResync=true} 时 remove 再 add 强制同步；tick 路径可 false 并节流。
     */
    public static void enforceSpectatorBlindness(ServerPlayer player) {
        enforceSpectatorBlindness(player, true);
    }

    public static void enforceSpectatorBlindness(ServerPlayer player, boolean forceClientResync) {
        if (player == null) {
            return;
        }
        if (!player.isSpectator()) {
            player.setGameMode(GameType.SPECTATOR);
        }
        if (forceClientResync) {
            player.removeEffect(MobEffects.BLINDNESS);
            player.addEffect(new MobEffectInstance(
                MobEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false));
        } else if (!player.hasEffect(MobEffects.BLINDNESS)) {
            player.addEffect(new MobEffectInstance(
                MobEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false));
        }
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0f;
    }

    /**
     * @deprecated 使用 {@link #applyMatchHoldState(ServerPlayer, HoldAnchor)}；保留兼容旧调用。
     */
    @Deprecated
    public void applyMatchLoadingHoldState(ServerPlayer player) {
        applyMatchHoldState(player, HoldAnchor.AUTO);
    }

    private void startTeamSelect() {
        MinecraftServer server = Espetro.getServer();
        if (server == null || pendingMap == null || !BattlefieldContext.isActive()) {
            setPhase(GamePhase.LOBBY);
            if (server != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    applyHubState(player);
                }
            }
            return;
        }

        waitingForTeam.clear();
        teamSelectedPlayers.clear();
        midGameJoiners.clear();
        teamSelectTickCounter = 0;
        setPhase(GamePhase.TEAM_SELECT);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            clearPlayerRoundAssignment(player);
            waitingForTeam.add(player.getUUID());
            applyMatchHoldState(player, HoldAnchor.BATTLEFIELD_WAIT);
            NetworkManager.sendOpenFactionScreen(player);
        }
        broadcastTeamSelectState();
    }

    // ========== 队伍选择阶段 ==========

    public void onTeamSelected(ServerPlayer player, String factionId) {
        if (currentPhase != GamePhase.TEAM_SELECT) {
            return;
        }
        String resolvedTeam = getTeamFromFactionStatic(factionId);
        if (!"ATTACK".equals(resolvedTeam) && !"DEFEND".equals(resolvedTeam)) {
            return;
        }

        waitingForTeam.remove(player.getUUID());
        teamSelectedPlayers.add(player.getUUID());

        // TEAM_SELECT uses ATTACK / DEFEND as the temporary faction id. The
        // winning formation replaces it after both formation votes finish.
        ClassCountManager.getInstance().setPlayerFaction(player.getUUID(), resolvedTeam);
        ClassCountManager.getInstance().setPlayerTeam(player.getUUID(), resolvedTeam);
        if ("ATTACK".equals(resolvedTeam)) {
            TeamManager.joinAttackTeam(player.server, player.getName().getString());
        } else {
            TeamManager.joinDefendTeam(player.server, player.getName().getString());
        }
        PlayerMatchStatsManager.getInstance().onTeamSelected(player, resolvedTeam);

        Espetro.LOGGER.info("玩家 {} 选择了队伍 {}", player.getName().getString(), resolvedTeam);
        broadcastTeamSelectState();
    }

    public void forceStartCommanderVote() {
        if (currentPhase == GamePhase.TEAM_SELECT && teamSelectedPlayers.isEmpty()) {
            Espetro.LOGGER.info("没有玩家选择队伍！");
            return;
        }
        if (currentPhase == GamePhase.TEAM_SELECT) {
            finishTeamSelect();
            return;
        }
        startDefendCommanderVote();
    }

    private void finishTeamSelect() {
        if (currentPhase != GamePhase.TEAM_SELECT) {
            return;
        }
        // Players who did not choose during the free-selection window remain
        // in the battlefield waiting area and may still join later as
        // reinforcements through the same server-authoritative side picker.
        midGameJoiners.addAll(waitingForTeam);
        waitingForTeam.clear();
        broadcastTeamSelectState(false);
        startDefendCommanderVote();
    }

    private void broadcastTeamSelectState() {
        broadcastTeamSelectState(true);
    }

    private void broadcastTeamSelectState(boolean active) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        int attack = 0;
        int defend = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String team = ClassCountManager.getInstance().getPlayerTeam(player.getUUID());
            if ("ATTACK".equals(team)) attack++;
            if ("DEFEND".equals(team)) defend++;
        }
        int remaining = Math.max(0,
            GameConfig.getTeamSelectSeconds() - teamSelectTickCounter / TICKS_PER_SECOND);
        long end = server.overworld().getGameTime() + remaining * 20L;
        NetworkManager.broadcastTeamSelectState(attack, defend, remaining, end, active);
    }

    // ========== 阶段流转 ==========

    /**
     * 开始守方指挥官投票
     */
    private void startDefendCommanderVote() {
        setPhase(GamePhase.DEFEND_COMMANDER_VOTE);
        VoteManager.getInstance().initPlayers();
        VoteManager.getInstance().startDefendVote();
    }

    /**
     * 开始攻方指挥官投票
     */
    private void startAttackCommanderVote() {
        setPhase(GamePhase.ATTACK_COMMANDER_VOTE);
        VoteManager.getInstance().startAttackVote();
    }

    /**
     * 开始守方编制选择
     */
    private void startDefendFactionSelect() {
        setPhase(GamePhase.DEFEND_FACTION_SELECT);
        ClassSelectManager.getInstance().initFactionPool();
        ClassSelectManager.getInstance().startDefendSelecting();
    }

    /**
     * 开始攻方编制选择
     */
    private void startAttackFactionSelect() {
        setPhase(GamePhase.ATTACK_FACTION_SELECT);
        ClassSelectManager.getInstance().startAttackSelecting();
    }

    /**
     * 开始部署阶段
     */
    private void startDeploying() {
        setPhase(GamePhase.DEPLOYING);
        deployTickCounter = 0;
        factionRevealTickCounter = 0;
        deployClassSelected.clear();
        BastionManager.getInstance().reset();
        TeamPackManager.getInstance().reset();
        VehicleManager.getInstance().reset();
        removeAttackWaitingBarrier();

        // 激活前哨基地（部署阶段防守方可用）
        OutpostManager.getInstance().activate();

        // 编制选择最终处理
        ClassSelectManager.getInstance().finalizeSelection();

        // 所有玩家先进入统一等待点，选择部署点后才进入战场。
        teleportAllToSpawnPoints();
        // Bastion.reset() 会清除位置锁；只按未选边记录恢复受影响玩家，
        // 不在服务器 tick 中扫描全部在线玩家。
        restoreRecordedUnassignedHolds();
        deployInitialFactionVehicles();
        placeAttackWaitingBarrier();

        // 广播职业选择界面给所有玩家（部署阶段可选职业）
        broadcastClassSelectionForDeploy();

        Espetro.LOGGER.info("防守部署阶段开始，持续{}秒，攻方等待区域边长{}格，高{}格",
            GameConfig.getDeployTimeoutSeconds(), ATTACK_WAITING_BARRIER_SIDE, ATTACK_WAITING_BARRIER_HEIGHT);
    }

    /**
     * 开始双方最终编制揭示阶段。
     */
    private void startFactionReveal() {
        setPhase(GamePhase.FACTION_REVEAL);
        factionRevealTickCounter = 0;

        ClassSelectManager selectManager = ClassSelectManager.getInstance();
        NetworkManager.broadcastFactionRevealScreen(
            selectManager.getFinalAttackClass(),
            selectManager.getFinalDefendClass(),
            GameConfig.getFactionRevealSeconds()
        );

        Espetro.LOGGER.info("双方编制揭示开始，持续{}秒", GameConfig.getFactionRevealSeconds());
    }

    /**
     * 部署阶段开始时广播统一部署界面
     */
    private void broadcastClassSelectionForDeploy() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String team = ClassCountManager.getInstance().getPlayerTeam(player.getUUID());
            if (team == null) continue;

            // 发送统一部署主界面（集成职业选择、复活点选择、载具部署、地图）
            NetworkManager.sendUnifiedDeployScreen(player, GameConfig.getDeployTimeoutSeconds());
        }
        // 职业选择界面已通过 UnifiedDeployScreen 自动打开，不再发送聊天消息
    }

    /**
     * 部署阶段开始时，为本局最终攻守编制各预部署一轮 JSON 中配置的载具。
     */
    private void deployInitialFactionVehicles() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        ServerLevel level = BattlefieldContext.requireBattlefield(server);
        ClassSelectManager selectManager = ClassSelectManager.getInstance();
        int attackCount = deployInitialFactionVehiclesForTeam("ATTACK", selectManager.getFinalAttackClass(), level);
        int defendCount = deployInitialFactionVehiclesForTeam("DEFEND", selectManager.getFinalDefendClass(), level);

        Espetro.LOGGER.info("初始载具预部署完成: 攻方{}辆，守方{}辆", attackCount, defendCount);
    }

    private int deployInitialFactionVehiclesForTeam(String team, String factionId, ServerLevel level) {
        if (factionId == null || factionId.isBlank()) {
            return 0;
        }

        return VehicleManager.getInstance().deployInitialVehicles(factionId, team, level);
    }

    /** 将所有已选队玩家放入统一部署等待状态。 */
    private void teleportAllToSpawnPoints() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        ClassCountManager countManager = ClassCountManager.getInstance();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            String team = countManager.getPlayerTeam(uuid);
            if (team == null) {
                team = Espetro.getPlayerTeam(player);
            }
            if (team == null) {
                String factionId = countManager.getPlayerFaction(uuid);
                if (factionId != null) {
                    team = getTeamFromFactionStatic(factionId);
                }
            }
            if (team != null) {
                prepareDeploySelection(player, team);
            }
        }
    }

    private void saveTeamSpawnAsDeployPoint(ServerPlayer player, String team) {
        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
        ServerLevel overworld = BattlefieldContext.requireBattlefield(player.server);
        BlockPos deployPos = new BlockPos((int) spawn.x, (int) spawn.y, (int) spawn.z);
        BastionManager.getInstance().savePlayerDeployPoint(player, deployPos, overworld);
    }

    private void prepareDeploySelection(ServerPlayer player, String team) {
        saveTeamSpawnAsDeployPoint(player, team);
        BastionManager.getInstance().activatePlayerBastionSelection(player.getUUID());
        applyDeploymentWaitingState(player);
        if ("DEFEND".equals(team)) {
            OutpostManager.getInstance().prepareDeployTargets(BattlefieldContext.requireBattlefield(player.server));
        }
    }

    /**
     * 部署阶段Tick处理
     */
    public void onDeployTick() {
        // 每秒更新一次
        if (deployTickCounter % TICKS_PER_SECOND == 0) {
            broadcastDefenseSetupActionBar(getDeployTimeRemainingSeconds());
        }

        // 部署阶段结束 -> 对战开始
        if (deployTickCounter >= GameConfig.getDeployTimeoutSeconds() * TICKS_PER_SECOND) {
            startBattle();
        }
    }

    public int getDeployTimeRemainingSeconds() {
        if (currentPhase != GamePhase.DEPLOYING) {
            return 0;
        }
        return Math.max(0, GameConfig.getDeployTimeoutSeconds() - (deployTickCounter / TICKS_PER_SECOND));
    }

    private void broadcastDefenseSetupActionBar(int secondsRemaining) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        ClassCountManager countManager = ClassCountManager.getInstance();
        for (UUID uuid : teamSelectedPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;

            String team = countManager.getPlayerTeam(uuid);
            if (team == null) {
                team = getTeamFromFactionStatic(countManager.getPlayerFaction(uuid));
            }

            String message = "ATTACK".equals(team)
                ? "§c等待进攻§e[" + secondsRemaining + "秒]"
                : "§9部署防线§e[" + secondsRemaining + "秒]";
            NetworkManager.sendWaitingStatus(player, message, true);
        }
    }

    private void placeAttackWaitingBarrier() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        removeAttackWaitingBarrier();

        ServerLevel level = BattlefieldContext.requireBattlefield(server);
        if (!BattlefieldContext.isActiveBattlefield(level)) {
            Espetro.LOGGER.error("拒绝在非活动战场维度创建部署屏障: {}",
                level.dimension().location());
            return;
        }
        attackWaitingBarrierDimension = level.dimension();
        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint("ATTACK");
        BlockPos center = new BlockPos((int) Math.floor(spawn.x), (int) Math.floor(spawn.y), (int) Math.floor(spawn.z));

        int half = ATTACK_WAITING_BARRIER_SIDE / 2;
        int minX = center.getX() - half;
        int maxX = minX + ATTACK_WAITING_BARRIER_SIDE - 1;
        int minZ = center.getZ() - half;
        int maxZ = minZ + ATTACK_WAITING_BARRIER_SIDE - 1;
        int baseY = center.getY();
        // Only build the perimeter. A full 200x200 roof previously forced
        // tens of thousands of chunk/block updates and stalled all entities.
        for (int y = baseY; y < baseY + ATTACK_WAITING_BARRIER_HEIGHT; y++) {
            for (int x = minX; x <= maxX; x++) {
                setTemporaryBarrier(level, new BlockPos(x, y, minZ));
                setTemporaryBarrier(level, new BlockPos(x, y, maxZ));
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                setTemporaryBarrier(level, new BlockPos(minX, y, z));
                setTemporaryBarrier(level, new BlockPos(maxX, y, z));
            }
        }

        Espetro.LOGGER.info("已创建攻方等待屏障: center={}, side={}, height={}, blocks={}",
            center, ATTACK_WAITING_BARRIER_SIDE, ATTACK_WAITING_BARRIER_HEIGHT, attackWaitingBarrierBlocks.size());
    }

    private void setTemporaryBarrier(ServerLevel level, BlockPos pos) {
        BlockState previous = level.getBlockState(pos);
        if (!previous.isAir() && !previous.getCollisionShape(level, pos).isEmpty()) {
            return;
        }
        if (!attackWaitingBarrierBlocks.containsKey(pos)) {
            attackWaitingBarrierBlocks.put(pos.immutable(), previous);
        }
        level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 18);
    }

    private int removeAttackWaitingBarrier() {
        return removeAttackWaitingBarrier(Espetro.getServer());
    }

    /**
     * 恢复本次运行记录的临时屏障，并扫描预期屏障外壳清除崩服/异常退出留下的孤儿屏障。
     * 服务器停机时也会显式调用。
     */
    public int cleanupTemporaryBarriers(MinecraftServer server) {
        return removeAttackWaitingBarrier(server);
    }

    private int removeAttackWaitingBarrier(MinecraftServer server) {
        if (server == null) {
            attackWaitingBarrierBlocks.clear();
            attackWaitingBarrierDimension = null;
            return 0;
        }

        ResourceKey<Level> dimension = attackWaitingBarrierDimension;
        ServerLevel level = dimension == null ? null : server.getLevel(dimension);
        if (level == null || Level.OVERWORLD.equals(level.dimension())) {
            attackWaitingBarrierBlocks.clear();
            attackWaitingBarrierDimension = null;
            return 0;
        }
        int restored = 0;
        for (Map.Entry<BlockPos, BlockState> entry : attackWaitingBarrierBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            if (level.getBlockState(pos).is(Blocks.BARRIER)) {
                level.setBlock(pos, entry.getValue(), 3);
                restored++;
            }
        }
        attackWaitingBarrierBlocks.clear();
        int orphaned = removeOrphanedAttackWaitingBarriers(level);
        attackWaitingBarrierDimension = null;
        int total = restored + orphaned;
        if (total > 0) {
            Espetro.LOGGER.info("已清理攻方等待屏障: 恢复{}个记录方块, 删除{}个残留屏障",
                restored, orphaned);
        }
        return total;
    }

    private int removeOrphanedAttackWaitingBarriers(ServerLevel level) {
        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint("ATTACK");
        BlockPos center = new BlockPos(
            (int) Math.floor(spawn.x), (int) Math.floor(spawn.y), (int) Math.floor(spawn.z));
        int half = ATTACK_WAITING_BARRIER_SIDE / 2;
        int minX = center.getX() - half;
        int maxX = minX + ATTACK_WAITING_BARRIER_SIDE - 1;
        int minZ = center.getZ() - half;
        int maxZ = minZ + ATTACK_WAITING_BARRIER_SIDE - 1;
        int baseY = center.getY();
        int removed = 0;

        for (int y = baseY; y < baseY + ATTACK_WAITING_BARRIER_HEIGHT; y++) {
            for (int x = minX; x <= maxX; x++) {
                removed += removeBarrierAt(level, new BlockPos(x, y, minZ));
                removed += removeBarrierAt(level, new BlockPos(x, y, maxZ));
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                removed += removeBarrierAt(level, new BlockPos(minX, y, z));
                removed += removeBarrierAt(level, new BlockPos(maxX, y, z));
            }
        }

        return removed;
    }

    private int removeBarrierAt(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.BARRIER)) {
            return 0;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
        return 1;
    }

    /**
     * 开始对战
     */
    private void startBattle() {
        removeAttackWaitingBarrier();

        // 开战前先销毁虚拟前哨，确保 BATTLE 阶段的任何请求都无法再使用。
        boolean hadActiveOutposts = OutpostManager.getInstance().isAvailable();
        OutpostManager.getInstance().deactivate();
        setPhase(GamePhase.BATTLE);
        if (hadActiveOutposts) {
            Espetro.broadcastToTeam("DEFEND", "§c⚔ 攻方已开始进攻，前哨基地已销毁！");
        }

        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        // 未选择部署点的玩家即使布防结束也继续留在统一等待点；
        // 只有部署命令成功后才会由 BastionManager.clearWaiting() 解除。
        BastionManager bastionManager = BastionManager.getInstance();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isRecordedUnassigned(player)) {
                // 开战事件不得像普通已部署玩家一样摘除失明。
                applyMatchHoldState(player, HoldAnchor.CURRENT_LOCK);
            } else if (bastionManager.isWaitingForBastion(player.getUUID())) {
                applyDeploymentWaitingState(player);
                NetworkManager.sendUnifiedDeployScreen(player, -1);
            } else {
                player.removeEffect(MobEffects.BLINDNESS);
                applyBattlefieldMiningRestriction(player);
            }
        }

        // 初始化兵力统计
        TroopCountManager.getInstance().initializeTroops();
        CommanderGovernanceManager.getInstance().syncCommandersFromVoteManager();

        // 扣除初始载具的 troopValue（初始载具占用兵力预算）
        VehicleManager vm = VehicleManager.getInstance();
        int attackVehicleCost = vm.getInitialTroopValueForTeam("ATTACK");
        int defendVehicleCost = vm.getInitialTroopValueForTeam("DEFEND");
        if (attackVehicleCost > 0) {
            TroopCountManager.getInstance().modifyAttackTroops(-attackVehicleCost);
            Espetro.LOGGER.info("攻方初始载具占用 {} 兵力，剩余: {}", attackVehicleCost, TroopCountManager.getInstance().getAttackTroops());
        }
        if (defendVehicleCost > 0) {
            TroopCountManager.getInstance().modifyDefendTroops(-defendVehicleCost);
            Espetro.LOGGER.info("守方初始载具占用 {} 兵力，剩余: {}", defendVehicleCost, TroopCountManager.getInstance().getDefendTroops());
        }

        org.espetro.network.NetworkManager.broadcastTroopCounts(
            TroopCountManager.getInstance().getAttackTroops(),
            TroopCountManager.getInstance().getDefendTroops()
        );

        // 广播开始消息
        Espetro.broadcastToAll("§6========================================");
        Espetro.broadcastToAll("§a§l★ 对战开始！ ★");
        Espetro.broadcastToAll("§6========================================");

        Espetro.LOGGER.info("===== 对战开始 =====");
    }

    private void onFactionRevealTick() {
        factionRevealTickCounter++;
        if (factionRevealTickCounter >= GameConfig.getFactionRevealSeconds() * TICKS_PER_SECOND) {
            startDeploying();
        }
    }

    // ========== 服务器Tick ==========

    public void onServerTick() {
        switch (currentPhase) {
            case LOBBY, WAITING_FOR_PLAYERS:
                // 人数变化时立即推；否则最多 5 秒一次，降低主城挂机广播负载
                if (deployTickCounter % TICKS_PER_SECOND == 0) {
                    broadcastHubStatusThrottled();
                }
                break;
            case MAP_VOTE:
                MinecraftServer mapVoteServer = Espetro.getServer();
                if (mapVoteServer != null) {
                    MapVoteManager.getInstance().onServerTick(mapVoteServer);
                }
                break;
            case MAP_LOADING:
                break;
            case TEAM_SELECT:
                teamSelectTickCounter++;
                if (teamSelectTickCounter % TICKS_PER_SECOND == 0) {
                    broadcastTeamSelectState();
                }
                if (teamSelectTickCounter >= GameConfig.getTeamSelectSeconds() * TICKS_PER_SECOND) {
                    finishTeamSelect();
                }
                break;
            case DEFEND_COMMANDER_VOTE:
                VoteManager.getInstance().onServerTick();
                if (VoteManager.getInstance().isCurrentVoteTimedOut()) {
                    VoteManager.getInstance().finishCurrentVote();
                    startAttackCommanderVote();
                }
                break;
            case ATTACK_COMMANDER_VOTE:
                VoteManager.getInstance().onServerTick();
                if (VoteManager.getInstance().isCurrentVoteTimedOut()) {
                    VoteManager.getInstance().finishCurrentVote();
                    startDefendFactionSelect();
                }
                break;
            case DEFEND_FACTION_SELECT:
                ClassSelectManager.getInstance().onServerTick();
                if (ClassSelectManager.getInstance().isCurrentSelectTimedOut()) {
                    ClassSelectManager.getInstance().finishCurrentSelecting();
                    startAttackFactionSelect();
                }
                break;
            case ATTACK_FACTION_SELECT:
                ClassSelectManager.getInstance().onServerTick();
                if (ClassSelectManager.getInstance().isCurrentSelectTimedOut()) {
                    ClassSelectManager.getInstance().finishCurrentSelecting();
                    startFactionReveal();
                }
                break;
            case FACTION_REVEAL:
                onFactionRevealTick();
                break;
            case DEPLOYING:
                onDeployTick();
                break;
            case BATTLE:
                MinecraftServer battleServer = Espetro.getServer();
                if (battleServer != null) {
                    CommanderGovernanceManager.getInstance().onServerTick(battleServer);
                }
                break;
            case ROUND_END:
                roundEndTickCounter++;
                if (roundEndTickCounter >= GameConfig.getRoundEndSeconds() * TICKS_PER_SECOND) {
                    beginCleanup();
                }
                break;
            case CLEANUP:
                break;
            default:
                break;
        }
        deployTickCounter++;
    }

    /**
     * 部署阶段重置 Bastion 记录后，按选边状态集合恢复未选阵营玩家的等待锁。
     * 此方法只在阶段切换事件执行一次，复杂度与实际未选边人数相关。
     */
    private void restoreRecordedUnassignedHolds() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return;
        }
        for (UUID playerId : waitingForTeam) {
            restoreUnassignedHold(server, playerId);
        }
        for (UUID playerId : midGameJoiners) {
            restoreUnassignedHold(server, playerId);
        }
    }

    private void restoreUnassignedHold(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null && isRecordedUnassigned(player)) {
            applyMatchHoldState(player, HoldAnchor.AUTO);
        }
    }

    private boolean isRecordedUnassigned(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (!waitingForTeam.contains(playerId) && !midGameJoiners.contains(playerId)) {
            return false;
        }
        String team = ClassCountManager.getInstance().getPlayerTeam(playerId);
        if (team == null) {
            team = Espetro.getPlayerTeam(player);
        }
        return !"ATTACK".equals(team) && !"DEFEND".equals(team);
    }

    // ========== 工具方法 ==========

    private void teleportToTeamSpawn(ServerPlayer player, String team) {
        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
        ServerLevel overworld = BattlefieldContext.requireBattlefield(player.server);
        player.teleportTo(overworld, spawn.x, spawn.y, spawn.z, spawn.yaw, 0f);

        BlockPos deployPos = new BlockPos((int) spawn.x, (int) spawn.y, (int) spawn.z);
        org.espetro.bastion.BastionManager.getInstance().savePlayerDeployPoint(player, deployPos, overworld);
    }

    public static String getTeamFromFactionStatic(String factionId) {
        if (factionId == null) return "DEFEND";

        if ("ATTACK".equalsIgnoreCase(factionId) || "DEFEND".equalsIgnoreCase(factionId)) {
            return factionId.toUpperCase();
        }

        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.FactionData factionData = loader.getFaction(factionId);
        if (factionData != null && factionData.team != null) {
            return factionData.team;
        }

        FactionConfig config = FactionConfigLoader.loadFaction(factionId);
        if (config != null && config.team != null) {
            return config.team;
        }

        String lower = factionId.toLowerCase();
        if (lower.contains("attack") ||
            lower.contains("pla") ||
            lower.contains("russia") ||
            lower.contains("rus") ||
            lower.contains("militia")) {
            return "ATTACK";
        }

        return "DEFEND";
    }

    // ========== 重置 ==========

    public void resetGame() {
        MinecraftServer server = Espetro.getServer();
        forceStopInProgress = false;
        clearRoundRuntime(false);
        pendingRoundWinner = null;
        pendingMap = null;
        currentPhase = GamePhase.LOBBY;

        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                clearPlayerRoundAssignment(player);
                applyHubState(player);
            }
            if (BattlefieldContext.isActive()) {
                BattlefieldWorldManager.getInstance().cleanupBattlefield(server, null, () -> {
                    setPhase(GamePhase.LOBBY);
                    broadcastHubStatus();
                });
            } else {
                setPhase(GamePhase.LOBBY);
                broadcastHubStatus();
            }
        }
        Espetro.LOGGER.info("游戏状态已重置到主城");
    }

    /**
     * Immediately aborts any phase and returns every online player to the
     * overworld hub. Battlefield cleanup is serialized with an in-flight map
     * activation, so an administrator can safely use this during MAP_LOADING.
     *
     * <p>The battlefield is detached from the server tick list, its storage
     * handles are closed without saving match damage, and the entire save-side
     * dimension copy is deleted. A later round imports a new copy from the
     * read-only EsWorld template.</p>
     *
     * @return {@code false} only when there is no server or another forced stop
     * is already waiting for battlefield cleanup
     */
    public boolean forceStopGame(
        MinecraftServer server,
        Consumer<BattlefieldWorldManager.Result> onComplete
    ) {
        if (server == null || forceStopInProgress) {
            return false;
        }

        forceStopInProgress = true;
        GamePhase stoppedPhase = currentPhase;
        ActiveMapConfig stoppedMap = pendingMap != null ? pendingMap : BattlefieldContext.getOrNull();
        int playerCount = server.getPlayerCount();

        setPhase(GamePhase.CLEANUP);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            clearPlayerRoundAssignment(player);
            player.removeAllEffects();
            applyHubState(player);
            StaminaManager.resetPlayer(player);
        }

        clearRoundRuntime(true);
        pendingMap = null;
        pendingRoundWinner = null;
        SupplyManager.getInstance().reset();

        Espetro.broadcastToAll("§6[Espetro] 管理员已强制结束本局，正在返回主城。");
        BattlefieldWorldManager.getInstance().cleanupBattlefield(server, stoppedMap, cleanupResult -> {
            forceStopInProgress = false;
            setPhase(GamePhase.LOBBY);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                clearPlayerRoundAssignment(player);
                player.removeAllEffects();
                applyHubState(player);
                StaminaManager.resetPlayer(player);
                NetworkManager.sendOpenHubScreen(player, server.getPlayerCount(),
                    "对局已结束，等待管理员开始下一局");
            }
            broadcastHubStatus();
            if (cleanupResult.success()) {
                Espetro.broadcastToAll(
                    "§a[Espetro] 所有玩家已返回主城，战场存档副本已删除。");
            } else {
                Espetro.broadcastToAll(
                    "§c[Espetro] 玩家已返回主城，但战场存档副本删除失败："
                        + cleanupResult.error());
            }
            Espetro.LOGGER.info(
                "管理员强制终止战局完成: previousPhase={}, map={}, players={}, terrainReset={}",
                stoppedPhase, stoppedMap == null ? "none" : stoppedMap.dimensionId,
                playerCount, cleanupResult.success());
            if (onComplete != null) {
                try {
                    onComplete.accept(cleanupResult);
                } catch (Exception e) {
                    Espetro.LOGGER.error("强制终止战局完成回调执行失败", e);
                }
            }
        });
        return true;
    }

    private void clearRoundRuntime(boolean clearStats) {
        waitingForTeam.clear();
        teamSelectedPlayers.clear();
        midGameJoiners.clear();
        deployClassSelected.clear();
        deployTickCounter = 0;
        factionRevealTickCounter = 0;
        teamSelectTickCounter = 0;
        roundEndTickCounter = 0;
        removeAttackWaitingBarrier();

        MapVoteManager.getInstance().reset();
        VoteManager.getInstance().reset();
        ClassSelectManager.getInstance().reset();
        SquadManager.getInstance().reset();
        TeamPackManager.getInstance().reset();
        OutpostManager.getInstance().reset();
        CommanderSkillManager.getInstance().reset();
        CommanderGovernanceManager.getInstance().reset();
        ClassCountManager.getInstance().resetAll();
        VehicleManager.getInstance().reset();
        TroopCountManager.getInstance().resetTroops();
        BastionManager.getInstance().reset();
        if (clearStats) {
            PlayerMatchStatsManager.getInstance().resetMatch();
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        PlayerMatchStatsManager.getInstance().onPlayerJoin(player);
        // 入服时主动推送指挥官技能数据，确保客户端轮盘可立即显示技能入口
        NetworkManager.sendCommanderSkillSync(player);

        switch (currentPhase) {
            case LOBBY, WAITING_FOR_PLAYERS -> {
                forcePlayerToHub(player);
                NetworkManager.sendOpenHubScreen(player, player.server.getPlayerCount(),
                    "等待管理员开始下一局");
            }
            case MAP_VOTE -> {
                // 投票中途加入：直接 MatchHold，勿 hub（会摘失明）。
                applyMatchHoldState(player, HoldAnchor.HUB_HIGH);
                MapVoteManager.getInstance().syncToPlayer(player);
            }
            case MAP_LOADING -> {
                applyMatchHoldState(player, HoldAnchor.AUTO);
                player.sendSystemMessage(Component.literal("§e战场正在装载，请稍候。"));
            }
            case TEAM_SELECT -> {
                clearPlayerRoundAssignment(player);
                waitingForTeam.add(player.getUUID());
                applyMatchHoldState(player, HoldAnchor.BATTLEFIELD_WAIT);
                NetworkManager.sendOpenFactionScreen(player);
                broadcastTeamSelectState();
            }
            case ROUND_END, CLEANUP -> {
                forcePlayerToHub(player);
                player.sendSystemMessage(Component.literal("§e本回合正在结算，请等待下一局。"));
            }
            default -> onMidGameJoin(player);
        }
    }

    /**
     * 退服前强制写回主城位置/重生点（主城维度持久不重置）。
     * 应在清空装备并保存 playerdata 之前调用。
     */
    public void onPlayerLeave(ServerPlayer player) {
        if (player != null) {
            forcePlayerToHub(player);
            onPlayerLeave(player.getUUID());
        }
    }

    public void onPlayerLeave(UUID uuid) {
        waitingForTeam.remove(uuid);
        teamSelectedPlayers.remove(uuid);
        midGameJoiners.remove(uuid);
        deployClassSelected.remove(uuid);
        MapVoteManager.getInstance().onPlayerLeave(uuid);
        VoteManager.getInstance().removePlayer(uuid);
        ClassSelectManager.getInstance().removePlayerVote(uuid);
        PlayerMatchStatsManager.getInstance().onPlayerLeave(uuid);
        BastionManager.getInstance().unlockPlayerPosition(uuid);
        BastionManager.getInstance().clearWaiting(uuid);
    }

    public Vec3 getWaitingPosition() {
        return new Vec3(0.5, GameConfig.getWaitingY(), 0.5);
    }

    /** 强制玩家处于统一的高空等待点、旁观模式和失明状态。 */
    public void applyWaitingState(ServerPlayer player) {
        applyMatchHoldState(player, HoldAnchor.BATTLEFIELD_WAIT);
    }

    public void applyBattlefieldWaitingState(ServerPlayer player) {
        applyMatchHoldState(player, HoldAnchor.BATTLEFIELD_WAIT);
    }

    /**
     * 主世界主城：持久维度，不随战场卸载重置。
     * 强制玩家回到 overworld 出生点（维度或坐标不对都会传送）。
     * 合法解除 MatchHold：解锁 + 去失明；非管理员固定为冒险模式。
     */
    public void applyHubState(ServerPlayer player) {
        forcePlayerToHub(player);
    }

    /**
     * 非管理员位于主世界主城时强制冒险模式（不可自行切生存/创造）。
     * 管理员不强制锁定，但进主城时仍会先设为冒险（见 {@link #applyHubAdventureOnEnter}）。
     */
    public boolean shouldForceHubAdventure(ServerPlayer player) {
        return player != null
            && player.connection != null
            && !player.hasPermissions(2)
            && Level.OVERWORLD.equals(player.serverLevel().dimension());
    }

    /**
     * 进入主城时：所有人（含管理员）设为冒险模式。
     * 之后仅非管理员被 {@link #shouldForceHubAdventure} 持续锁定。
     */
    public void applyHubAdventureOnEnter(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        if (!Level.OVERWORLD.equals(player.serverLevel().dimension())) {
            return;
        }
        if (player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) {
            player.setGameMode(GameType.ADVENTURE);
        }
    }

    /**
     * 持续收口：非管理员在主城若不是冒险则改回冒险。
     * 管理员不在此强制，可自行切换创造等。
     */
    public void enforceHubAdventure(ServerPlayer player) {
        if (shouldForceHubAdventure(player)
                && player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) {
            player.setGameMode(GameType.ADVENTURE);
        }
    }

    /**
     * 已实际部署到战场的玩家：通过挖掘等级/可收获判定禁止挖方块，
     * 不再使用挖掘疲劳。真正拦截在 {@link #shouldRestrictBattlefieldMining} 驱动的事件里完成。
     * 此方法只清理旧版疲劳效果，便于从疲劳方案平滑迁移。
     */
    public void applyBattlefieldMiningRestriction(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        // 清除历史「战场挖掘疲劳」残留（旧版本施加的永久 DIG_SLOWDOWN）。
        player.removeEffect(MobEffects.DIG_SLOWDOWN);
    }

    /** @deprecated use {@link #applyBattlefieldMiningRestriction(ServerPlayer)} */
    @Deprecated
    public void applyBattlefieldMiningFatigue(ServerPlayer player) {
        applyBattlefieldMiningRestriction(player);
    }

    /**
     * 战场部署/战斗中的非创造、非旁观玩家：视为挖掘等级不足，无法开采任何方块。
     * 服务端权威；客户端用于 BreakSpeed 预判（避免裂纹动画）。
     */
    public boolean shouldRestrictBattlefieldMining(net.minecraft.world.entity.player.Player player) {
        if (player == null || !player.isAlive()) {
            return false;
        }
        if (player.isSpectator() || player.getAbilities().instabuild) {
            return false;
        }
        if (!isDeployOrBattlePhaseForMining(player)) {
            return false;
        }
        if (player.level() instanceof ServerLevel serverLevel) {
            return BattlefieldContext.isActiveBattlefield(serverLevel);
        }
        // 客户端：若已有 ACTIVE 配置则严格对维度；否则用「非主城」近似（避免裂纹误显）。
        if (BattlefieldContext.isActive()) {
            return BattlefieldContext.isActiveBattlefield(player.level().dimension());
        }
        return !Level.OVERWORLD.equals(player.level().dimension());
    }

    private boolean isDeployOrBattlePhaseForMining(net.minecraft.world.entity.player.Player player) {
        if (!player.level().isClientSide()) {
            return currentPhase == GamePhase.DEPLOYING || currentPhase == GamePhase.BATTLE;
        }
        try {
            Class<?> clientState = Class.forName("org.espetro.client.gui.ClientGameState");
            Object phase = clientState.getMethod("getCurrentPhase").invoke(null);
            if (phase == null) {
                return false;
            }
            String name = phase.toString();
            return "DEPLOYING".equals(name) || "BATTLE".equals(name);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 进服/退服/回城共用：强制进入主城维度出生点，不销毁或重载主城世界。
     */
    public void forcePlayerToHub(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        player.removeEffect(MobEffects.BLINDNESS);
        player.removeEffect(MobEffects.DIG_SLOWDOWN);
        BastionManager.getInstance().unlockPlayerPosition(player.getUUID());
        BastionManager.getInstance().clearWaiting(player.getUUID());
        // Natural regeneration is disabled globally by Espetro. Without an
        // explicit hub reset, damage saved in playerdata survives reconnects
        // and leaves players permanently below full health in the lobby.
        if (player.isAlive()) {
            player.setHealth(player.getMaxHealth());
            player.clearFire();
            player.setAirSupply(player.getMaxAirSupply());
        }
        ServerLevel hub = player.server.overworld();
        BlockPos spawn = hub.getSharedSpawnPos();
        double x = spawn.getX() + 0.5;
        double y = spawn.getY();
        double z = spawn.getZ() + 0.5;
        // 始终写回重生点，避免下次登录落在已卸载的战场维度。
        player.setRespawnPosition(Level.OVERWORLD, spawn, 0f, true, false);
        boolean wrongDimension = player.serverLevel() != hub;
        boolean farFromSpawn = player.distanceToSqr(x, y, z) > 2.0 * 2.0;
        if (wrongDimension || farFromSpawn) {
            player.teleportTo(hub, x, y, z, 0f, 0f);
        }
        // 回主城：所有人先进冒险；非管理员之后由 changeGameMode 事件持续锁定
        applyHubAdventureOnEnter(player);
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0f;
    }

    /** 等待部署点选择时使用；统一 MatchHold（旁观/失明/战场等待点锁）。 */
    public void applyDeploymentWaitingState(ServerPlayer player) {
        applyMatchHoldState(player, HoldAnchor.BATTLEFIELD_WAIT);
    }

    // ========== 战局中加入 ==========

    public boolean isMidGameJoiner(UUID uuid) {
        return midGameJoiners.contains(uuid);
    }

    public void removeMidGameJoiner(UUID uuid) {
        midGameJoiners.remove(uuid);
    }

    public void onMidGameJoin(ServerPlayer player) {
        midGameJoiners.add(player.getUUID());
        clearPlayerRoundAssignment(player);
        // 中途加入先在主城高空等待点选边。未选边之前必须保持旁观、失明和位置锁，
        // 不能通过关闭界面或长时间不选择来恢复移动。
        applyMatchHoldState(player, HoldAnchor.HUB_HIGH);

        // 只给该玩家同步阶段信息，避免全局广播
        org.espetro.network.NetworkManager.NET.send(
            net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
            new org.espetro.network.GamePhaseSyncPacket(currentPhase));
        NetworkManager.sendOpenFactionScreen(player);

        player.sendSystemMessage(Component.literal("§6========================================"));
        player.sendSystemMessage(Component.literal("§e⚡ 战场上需要增援！请选择你的阵营"));
        player.sendSystemMessage(Component.literal("§e按上方按钮选择 §c进攻方 §e或 §9防守方"));
        player.sendSystemMessage(Component.literal("§6========================================"));

    }

    public void onMidGameTeamSelected(ServerPlayer player, String team) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        ClassSelectManager selectManager = ClassSelectManager.getInstance();
        String factionId = "ATTACK".equals(team)
            ? selectManager.getFinalAttackClass()
            : selectManager.getFinalDefendClass();

        if (factionId == null) {
            factionId = team;
            Espetro.LOGGER.warn("战局中加入: {} 方编制未设置，使用默认值", team);
        }

        if ("ATTACK".equals(team)) {
            TeamManager.joinAttackTeam(server, player.getName().getString());
        } else {
            TeamManager.joinDefendTeam(server, player.getName().getString());
        }

        ClassCountManager.getInstance().setPlayerFaction(player.getUUID(), factionId);
        ClassCountManager.getInstance().setPlayerTeam(player.getUUID(), team);
        PlayerMatchStatsManager.getInstance().onTeamSelected(player, team);

        VoteManager voteManager = VoteManager.getInstance();
        if ("ATTACK".equals(team)) {
            voteManager.addAttackPlayer(player.getUUID());
        } else {
            voteManager.addDefendPlayer(player.getUUID());
        }

        teamSelectedPlayers.add(player.getUUID());

        // ===== 根据当前游戏阶段分别处理中途加入者的状态同步 =====
        switch (currentPhase) {

            case DEFEND_COMMANDER_VOTE, ATTACK_COMMANDER_VOTE -> {
                String votingTeam = currentPhase.getActiveTeam();
                int voteRemaining = voteManager.getRemainingSeconds();

                if (team.equals(votingTeam)) {
                    NetworkManager.sendCommanderVoteScreenToPlayer(player, team, voteRemaining);
                } else {
                    NetworkManager.sendCommanderVoteScreenToPlayer(player, team, 0);
                }
            }

            case DEFEND_FACTION_SELECT, ATTACK_FACTION_SELECT -> {
                String selectingTeam = currentPhase.getActiveTeam();
                int selectRemaining = selectManager.getRemainingSeconds();

                UUID cmdUuid = "ATTACK".equals(team) ? voteManager.getAttackCommander()
                    : voteManager.getDefendCommander();
                boolean isCmd = cmdUuid != null && cmdUuid.equals(player.getUUID());

                if (team.equals(selectingTeam)) {
                    NetworkManager.sendClassSelectScreen(player, team, isCmd, selectRemaining);
                } else {
                    NetworkManager.sendClassSelectScreen(player, team, false, 0);
                }
            }

            case DEPLOYING -> {
                // 部署阶段增援与开局玩家一致：先进入统一等待点，再选择部署点。
                player.removeAllEffects();
                prepareDeploySelection(player, team);

                // 发送统一部署主界面
                int remaining = getDeployTimeRemainingSeconds();
                NetworkManager.sendUnifiedDeployScreen(player, remaining);
                NetworkManager.sendWaitingStatus(player, "ATTACK".equals(team)
                    ? "§c等待进攻§e[" + remaining + "秒]"
                    : "§9部署防线§e[" + remaining + "秒]", true);
                player.sendSystemMessage(Component.literal(
                    "§a✅ 增援到达部署阶段！请在左侧面板选择职业和部署点"));

                Espetro.broadcastToAll("§e⚡ 增援到达！" + player.getName().getString()
                    + " 加入了" + ("ATTACK".equals(team) ? " §c进攻方" : " §9防守方")
                    + " §7(部署中)");
            }

            case BATTLE -> {
                // 对战阶段增援也必须先选择部署点。
                player.removeAllEffects();

                BastionManager bastionManager = BastionManager.getInstance();
                SpawnPointConfig.SpawnPoint spawnPoint = SpawnPointConfig.getSpawnPoint(team);
                ServerLevel overworld = BattlefieldContext.requireBattlefield(server);
                bastionManager.savePlayerDeployPoint(player,
                    new BlockPos((int) spawnPoint.x, (int) spawnPoint.y, (int) spawnPoint.z),
                    overworld);
                bastionManager.activatePlayerBastionSelection(player.getUUID());
                applyDeploymentWaitingState(player);

                TroopCountManager troopMgr = TroopCountManager.getInstance();
                NetworkManager.broadcastTroopCounts(
                    troopMgr.getAttackTroops(), troopMgr.getDefendTroops());

                String commanderName = "无";
                UUID commanderUuid = "ATTACK".equals(team)
                    ? voteManager.getAttackCommander()
                    : voteManager.getDefendCommander();
                if (commanderUuid != null) {
                    ServerPlayer commander = server.getPlayerList().getPlayer(commanderUuid);
                    if (commander != null) {
                        commanderName = commander.getName().getString();
                    }
                }

                player.sendSystemMessage(Component.literal("§a════════════════════════════════"));
                player.sendSystemMessage(Component.literal("§a你已作为增援加入"
                    + ("ATTACK".equals(team) ? "§c进攻方" : "§9防守方") + "§a！"));
                player.sendSystemMessage(Component.literal("§e编制: §f" + factionId));
                player.sendSystemMessage(Component.literal("§e指挥官: §f" + commanderName));
                player.sendSystemMessage(Component.literal("§a════════════════════════════════"));
                player.sendSystemMessage(Component.literal("§e⚠ 请先在部署面板选择部署点，再选择职业！"));

                NetworkManager.sendUnifiedDeployScreen(player, -1);

                Espetro.broadcastToAll("§e⚡ 增援到达！" + player.getName().getString()
                    + " 加入了" + ("ATTACK".equals(team) ? " §c进攻方" : " §9防守方"));
            }

            default -> {
                // 其他阶段保持旁观者状态
            }
        }
    }

    public void onMidGameDeployComplete(ServerPlayer player) {
        boolean wasMidGameJoiner = midGameJoiners.remove(player.getUUID());
        applyBattlefieldMiningRestriction(player);

        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());

        if (factionId != null) {
            // 不再发送旧的 ClassSelectionScreen，由 UnifiedDeployScreen 统一处理
            if (wasMidGameJoiner) {
                player.sendSystemMessage(Component.literal("§a✅ 部署完成！"));
            } else {
                player.sendSystemMessage(Component.literal("§a✅ 已复活！"));
            }
        }
    }

    /**
     * End a battle from troop depletion, command, API or KubeJS. Winner is one
     * of ATTACK / DEFEND / DRAW. The actual world detach happens after the
     * configured result-screen delay.
     */
    public boolean endRound(String winner) {
        if (currentPhase != GamePhase.BATTLE) {
            return false;
        }
        String normalized = winner == null ? "" : winner.trim().toUpperCase(Locale.ROOT);
        if (!"ATTACK".equals(normalized) && !"DEFEND".equals(normalized)
            && !"DRAW".equals(normalized)) {
            return false;
        }
        pendingRoundWinner = normalized;
        BattlefieldContext.setLastRoundWinner(normalized);
        roundEndTickCounter = 0;
        removeAttackWaitingBarrier();
        setPhase(GamePhase.ROUND_END);
        NetworkManager.broadcastRoundEnd(normalized, GameConfig.getRoundEndSeconds());
        String result = switch (normalized) {
            case "ATTACK" -> "§c进攻方胜利";
            case "DEFEND" -> "§9防守方胜利";
            default -> "§e平局";
        };
        Espetro.broadcastToAll("§6===== " + result + " §6=====");
        return true;
    }

    private void beginCleanup() {
        if (currentPhase != GamePhase.ROUND_END) {
            return;
        }
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return;
        }
        setPhase(GamePhase.CLEANUP);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            applyHubState(player);
            ClassEquipment.clearEquipment(player);
            TeamManager.removeFromAllTeams(
                server.getScoreboard(), player.getName().getString());
        }

        ActiveMapConfig completedMap = pendingMap != null ? pendingMap : BattlefieldContext.getOrNull();
        clearRoundRuntime(false); // per-round scoreboard survives until next prestart
        BattlefieldWorldManager.getInstance().cleanupBattlefield(server, completedMap, cleanupResult -> {
            pendingMap = null;
            pendingRoundWinner = null;
            setPhase(GamePhase.LOBBY);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                applyHubState(player);
                NetworkManager.sendOpenHubScreen(player, server.getPlayerCount(),
                    "等待管理员开始下一局");
            }
            broadcastHubStatus();
            if (!cleanupResult.success()) {
                Espetro.broadcastToAll(
                    "§c[Espetro] 战场存档副本删除失败，下次加载将重试："
                        + cleanupResult.error());
            }
        });
    }

    private void clearPlayerRoundAssignment(ServerPlayer player) {
        if (player == null) return;
        String squadTeam = SquadManager.getInstance().removePlayer(player.getUUID());
        ClassCountManager.getInstance().removePlayer(player);
        ClassEquipment.clearEquipment(player);
        TeamManager.removeFromAllTeams(player.server.getScoreboard(), player.getName().getString());
        waitingForTeam.remove(player.getUUID());
        teamSelectedPlayers.remove(player.getUUID());
        deployClassSelected.remove(player.getUUID());
        if (squadTeam != null) {
            NetworkManager.syncSquadsToTeam(squadTeam);
        }
    }

    private void broadcastHubStatus() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        int count = server.getPlayerCount();
        lastHubBroadcastPlayerCount = count;
        lastHubBroadcastTick = deployTickCounter;
        String text = "§6主城 §7| §e在线人数: §f" + count
            + " §7| §e等待管理员开始下一局";
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel() == server.overworld()) {
                NetworkManager.sendWaitingStatus(player, text, true);
            }
        }
    }

    /** 人数变化立即推；否则至少间隔 5 秒。 */
    private void broadcastHubStatusThrottled() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        int count = server.getPlayerCount();
        boolean countChanged = count != lastHubBroadcastPlayerCount;
        boolean intervalElapsed = deployTickCounter - lastHubBroadcastTick >= 5 * TICKS_PER_SECOND;
        if (countChanged || intervalElapsed || lastHubBroadcastTick == Integer.MIN_VALUE) {
            broadcastHubStatus();
        }
    }

    /**
     * 标记玩家在部署阶段已选择职业
     */
    public void markDeployClassSelected(UUID uuid) {
        deployClassSelected.add(uuid);
    }

    public boolean isDeployClassSelected(UUID uuid) {
        return deployClassSelected.contains(uuid);
    }

    // ========== 兼容方法 ==========

    public int getTeamSelectedCount() {
        return teamSelectedPlayers.size();
    }

    public int getWaitingForTeamCount() {
        return waitingForTeam.size();
    }

    public boolean isGameStarted() {
        return currentPhase == GamePhase.BATTLE;
    }

    public void forceStartGame() {
        MinecraftServer server = Espetro.getServer();
        if (server != null && currentPhase.isLobbyLike()) {
            prestart(server);
        }
    }

    public Map<String, SpawnPointConfig.SpawnPoint> getAllSpawnPoints() {
        return SpawnPointConfig.getAllSpawnPoints();
    }

    public void setTeamSpawnPoint(String team, double x, double y, double z, float yaw) {
        SpawnPointConfig.setSpawnPoint(team, x, y, z, yaw);

        BlockPos newPos = new BlockPos((int) x, (int) y, (int) z);
        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            ServerLevel overworld = BattlefieldContext.requireBattlefield(server);
            org.espetro.bastion.BastionManager bastionMgr = org.espetro.bastion.BastionManager.getInstance();

            for (UUID uuid : teamSelectedPlayers) {
                String playerTeam = ClassCountManager.getInstance().getPlayerTeam(uuid);
                if (playerTeam == null) {
                    playerTeam = getTeamFromFactionStatic(ClassCountManager.getInstance().getPlayerFaction(uuid));
                }
                if (team.equals(playerTeam)) {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        bastionMgr.savePlayerDeployPoint(player, newPos, overworld);
                    }
                }
            }
        }
    }

    /** @deprecated Readiness no longer exists; this is the selected-side count. */
    @Deprecated
    public int getReadyCount() {
        return teamSelectedPlayers.size();
    }

    public int getWaitingCount() {
        return waitingForTeam.size();
    }
}
