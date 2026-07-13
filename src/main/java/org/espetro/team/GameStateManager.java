package org.espetro.team;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.espetro.Espetro;
import org.espetro.bastion.BastionManager;
import org.espetro.config.GameConfig;
import org.espetro.network.NetworkManager;
import org.espetro.vehicle.VehicleManager;

import java.util.*;

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
    private GamePhase currentPhase = GamePhase.WAITING_FOR_PLAYERS;

    // 部署阶段计时器
    private int deployTickCounter = 0;
    // 双方编制揭示阶段计时器
    private int factionRevealTickCounter = 0;
    private static final int TICKS_PER_SECOND = 20;
    private static final int FACTION_REVEAL_SECONDS = 5;
    private static final int ATTACK_WAITING_BARRIER_SIDE = 200;
    private static final int ATTACK_WAITING_BARRIER_HEIGHT = 20;

    // 攻方等待防守部署时临时放置的屏障，记录原方块以便开战后恢复
    private final Map<BlockPos, BlockState> attackWaitingBarrierBlocks = new HashMap<>();

    // 等待选择队伍的玩家
    private final Set<UUID> waitingForTeam = new HashSet<>();
    // 已选择队伍的玩家
    private final Set<UUID> teamSelectedPlayers = new HashSet<>();
    // 战局中加入的玩家（部署点选择完成前）
    private final Set<UUID> midGameJoiners = new HashSet<>();
    // 已在部署阶段选择过职业的玩家（防止重复选择）
    private final Set<UUID> deployClassSelected = new HashSet<>();

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
        this.currentPhase = phase;
        Espetro.LOGGER.info("游戏阶段切换: {}", phase.getDisplayName());
        NetworkManager.broadcastGamePhase(phase);
    }

    // ========== 队伍选择阶段 ==========

    public void onTeamSelected(ServerPlayer player, String factionId) {
        waitingForTeam.remove(player.getUUID());
        teamSelectedPlayers.add(player.getUUID());

        // 记录玩家阵营
        ClassCountManager.getInstance().setPlayerFaction(player.getUUID(), factionId);
        // 记录玩家的原始队伍（ATTACK/DEFEND）
        String resolvedTeam = getTeamFromFactionStatic(factionId);
        ClassCountManager.getInstance().setPlayerTeam(player.getUUID(), resolvedTeam);

        Espetro.LOGGER.info("玩家 {} 选择了队伍 {}, 已选人数: {}/{}",
            player.getName().getString(), factionId, teamSelectedPlayers.size(), GameConfig.getRequiredPlayers());

        updateWaitingMessage(player);
        checkStartCommanderVote();
    }

    private void updateWaitingMessage(ServerPlayer player) {
        String message = "§6⏳ 等待玩家集结中 §e[" + teamSelectedPlayers.size() + "/" + GameConfig.getRequiredPlayers() + "]";
        NetworkManager.sendWaitingStatus(player, message, true);
    }

    private void broadcastWaitingMessages() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return;
        }

        String message = "§6⏳ 等待玩家集结中 §e[" + teamSelectedPlayers.size() + "/" + GameConfig.getRequiredPlayers() + "]";
        for (UUID uuid : teamSelectedPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                NetworkManager.sendWaitingStatus(player, message, true);
            }
        }
    }

    private void checkStartCommanderVote() {
        if (teamSelectedPlayers.size() >= GameConfig.getRequiredPlayers()) {
            startDefendCommanderVote();
        }
    }

    public void forceStartCommanderVote() {
        if (teamSelectedPlayers.size() == 0) {
            Espetro.LOGGER.info("没有玩家选择队伍！");
            return;
        }
        startDefendCommanderVote();
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

        // 传送所有玩家到复活点
        teleportAllToSpawnPoints();
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
            FACTION_REVEAL_SECONDS
        );

        Espetro.LOGGER.info("双方编制揭示开始，持续{}秒", FACTION_REVEAL_SECONDS);
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

        ServerLevel level = server.overworld();
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

    /**
     * 传送所有玩家到各自队伍复活点
     */
    private void teleportAllToSpawnPoints() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        ClassCountManager countManager = ClassCountManager.getInstance();

        for (UUID uuid : teamSelectedPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                String team = countManager.getPlayerTeam(uuid);
                if (team == null) {
                    team = getTeamFromFactionStatic(countManager.getPlayerFaction(uuid));
                }

                if (currentPhase == GamePhase.DEPLOYING && "DEFEND".equals(team)) {
                    prepareDeploySelection(player, team);
                } else {
                    player.setGameMode(GameType.SURVIVAL);
                    teleportToTeamSpawn(player, team);
                    // 准备阶段双方都允许正常视野和移动；攻方由部署点屏障限制活动范围
                    player.removeEffect(MobEffects.BLINDNESS);
                }
            }
        }
    }

    private void saveTeamSpawnAsDeployPoint(ServerPlayer player, String team) {
        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
        ServerLevel overworld = player.server.overworld();
        BlockPos deployPos = new BlockPos((int) spawn.x, (int) spawn.y, (int) spawn.z);
        BastionManager.getInstance().savePlayerDeployPoint(player, deployPos, overworld);
    }

    private void prepareDeploySelection(ServerPlayer player, String team) {
        saveTeamSpawnAsDeployPoint(player, team);
        applyWaitingState(player);
        BastionManager.getInstance().activatePlayerBastionSelection(player.getUUID());
        BastionManager.getInstance().lockPlayerPosition(player.getUUID(), player.position());
        if ("DEFEND".equals(team)) {
            OutpostManager.getInstance().prepareDeployTargets(player.server.overworld());
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

        ServerLevel level = server.overworld();
        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint("ATTACK");
        BlockPos center = new BlockPos((int) Math.floor(spawn.x), (int) Math.floor(spawn.y), (int) Math.floor(spawn.z));

        int half = ATTACK_WAITING_BARRIER_SIDE / 2;
        int minX = center.getX() - half;
        int maxX = minX + ATTACK_WAITING_BARRIER_SIDE - 1;
        int minZ = center.getZ() - half;
        int maxZ = minZ + ATTACK_WAITING_BARRIER_SIDE - 1;
        int baseY = center.getY();
        int roofY = baseY + ATTACK_WAITING_BARRIER_HEIGHT;

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

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setTemporaryBarrier(level, new BlockPos(x, roofY, z));
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
        level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 3);
    }

    private void removeAttackWaitingBarrier() {
        if (attackWaitingBarrierBlocks.isEmpty()) return;

        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            attackWaitingBarrierBlocks.clear();
            return;
        }

        ServerLevel level = server.overworld();
        int restored = 0;
        for (Map.Entry<BlockPos, BlockState> entry : attackWaitingBarrierBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            if (level.getBlockState(pos).is(Blocks.BARRIER)) {
                level.setBlock(pos, entry.getValue(), 3);
                restored++;
            }
        }
        attackWaitingBarrierBlocks.clear();
        Espetro.LOGGER.info("已移除攻方等待屏障，恢复{}个方块", restored);
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

        // 移除准备阶段残留状态
        BastionManager bastionManager = BastionManager.getInstance();
        for (UUID uuid : teamSelectedPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                if (bastionManager.isWaitingForBastion(player.getUUID())) {
                    if (bastionManager.isDeathWaiting(player.getUUID())) {
                        player.setGameMode(GameType.SPECTATOR);
                        player.addEffect(new MobEffectInstance(
                            MobEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false));
                        if (bastionManager.getPlayerLockPosition(player.getUUID()) == null) {
                            bastionManager.lockPlayerPosition(player.getUUID(), player.position());
                        }
                        NetworkManager.sendUnifiedDeployScreen(player, -1);
                    } else {
                        bastionManager.respawnAtDeployPoint(server.overworld(), player);
                        player.removeEffect(MobEffects.BLINDNESS);
                    }
                } else {
                    player.removeEffect(MobEffects.BLINDNESS);
                }
            }
        }

        // 初始化兵力统计
        TroopCountManager.getInstance().initializeTroops();

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
        if (factionRevealTickCounter >= FACTION_REVEAL_SECONDS * TICKS_PER_SECOND) {
            startDeploying();
        }
    }

    // ========== 服务器Tick ==========

    public void onServerTick() {
        switch (currentPhase) {
            case WAITING_FOR_PLAYERS:
                if (!teamSelectedPlayers.isEmpty() && deployTickCounter % 20 == 0) {
                    broadcastWaitingMessages();
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
            default:
                break;
        }
        deployTickCounter++;
    }

    // ========== 工具方法 ==========

    private void teleportToTeamSpawn(ServerPlayer player, String team) {
        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
        ServerLevel overworld = player.server.overworld();
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
        currentPhase = GamePhase.WAITING_FOR_PLAYERS;
        waitingForTeam.clear();
        teamSelectedPlayers.clear();
        midGameJoiners.clear();
        deployClassSelected.clear();
        deployTickCounter = 0;
        factionRevealTickCounter = 0;
        removeAttackWaitingBarrier();

        VoteManager.getInstance().reset();
        ClassSelectManager.getInstance().reset();
        SquadManager.getInstance().reset();
        TeamPackManager.getInstance().reset();
        OutpostManager.getInstance().reset();
        CommanderSkillManager.getInstance().reset();

        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                waitingForTeam.add(player.getUUID());
                applyWaitingState(player);
                NetworkManager.sendOpenFactionScreen(player);
            }
        }

        Espetro.LOGGER.info("游戏状态已重置");
    }

    public void onPlayerJoin(ServerPlayer player) {
        waitingForTeam.add(player.getUUID());
        applyWaitingState(player);
        NetworkManager.sendOpenFactionScreen(player);

        player.sendSystemMessage(Component.literal("§6========================================"));
        player.sendSystemMessage(Component.literal("§e请选择你的阵营！按 §aK键 §e打开阵营选择界面"));
        player.sendSystemMessage(Component.literal("§e攻击方 §7或 §9防守方"));
        player.sendSystemMessage(Component.literal("§6========================================"));
    }

    public void onPlayerLeave(UUID uuid) {
        waitingForTeam.remove(uuid);
        teamSelectedPlayers.remove(uuid);
        midGameJoiners.remove(uuid);
        deployClassSelected.remove(uuid);
    }

    public void applyWaitingState(ServerPlayer player) {
        player.setGameMode(GameType.SPECTATOR);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false));
        ServerLevel overworld = player.server.overworld();
        player.teleportTo(overworld, 0.5, GameConfig.getWaitingY(), 0.5, 0f, 0f);
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
        applyWaitingState(player);

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
                // 部署阶段：防守方先选部署点，攻方保持原逻辑。
                player.removeAllEffects();
                if ("DEFEND".equals(team)) {
                    prepareDeploySelection(player, team);
                } else {
                    player.setGameMode(GameType.SURVIVAL);
                    teleportToTeamSpawn(player, team);
                }

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
                // 对战阶段：完整增援流程（原逻辑不变）
                player.removeAllEffects();
                player.setGameMode(GameType.SURVIVAL);

                teleportToTeamSpawn(player, team);

                BastionManager bastionManager = BastionManager.getInstance();
                SpawnPointConfig.SpawnPoint spawnPoint = SpawnPointConfig.getSpawnPoint(team);
                ServerLevel overworld = server.overworld();
                bastionManager.savePlayerDeployPoint(player,
                    new BlockPos((int) spawnPoint.x, (int) spawnPoint.y, (int) spawnPoint.z),
                    overworld);
                bastionManager.activatePlayerBastionSelection(player.getUUID());

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
        if (currentPhase == GamePhase.WAITING_FOR_PLAYERS) {
            forceStartCommanderVote();
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
            ServerLevel overworld = server.overworld();
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

    public int getReadyCount() {
        return teamSelectedPlayers.size();
    }

    public int getWaitingCount() {
        return waitingForTeam.size();
    }
}