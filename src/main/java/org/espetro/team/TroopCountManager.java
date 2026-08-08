package org.espetro.team;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.mapconfig.GameConfigBridge;
import org.espetro.stats.PlayerMatchStatsManager;

/**
 * 兵力统计管理器
 * 使用记分板追踪双方兵力，阵亡时扣除相应职业的兵力值
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TroopCountManager {

    public static final String SCOREBOARD_OBJECTIVE = "troop_count";
    public static final String ATTACK_TROOPS_NAME = "attack_troops";
    public static final String DEFEND_TROOPS_NAME = "defend_troops";

    private static TroopCountManager INSTANCE;

    private TroopCountManager() {
        INSTANCE = this;
    }

    public static TroopCountManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new TroopCountManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new TroopCountManager();
    }

    /**
     * 获取记分板目标（不存在则创建）
     */
    private Objective getOrCreateObjective(Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(
                SCOREBOARD_OBJECTIVE,
                ObjectiveCriteria.DUMMY,
                Component.literal("§6双方兵力"),
                ObjectiveCriteria.RenderType.INTEGER
            );
        }
        return objective;
    }

    /**
     * 获取记分板
     */
    private Scoreboard getScoreboard() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return null;
        }
        return server.getScoreboard();
    }

    /**
     * 获取攻方兵力
     */
    public int getAttackTroops() {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return 0;
        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (objective == null) return 0;
        Score score = scoreboard.getOrCreatePlayerScore(ATTACK_TROOPS_NAME, objective);
        return score.getScore();
    }

    /**
     * 获取守方兵力
     */
    public int getDefendTroops() {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return 0;
        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (objective == null) return 0;
        Score score = scoreboard.getOrCreatePlayerScore(DEFEND_TROOPS_NAME, objective);
        return score.getScore();
    }

    /**
     * 设置攻方兵力
     */
    public void setAttackTroops(int value) {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;
        Objective objective = getOrCreateObjective(scoreboard);
        Score score = scoreboard.getOrCreatePlayerScore(ATTACK_TROOPS_NAME, objective);
        score.setScore(Math.max(0, value));
        Espetro.LOGGER.info("攻方兵力设置为: {}", value);
        syncToClients();
    }

    /**
     * 设置守方兵力
     */
    public void setDefendTroops(int value) {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;
        Objective objective = getOrCreateObjective(scoreboard);
        Score score = scoreboard.getOrCreatePlayerScore(DEFEND_TROOPS_NAME, objective);
        score.setScore(Math.max(0, value));
        Espetro.LOGGER.info("守方兵力设置为: {}", value);
        syncToClients();
    }

    /**
     * 增加/减少攻方兵力
     * <p>
     * ESPoints 通过此方法直接发放批次奖励（绕过 /espetro 命令系统），
     * 此处用栈追踪检测调用方是否为 CapturePointManager，若是则覆写为配置值。
     * 栈追踪使用字符串匹配，不受 Forge SecureJar 类加载器隔离影响。
     * <p>
     * 若检测到 ESPoints 批次完成，同时用原版 /title /subtitle /playsound
     * 指令向双方玩家展示据点占领提示。
     */
    public void modifyAttackTroops(int delta) {
        int step = delta;
        boolean fromEsPoints = false;
        int configured = GameConfigBridge.getAttackBatchCompletionReinforcement();
        if (configured > 0 && delta > 0) {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().contains("CapturePointManager")) {
                    Espetro.LOGGER.info("[TroopCountManager] 拦截 ESPoints 批次奖励: {} → {}", delta, configured);
                    step = configured;
                    fromEsPoints = true;
                    break;
                }
            }
        } else if (delta > 0) {
            // 无配置覆写时也检测 ESPoints 调用，用于播放提示
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().contains("CapturePointManager")) {
                    fromEsPoints = true;
                    break;
                }
            }
        }
        int current = getAttackTroops();
        setAttackTroops(current + step);

        // ESPoints 批次完成 → 原版指令播放据点占领提示
        if (fromEsPoints) {
            MinecraftServer server = Espetro.getServer();
            if (server != null) {
                net.minecraft.commands.CommandSourceStack cmdSrc = server.createCommandSourceStack()
                    .withSuppressedOutput();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    String team = Espetro.getPlayerTeam(player);
                    if (team == null) continue;
                    String name = player.getName().getString();
                    if ("ATTACK".equals(team)) {
                        server.getCommands().performPrefixedCommand(cmdSrc,
                            "title " + name + " title {\"text\":\"据点已占领\",\"color\":\"green\",\"bold\":true}");
                        server.getCommands().performPrefixedCommand(cmdSrc,
                            "playsound minecraft:entity.player.levelup master " + name + " ~ ~ ~ 1.0 1.0");
                    } else if ("DEFEND".equals(team)) {
                        server.getCommands().performPrefixedCommand(cmdSrc,
                            "title " + name + " title {\"text\":\"据点失守\",\"color\":\"red\",\"bold\":true}");
                        server.getCommands().performPrefixedCommand(cmdSrc,
                            "title " + name + " subtitle {\"text\":\"进攻方已攻占本批次所有据点\",\"color\":\"gray\"}");
                        server.getCommands().performPrefixedCommand(cmdSrc,
                            "playsound minecraft:block.beacon.deactivate master " + name + " ~ ~ ~ 1.0 1.0");
                    }
                }
            }
        }
    }

    /**
     * 增加/减少守方兵力
     */
    public void modifyDefendTroops(int delta) {
        int current = getDefendTroops();
        setDefendTroops(current + delta);
    }

    /**
     * 同步兵力到所有客户端
     */
    private void syncToClients() {
        int attack = getAttackTroops();
        int defend = getDefendTroops();
        org.espetro.network.NetworkManager.broadcastTroopCounts(attack, defend);
    }

    /**
     * 初始化兵力值
     */
    public void initializeTroops() {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;

        int initialAttack = GameConfig.getInitialAttackTroops();
        int initialDefend = GameConfig.getInitialDefendTroops();

        Objective objective = getOrCreateObjective(scoreboard);

        Score attackScore = scoreboard.getOrCreatePlayerScore(ATTACK_TROOPS_NAME, objective);
        attackScore.setScore(initialAttack);

        Score defendScore = scoreboard.getOrCreatePlayerScore(DEFEND_TROOPS_NAME, objective);
        defendScore.setScore(initialDefend);

        Espetro.LOGGER.info("兵力统计已初始化: 攻方 {} | 守方 {}", initialAttack, initialDefend);
        Espetro.broadcastToAll("§6========================================");
        Espetro.broadcastToAll("§e⚔ 战斗开始！进攻方初始兵力: §c" + initialAttack + " §7| §9防守方初始兵力: " + initialDefend + " §e⚔");
        Espetro.broadcastToAll("§6========================================");

        // 同步到客户端
        syncToClients();
    }

    /**
     * 重置兵力值
     */
    public void resetTroops() {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;

        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (objective == null) return;

        // 移除记分板
        scoreboard.removeObjective(objective);

        Espetro.LOGGER.info("兵力统计已重置");
        // 清局后同步客户端隐藏/清零兵力 HUD，避免结算残留
        org.espetro.network.NetworkManager.broadcastTroopCounts(0, 0);
    }

    /**
     * 判断是否为指挥官（role 为 "指挥" 的职业）
     */
    private boolean isCommanderClass(String classId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        if (kit == null) return false;
        return "指挥".equals(kit.role);
    }

    /**
     * 获取职业的兵力消耗值（从配置读取，默认1）
     */
    private int getTroopValueForClass(String classId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        if (kit == null) {
            Espetro.LOGGER.warn("未找到职业配置: {}, 使用默认值1", classId);
            return 1;
        }
        Espetro.LOGGER.debug("职业 {} 的兵力消耗值: {}", classId, kit.troopValue);
        return kit.troopValue;
    }

    /**
     * 处理玩家阵亡
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // 只在战斗阶段扣除兵力
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE) return;
        if (!BattlefieldContext.isActiveBattlefield(player.serverLevel())) return;

        // Scoreboard rules: every battle death counts; only a direct enemy
        // ServerPlayer kill grants a kill.
        PlayerMatchStatsManager.getInstance().onPlayerDeath(player, event.getSource());

        // 获取玩家当前职业（未选择则使用默认值）
        String classId = ClassCountManager.getInstance().getPlayerClass(player.getUUID());

        // 获取玩家的队伍
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return;

        // 计算兵力消耗（未选择职业默认扣1）
        int troopValue = (classId != null) ? getInstance().getTroopValueForClass(classId) : 1;

        // 判断是否为指挥官
        boolean isCommander = getInstance().isCommanderClass(classId);

        // 指挥官额外扣除兵力（从配置读取）
        int commandPenalty = isCommander ? GameConfig.getCommanderDeathPenalty() : 0;

        // 扣除相应队伍兵力
        TroopCountManager manager = getInstance();
        if ("ATTACK".equals(team)) {
            manager.modifyAttackTroops(-(troopValue + commandPenalty));
            String msg = "§c☠ 攻方 [" + player.getName().getString() + "] 阵亡！- " + troopValue + " 兵力";
            if (isCommander) msg += " §c(指挥官额外 -" + commandPenalty + ")";
            Espetro.broadcastToTeam(team, msg);
            Espetro.LOGGER.info("攻方 {} 阵亡(指挥官={})，扣除 {}{} 兵力，剩余: {}", player.getName().getString(), isCommander, troopValue, commandPenalty > 0 ? "+" + commandPenalty : "", manager.getAttackTroops());
        } else {
            manager.modifyDefendTroops(-(troopValue + commandPenalty));
            String msg = "§9☠ 守方 [" + player.getName().getString() + "] 阵亡！- " + troopValue + " 兵力";
            if (isCommander) msg += " §9(指挥官额外 -" + commandPenalty + ")";
            Espetro.broadcastToTeam(team, msg);
            Espetro.LOGGER.info("守方 {} 阵亡(指挥官={})，扣除 {}{} 兵力，剩余: {}", player.getName().getString(), isCommander, troopValue, commandPenalty > 0 ? "+" + commandPenalty : "", manager.getDefendTroops());
        }

        // 检查胜负条件
        manager.checkVictoryCondition();
    }

    /**
     * 检查胜负条件
     */
    public void checkVictoryCondition() {
        int attackTroops = getAttackTroops();
        int defendTroops = getDefendTroops();

        if (attackTroops <= 0) {
            Espetro.LOGGER.info("===== 防守方胜利 =====");
            GameStateManager.getInstance().endRound("DEFEND");
            return;
        }

        if (defendTroops <= 0) {
            Espetro.LOGGER.info("===== 进攻方胜利 =====");
            GameStateManager.getInstance().endRound("ATTACK");
            return;
        }
    }
}
