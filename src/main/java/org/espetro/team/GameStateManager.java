package org.espetro.team;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.espetro.Espetro;
import org.espetro.bastion.BastionManager;
import org.espetro.config.GameConfig;
import org.espetro.network.NetworkManager;

import java.util.*;

/**
 * 游戏状态管理器
 * 管理游戏各阶段流程
 *
 * 阶段流转（按流程图）：
 * WAITING → DEFEND_COMMANDER_VOTE(20s) → ATTACK_COMMANDER_VOTE(20s)
 * → DEFEND_FACTION_SELECT(30s) → ATTACK_FACTION_SELECT(30s)
 * → DEPLOYING(180s, 攻方失明+禁止移动) → BATTLE
 */
public class GameStateManager {

    private static GameStateManager INSTANCE;

    // 当前游戏阶段
    private GamePhase currentPhase = GamePhase.WAITING_FOR_PLAYERS;

    // 部署阶段计时器
    private int deployTickCounter = 0;
    private static final int TICKS_PER_SECOND = 20;

    // 攻方部署阶段固定位置（用于禁止移动）
    private final Map<UUID, BlockPos> attackDeployPositions = new HashMap<>();

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
        String message = "§6⏳ 等待玩家集结中 §e[" + teamSelectedPlayers.size() + "/" + GameConfig.getRequiredPlayers() + "]";
        for (UUID uuid : teamSelectedPlayers) {
            MinecraftServer server = Espetro.getServer();
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) {
                    NetworkManager.sendWaitingStatus(player, message, true);
                }
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
        attackDeployPositions.clear();
        deployClassSelected.clear();

        // 编制选择最终处理
        ClassSelectManager.getInstance().finalizeSelection();

        // 传送所有玩家到复活点
        teleportAllToSpawnPoints();

        int deployTimeout = GameConfig.getDeployTimeoutSeconds();
        Espetro.broadcastToTeam("ATTACK", "§6===== 攻方已传送到部署点！请等待进攻指令！[" + deployTimeout + "秒] =====");
        Espetro.broadcastToTeam("DEFEND", "§6===== 守方已传送到部署点！部署防线！[" + deployTimeout + "秒] =====");

        // 广播职业选择界面给所有玩家（部署阶段可选职业）
        broadcastClassSelectionForDeploy();
    }

    /**
     * 部署阶段开始时广播职业选择
     */
    private void broadcastClassSelectionForDeploy() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        ClassSelectManager selectManager = ClassSelectManager.getInstance();
        String attackFaction = selectManager.getFinalAttackClass();
        String defendFaction = selectManager.getFinalDefendClass();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String team = ClassCountManager.getInstance().getPlayerTeam(player.getUUID());
            if (team == null) continue;

            String factionId = "ATTACK".equals(team) ? attackFaction : defendFaction;
            if (factionId != null) {
                NetworkManager.sendClassSelectionScreen(player, factionId);
            }
        }

        Espetro.broadcastToAll("§e请选择你的职业！按 §aJ键 §e打开职业选择界面");
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

                // 设置生存模式
                player.setGameMode(GameType.SURVIVAL);

                // 传送到复活点
                teleportToTeamSpawn(player, team);

                // 攻方设置失明+记录固定位置，守方移除失明
                if ("ATTACK".equals(team)) {
                    int deployTimeout = GameConfig.getDeployTimeoutSeconds();
                    player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, deployTimeout * TICKS_PER_SECOND, 0, false, false, false));
                    // 记录攻方固定位置（禁止移动）
                    attackDeployPositions.put(uuid, player.blockPosition());
                } else {
                    player.removeEffect(MobEffects.BLINDNESS);
                }
            }
        }
    }

    /**
     * 部署阶段Tick处理
     */
    public void onDeployTick() {
        int deployTimeout = GameConfig.getDeployTimeoutSeconds();
        int secondsRemaining = deployTimeout - (deployTickCounter / TICKS_PER_SECOND);

        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            // 攻方禁止移动：每tick检查攻方位置，如果移动了传送回去
            for (UUID uuid : attackDeployPositions.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null && player.isAlive()) {
                    BlockPos fixedPos = attackDeployPositions.get(uuid);
                    BlockPos currentPos = player.blockPosition();
                    // 如果移动超过0.5格，传送回固定位置
                    if (!currentPos.closerThan(fixedPos, 0.5)) {
                        player.teleportTo(player.serverLevel(), fixedPos.getX() + 0.5, fixedPos.getY(), fixedPos.getZ() + 0.5,
                            player.getYRot(), player.getXRot());
                    }
                }
            }
        }

        // 每秒更新一次
        if (deployTickCounter % TICKS_PER_SECOND == 0) {
            if (secondsRemaining > 0) {
                Espetro.broadcastToTeam("ATTACK", "§c等待进攻指令！[" + secondsRemaining + "秒]");
                Espetro.broadcastToTeam("DEFEND", "§9部署防线！[" + secondsRemaining + "秒]");
            }

            // 部署警告时间提示
            int deployWarning = GameConfig.getDeployWarningSeconds();
            if (secondsRemaining == deployWarning) {
                Espetro.broadcastToAll("§e⚠ 战斗将在" + deployWarning + "秒后开始！");
            }
        }

        // 部署阶段结束 -> 对战开始
        if (deployTickCounter >= deployTimeout * TICKS_PER_SECOND) {
            startBattle();
        }
    }

    /**
     * 开始对战
     */
    private void startBattle() {
        setPhase(GamePhase.BATTLE);

        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        // 移除攻方失明效果和禁止移动
        attackDeployPositions.clear();
        for (UUID uuid : teamSelectedPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.removeEffect(MobEffects.BLINDNESS);
            }
        }

        // 初始化兵力统计
        TroopCountManager.getInstance().initializeTroops();
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
                    startDeploying();
                }
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
        attackDeployPositions.clear();

        VoteManager.getInstance().reset();
        ClassSelectManager.getInstance().reset();

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
        attackDeployPositions.remove(uuid);
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
        player.sendSystemMessage(Component.literal("§e⚠ 请先在下方消息中选择部署点，再选择职业！"));

        org.espetro.network.BastionSelectionPacket.sendBastionSelectionMessage(player);

        Espetro.broadcastToAll("§e⚡ 增援到达！" + player.getName().getString() + " 加入了"
            + ("ATTACK".equals(team) ? " §c进攻方" : " §9防守方"));
    }

    public void onMidGameDeployComplete(ServerPlayer player) {
        boolean wasMidGameJoiner = midGameJoiners.remove(player.getUUID());

        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());

        if (factionId != null) {
            NetworkManager.sendClassSelectionScreen(player, factionId);
            if (wasMidGameJoiner) {
                player.sendSystemMessage(Component.literal("§a✅ 部署完成！请按 §eJ键 §a选择你的职业"));
            } else {
                player.sendSystemMessage(Component.literal("§a✅ 已复活！请按 §eJ键 §a重新选择你的职业"));
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
