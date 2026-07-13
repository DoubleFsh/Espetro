package org.espetro.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.espetro.Espetro;
import org.espetro.data.EspetroDataResources;

/**
 * 游戏全局配置加载器
 * 从 data/espetro/config/game.json 加载游戏参数
 * 
 * 涵盖: 人数要求、各阶段超时、兵力初始值、复活保护等
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
    private static int staminaRegenDelaySeconds = 4;
    private static int staminaRegenPerSecond = 2;

    private static boolean loaded = false;

    /**
     * 从数据包加载配置（服务端启动时调用）
     */
    public static void loadConfig(MinecraftServer server) {
        try {
            ResourceManager resourceManager = server.getResourceManager();
            ResourceLocation configLocation = EspetroDataResources.location("config/game.json");

            var resourceOptional = EspetroDataResources.getPreferred(resourceManager, configLocation);
            if (resourceOptional.isPresent()) {
                var resource = resourceOptional.get();
                String jsonStr = EspetroDataResources.readUtf8(resource);
                JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);

                // 游戏参数
                if (root.has("game")) {
                    JsonObject game = root.getAsJsonObject("game");
                    requiredPlayers = getInt(game, "required_players", requiredPlayers);
                    deployTimeoutSeconds = getInt(game, "deploy_timeout_seconds", deployTimeoutSeconds);
                    deployWarningSeconds = getInt(game, "deploy_warning_seconds", deployWarningSeconds);
                    defendCommanderVoteSeconds = getInt(game, "defend_commander_vote_seconds", defendCommanderVoteSeconds);
                    attackCommanderVoteSeconds = getInt(game, "attack_commander_vote_seconds", attackCommanderVoteSeconds);
                    defendFactionSelectSeconds = getInt(game, "defend_faction_select_seconds", defendFactionSelectSeconds);
                    attackFactionSelectSeconds = getInt(game, "attack_faction_select_seconds", attackFactionSelectSeconds);
                    factionPoolSize = getInt(game, "faction_pool_size", factionPoolSize);
                    respawnInvincibilityTicks = getInt(game, "respawn_invincibility_ticks", respawnInvincibilityTicks);
                    teammateNameTagDistance = getDouble(game, "teammate_name_tag_distance", teammateNameTagDistance);
                    waitingY = getDouble(game, "waiting_y", waitingY);
                }

                // 兵力参数
                if (root.has("troops")) {
                    JsonObject troops = root.getAsJsonObject("troops");
                    initialAttackTroops = getInt(troops, "initial_attack", initialAttackTroops);
                    initialDefendTroops = getInt(troops, "initial_defend", initialDefendTroops);
                    commanderDeathPenalty = getInt(troops, "commander_death_penalty", commanderDeathPenalty);
                }

                // 体力参数
                if (root.has("stamina")) {
                    JsonObject stamina = root.getAsJsonObject("stamina");
                    int configuredStamina = getInt(stamina, "player_stamina", playerStamina);
                    playerStamina = configuredStamina == -1 ? -1 : Math.max(0, configuredStamina);
                    sprintStaminaCostPerSecond = Math.max(0,
                        getInt(stamina, "sprint_cost_per_second", sprintStaminaCostPerSecond));
                    jumpStaminaCost = Math.max(0,
                        getInt(stamina, "jump_cost", jumpStaminaCost));
                    staminaRegenDelaySeconds = Math.max(0,
                        getInt(stamina, "regen_delay_seconds", staminaRegenDelaySeconds));
                    staminaRegenPerSecond = Math.max(0,
                        getInt(stamina, "regen_per_second", staminaRegenPerSecond));
                }

                loaded = true;
                Espetro.LOGGER.info("已从 {} 加载游戏配置: 需要{}人, 部署{}秒, 守方指挥官投票{}秒, 攻方指挥官投票{}秒, 守方编制选择{}秒, 攻方编制选择{}秒, 编制池{}个",
                    EspetroDataResources.describeSource(resource), requiredPlayers, deployTimeoutSeconds,
                    defendCommanderVoteSeconds, attackCommanderVoteSeconds,
                    defendFactionSelectSeconds, attackFactionSelectSeconds, factionPoolSize);
                Espetro.LOGGER.info("兵力配置: 攻方{} | 守方{} | 指挥官阵亡惩罚{}",
                    initialAttackTroops, initialDefendTroops, commanderDeathPenalty);
                Espetro.LOGGER.info("体力配置: {}",
                    playerStamina == -1
                        ? "已禁用"
                        : String.format("上限%d | 奔跑每秒消耗%d | 跳跃消耗%d | %d秒后每秒恢复%d",
                            playerStamina, sprintStaminaCostPerSecond, jumpStaminaCost,
                            staminaRegenDelaySeconds, staminaRegenPerSecond));
                return;
            }

            Espetro.LOGGER.warn("未找到 game.json 配置文件，使用默认游戏参数");
        } catch (Exception e) {
            Espetro.LOGGER.error("加载游戏配置失败，使用默认参数", e);
        }
    }

    private static int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key)) return obj.get(key).getAsInt();
        return defaultValue;
    }

    private static double getDouble(JsonObject obj, String key, double defaultValue) {
        if (obj.has(key)) return obj.get(key).getAsDouble();
        return defaultValue;
    }

    /**
     * 检查是否成功加载了 JSON 配置
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 热重载配置（数据包更改后调用）
     */
    public static void reloadConfig(MinecraftServer server) {
        // 重置为默认值，确保缺少的字段不会被旧值残留
        requiredPlayers = 20;
        deployTimeoutSeconds = 240;
        deployWarningSeconds = 30;
        defendCommanderVoteSeconds = 20;
        attackCommanderVoteSeconds = 20;
        defendFactionSelectSeconds = 30;
        attackFactionSelectSeconds = 30;
        factionPoolSize = 6;
        respawnInvincibilityTicks = 60;
        teammateNameTagDistance = 10.0;
        waitingY = 200.0;
        initialAttackTroops = 280;
        initialDefendTroops = 1200;
        commanderDeathPenalty = 2;
        playerStamina = 100;
        sprintStaminaCostPerSecond = 5;
        jumpStaminaCost = 15;
        staminaRegenDelaySeconds = 4;
        staminaRegenPerSecond = 2;
        loaded = false;
        loadConfig(server);
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
}
