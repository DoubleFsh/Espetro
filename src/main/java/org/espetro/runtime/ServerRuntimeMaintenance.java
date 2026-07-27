package org.espetro.runtime;

import org.espetro.bastion.BastionManager;
import org.espetro.bastion.HabChannelManager;
import org.espetro.team.TeamPackManager;
import org.espetro.vehicle.VehicleManager;

/**
 * 服务端运行期维护任务调度器。
 *
 * 这里集中处理兜底清理类任务，避免主模组入口在每个 tick 直接扫描多个子系统。
 */
public final class ServerRuntimeMaintenance {

    private static final int INVALID_STATE_SCAN_INTERVAL_TICKS = 20;
    private static final ServerRuntimeMaintenance INSTANCE = new ServerRuntimeMaintenance();

    private long tickCounter;

    private ServerRuntimeMaintenance() {
    }

    public static ServerRuntimeMaintenance getInstance() {
        return INSTANCE;
    }

    public void reset() {
        tickCounter = 0;
        HabChannelManager.getInstance().reset();
    }

    public void onServerTick() {
        TeamPackManager.getInstance().onServerTick();
        HabChannelManager.getInstance().tick();
        if (tickCounter++ % INVALID_STATE_SCAN_INTERVAL_TICKS == 0) {
            runInvalidStateCleanup();
        }
    }

    private void runInvalidStateCleanup() {
        BastionManager.getInstance().removeInvalidBastions();
        TeamPackManager.getInstance().cleanupInvalidTeamPacks();
        VehicleManager.getInstance().removeInvalidVehicles();
    }
}
