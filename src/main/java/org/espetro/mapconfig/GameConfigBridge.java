package org.espetro.mapconfig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.espetro.config.GameConfig;
import org.espetro.team.SpawnPointConfig;
import org.espetro.bastion.BastionManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.team.OutpostManager;
import org.espetro.team.TeamPackManager;
import org.espetro.vehicle.VehicleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将活动地图 {@link ActiveMapConfig}（来自 EsWorld/.../EsConfig）应用到运行时静态配置。
 * 这是 game/spawn/bastion/outposts/logistics/team_pack/vehicles 的唯一权威写入路径。
 */
public final class GameConfigBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameConfigBridge.class);

    /**
     * 从 CapturePoints.json 读取的进攻批次奖励兵力值。
     * 0 表示未配置/使用默认值，由 ESPoints 的硬编码值（200）兜底。
     */
    private static int attackBatchCompletionReinforcement = 0;

    private GameConfigBridge() {
    }

    /**
     * 获取进攻批次奖励兵力配置值。
     * @return 配置值，0 表示未配置（由 ESPoints 默认值兜底）
     */
    public static int getAttackBatchCompletionReinforcement() {
        return attackBatchCompletionReinforcement;
    }

    public static void apply(ActiveMapConfig config) {
        if (config == null || !config.usable) {
            return;
        }
        GameConfig.applySnapshot(config.game);
        if (config.spawnPoints != null && config.spawnPoints.valid) {
            SpawnPointConfig.setSpawnPoint("ATTACK",
                config.spawnPoints.attack.x(),
                config.spawnPoints.attack.y(),
                config.spawnPoints.attack.z(),
                config.spawnPoints.attack.yaw());
            SpawnPointConfig.setSpawnPoint("DEFEND",
                config.spawnPoints.defend.x(),
                config.spawnPoints.defend.y(),
                config.spawnPoints.defend.z(),
                config.spawnPoints.defend.yaw());
        }
        BastionManager.getInstance().applyExternalJson(config.bastionJson);
        OutpostManager.getInstance().applyExternalJson(config.outpostsJson);
        LogisticsConfig.applyExternalJson(config.logisticsJson);
        TeamPackManager.getInstance().applyExternalJson(config.teamPackJson);
        VehicleConfig.applyActiveMap(config);
        // 从 CapturePoints.json 读取进攻批次奖励兵力（不依赖 HCRPoints 代码）
        String cpJson = config.capturePointsJson();
        if (cpJson != null && !cpJson.isEmpty()) {
            parseCapturePointsConfig(cpJson);
        }
    }

    /**
     * 从 CapturePoints.json 字符串中读取 attackBatchCompletionReinforcement。
     * 存入静态字段，供 EspetroCommand 在 ESPoints 调用 /espetro troops add ATTACK 时覆写。
     * <p>
     * 由于 Forge SecureJar 类加载器隔离，无法通过反射直接修改 ESPoints JAR 中的字段。
     * 改为在命令分发层拦截：ESPoints 用 server.createCommandSourceStack()（entity=null）
     * 调用命令，与玩家手动输入（entity=ServerPlayer）可区分。
     */
    private static void parseCapturePointsConfig(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("attackBatchCompletionReinforcement")) {
                int value = root.get("attackBatchCompletionReinforcement").getAsInt();
                if (value > 0) {
                    attackBatchCompletionReinforcement = value;
                    LOGGER.info("从 CapturePoints.json 读取进攻批次奖励兵力: {}", value);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("解析 CapturePoints.json 中的 attackBatchCompletionReinforcement 失败: {}", e.getMessage());
        }
    }
}
