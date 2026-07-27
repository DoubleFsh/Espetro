package org.espetro.team;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;

/**
 * 战场挖掘限制：用「无法满足方块所需挖掘等级」代替挖掘疲劳。
 * <ul>
 *   <li>{@link PlayerEvent.HarvestCheck} — 判定玩家挖掘等级不足以采集该方块</li>
 *   <li>{@link PlayerEvent.BreakSpeed} — 挖掘速度归零，避免裂纹进度</li>
 *   <li>{@link BlockEvent.BreakEvent} — 服务端兜底禁止破坏</li>
 * </ul>
 * Radio / 队包等特殊交互走各自事件，不依赖原版破坏流程。
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public final class BattlefieldMiningHandler {

    private BattlefieldMiningHandler() {
    }

    /** 将玩家视为挖掘等级不足，无法收获任何方块。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        if (GameStateManager.getInstance().shouldRestrictBattlefieldMining(event.getEntity())) {
            event.setCanHarvest(false);
        }
    }

    /** 挖掘速度归零，客户端裂纹与服务端进度一致无法推进。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (GameStateManager.getInstance().shouldRestrictBattlefieldMining(event.getEntity())) {
            event.setNewSpeed(0.0f);
        }
    }

    /** 服务端兜底：即便其它路径试图破坏方块也取消。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player != null
            && GameStateManager.getInstance().shouldRestrictBattlefieldMining(player)) {
            event.setCanceled(true);
        }
    }
}
