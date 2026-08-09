package org.espetro.runtime;

import org.espetro.bastion.BastionManager;
import org.espetro.dimension.BattlefieldWorldManager;
import org.espetro.team.TeamPackManager;
import org.espetro.stats.PlayerMatchStatsManager;
import org.espetro.network.NetworkManager;
import org.espetro.vehicle.VehicleManager;

/**
 * 服务端运行期维护任务调度器。
 *
 * 这里集中处理兜底清理类任务，避免主模组入口在每个 tick 直接扫描多个子系统。
 */
public final class ServerRuntimeMaintenance {

    /**
     * 兜底扫描：5 秒一次。兵站真摧毁已事件驱动；此处仅清脏记录 / 刷新已加载核心坐标，
     * 禁止全图 getAllEntities 或强加载区块。
     */
    private static final int INVALID_STATE_SCAN_INTERVAL_TICKS = 100;
    private static final ServerRuntimeMaintenance INSTANCE = new ServerRuntimeMaintenance();

    private long tickCounter;

    private ServerRuntimeMaintenance() {
    }

    public static ServerRuntimeMaintenance getInstance() {
        return INSTANCE;
    }

    public void reset() {
        tickCounter = 0;
        NetworkManager.clearQueuedFullScreens();
    }

    public void onServerTick() {
        BattlefieldWorldManager.getInstance().onServerTick();
        NetworkManager.drainQueuedFullScreens();
        PlayerMatchStatsManager.getInstance().onServerTick();
        TeamPackManager.getInstance().onServerTick();
        VehicleManager.getInstance().processInitialVehicleDeployments();
        long tick = tickCounter++;
        // 分摊：兵站 / 队包 / 载具 不同步扫，降低尖峰
        if (tick % INVALID_STATE_SCAN_INTERVAL_TICKS == 0) {
            BastionManager.getInstance().removeInvalidBastions();
        } else if (tick % INVALID_STATE_SCAN_INTERVAL_TICKS == 33) {
            TeamPackManager.getInstance().cleanupInvalidTeamPacks();
        } else if (tick % INVALID_STATE_SCAN_INTERVAL_TICKS == 66) {
            VehicleManager.getInstance().removeInvalidVehicles();
        }
    }
}
