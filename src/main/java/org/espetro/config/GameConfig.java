package org.espetro.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.espetro.Espetro;
import org.espetro.data.EspetroDataResources;

/**
 * 游戏参数运行时态。
 * <p>权威值来自活动地图 {@code EsWorld/<map>/EsConfig/game.json}，
 * 经 {@link #applySnapshot} 在战场激活时写入。启动阶段仅保留代码默认值，
 * 不再从 datapack {@code data/espetro/config/game.json} 读取。
 */
public class GameConfig {

    private static final Gson GSON = new Gson();
    private static final String CONFIG_PATH = "espetro/config/game.json";

    // ========== 游戏参数 (带默认值) ==========
    private static int requiredPlayers = 20;
    private static int deployTimeoutSeconds = 240;
    private static int deployWarningSeconds = 30;
    private static int defendCommanderVoteSeconds = 20;
    private static int attackCommanderVoteSeconds = 20;
    private static int defendFactionSelectSeconds = 30;
    private static int attackFactionSelectSeconds = 30;
    private static int factionPoolSize = 6;
    private static int respawnInvincibilityTicks = 60;
    private static int classSwitchCooldownSeconds = 60;
    private static double teammateNameTagDistance = 10.0;
    private static double waitingY = 200.0;

    // ========== 兵力参数 ==========
    private static int initialAttackTroops = 280;
    private static int initialDefendTroops = 1200;
    private static int commanderDeathPenalty = 2;

    // ========== 体力参数 ==========
    // playerStamina = -1 时整个体力系统禁用
    private static int playerStamina = 100;
    private static int sprintStaminaCostPerSecond = 5;
    private static int jumpStaminaCost = 15;
    private static int staminaRegenDelaySeconds = 2;
    private static int staminaRegenPerSecond = 2;
    private static int staminaFullRecoverySeconds = 12;

    // ========== 多维度战局新增字段 ==========
    private static int teamSelectSeconds = 60;
    private static int factionRevealSeconds = 5;
    private static int roundEndSeconds = 10;
    private static int impeachmentVoteSeconds = 60;
    private static int impeachmentCooldownSeconds = 600;
    private static int commanderVacancySeconds = 180;

    // AuraTip 新手教程（仅手动 reopen/命令；默认不在进服/阶段切换自动弹出）
    private static boolean tutorialEnabled = true;
    private static boolean tutorialShowOnJoin = false;
    private static boolean tutorialAllowSkip = true;

    private static boolean loaded = false;

    /**
     * @deprecated 不再从 datapack 加载。保留空实现以免外部调用崩溃。
     * 运行时参数仅由 {@link #applySnapshot} 从活动地图 EsConfig 设置。
     */
    @Deprecated
    public static void loadConfig(MinecraftServer server) {
        // 有意留空
    }

    private static int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key)) return obj.get(key).getAsInt();
        return defaultValue;
    }

    private static double getDouble(JsonObject obj, String key, double defaultValue) {
        if (obj.has(key)) return obj.get(key).getAsDouble();
        return defaultValue;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean defaultValue) {
        if (obj.has(key)) return obj.get(key).getAsBoolean();
        return defaultValue;
    }

    /**
     * 是否已应用过地图 EsConfig 快照（或历史上的 datapack 加载）。
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 热重载已禁用：请重启以读取 EsWorld/.../EsConfig。
     */
    @Deprecated
    public static void reloadConfig(MinecraftServer server) {
        Espetro.LOGGER.warn("GameConfig 不支持热重载；地图参数仅在战场激活时从 EsConfig 应用。请重启服务端。");
    }

    // ========== Getter ==========

    public static int getRequiredPlayers() {
        return requiredPlayers;
    }

    public static int getDeployTimeoutSeconds() {
        return deployTimeoutSeconds;
    }

    public static int getDeployWarningSeconds() {
        return deployWarningSeconds;
    }

    public static int getDefendCommanderVoteSeconds() {
        return defendCommanderVoteSeconds;
    }

    public static int getAttackCommanderVoteSeconds() {
        return attackCommanderVoteSeconds;
    }

    public static int getDefendFactionSelectSeconds() {
        return defendFactionSelectSeconds;
    }

    public static int getAttackFactionSelectSeconds() {
        return attackFactionSelectSeconds;
    }

    public static int getFactionPoolSize() {
        return factionPoolSize;
    }

    public static int getRespawnInvincibilityTicks() {
        return respawnInvincibilityTicks;
    }

    public static int getClassSwitchCooldownSeconds() {
        return classSwitchCooldownSeconds;
    }

    public static double getTeammateNameTagDistance() {
        return teammateNameTagDistance;
    }

    public static double getWaitingY() {
        return waitingY;
    }

    public static int getInitialAttackTroops() {
        return initialAttackTroops;
    }

    public static int getInitialDefendTroops() {
        return initialDefendTroops;
    }

    public static int getCommanderDeathPenalty() {
        return commanderDeathPenalty;
    }

    public static boolean isStaminaEnabled() {
        return playerStamina != -1;
    }

    public static int getPlayerStamina() {
        return playerStamina;
    }

    public static int getSprintStaminaCostPerSecond() {
        return sprintStaminaCostPerSecond;
    }

    public static int getJumpStaminaCost() {
        return jumpStaminaCost;
    }

    public static int getStaminaRegenDelaySeconds() {
        return staminaRegenDelaySeconds;
    }

    public static int getStaminaRegenPerSecond() {
        return staminaRegenPerSecond;
    }

    public static int getStaminaFullRecoverySeconds() {
        return staminaFullRecoverySeconds;
    }

    public static int getTeamSelectSeconds() {
        return teamSelectSeconds;
    }

    public static int getFactionRevealSeconds() {
        return factionRevealSeconds;
    }

    public static int getRoundEndSeconds() {
        return roundEndSeconds;
    }

    public static int getImpeachmentVoteSeconds() {
        return impeachmentVoteSeconds;
    }

    public static int getImpeachmentCooldownSeconds() {
        return impeachmentCooldownSeconds;
    }

    public static int getCommanderVacancySeconds() {
        return commanderVacancySeconds;
    }

    /**
     * Apply frozen ActiveMapConfig game settings (startup or map activation).
     * Does not re-read JSON from disk.
     */
    public static void applySnapshot(org.espetro.mapconfig.GameSettingsSnapshot s) {
        if (s == null) {
            return;
        }
        teamSelectSeconds = s.teamSelectSeconds;
        deployTimeoutSeconds = s.deployTimeoutSeconds;
        deployWarningSeconds = s.deployWarningSeconds;
        defendCommanderVoteSeconds = s.defendCommanderVoteSeconds;
        attackCommanderVoteSeconds = s.attackCommanderVoteSeconds;
        defendFactionSelectSeconds = s.defendFactionSelectSeconds;
        attackFactionSelectSeconds = s.attackFactionSelectSeconds;
        factionPoolSize = s.factionPoolSize;
        factionRevealSeconds = s.factionRevealSeconds;
        roundEndSeconds = s.roundEndSeconds;
        respawnInvincibilityTicks = s.respawnInvincibilityTicks;
        classSwitchCooldownSeconds = s.classSwitchCooldownSeconds;
        teammateNameTagDistance = s.teammateNameTagDistance;
        waitingY = s.waitingY;
        initialAttackTroops = s.initialAttackTroops;
        initialDefendTroops = s.initialDefendTroops;
        commanderDeathPenalty = s.commanderDeathPenalty;
        playerStamina = s.playerStamina;
        sprintStaminaCostPerSecond = s.sprintCostPerSecond;
        jumpStaminaCost = s.jumpCost;
        staminaRegenDelaySeconds = s.regenDelaySeconds;
        staminaRegenPerSecond = s.regenPerSecond;
        staminaFullRecoverySeconds = s.fullRecoverySeconds;
        impeachmentVoteSeconds = s.impeachmentVoteSeconds;
        impeachmentCooldownSeconds = s.impeachmentCooldownSeconds;
        commanderVacancySeconds = s.commanderVacancySeconds;
        // required_players is deprecated; tutorial steps are AuraTip-driven (manual).
        loaded = true;
        Espetro.LOGGER.info("已应用活动地图 game 快照: 选边{}s 部署{}s 揭示{}s 结算{}s 换职冷却{}s 弹劾{}s/冷却{}s",
            teamSelectSeconds, deployTimeoutSeconds, factionRevealSeconds, roundEndSeconds,
            classSwitchCooldownSeconds, impeachmentVoteSeconds, impeachmentCooldownSeconds);
    }

    public static boolean isTutorialEnabled() {
        return tutorialEnabled;
    }

    public static boolean isTutorialShowOnJoin() {
        return tutorialShowOnJoin;
    }

    public static boolean isTutorialAllowSkip() {
        return tutorialAllowSkip;
    }

}
