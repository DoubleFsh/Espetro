package org.espetro.mapconfig;

import org.espetro.config.GameConfig;
import org.espetro.team.SpawnPointConfig;
import org.espetro.bastion.BastionManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.team.OutpostManager;
import org.espetro.team.TeamPackManager;
import org.espetro.vehicle.VehicleConfig;

/**
 * 将活动地图 {@link ActiveMapConfig}（来自 EsWorld/.../EsConfig）应用到运行时静态配置。
 * 这是 game/spawn/bastion/outposts/logistics/team_pack/vehicles 的唯一权威写入路径。
 */
public final class GameConfigBridge {

    private GameConfigBridge() {
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
    }
}
