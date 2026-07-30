package org.espetro.logistics;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;

/**
 * 禁止放置「建材」补给：
 * <ul>
 *   <li>{@link ConstructionMaterialItem} 本身不是方块物品，无法放置；</li>
 *   <li>若配置仍用木板等方块物品，且堆叠带有 CONSTRUCTION 标记，则拦截右键放置。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public final class SupplyPlacementGuard {

    private SupplyPlacementGuard() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        if (stack.getItem() instanceof ConstructionMaterialItem
            || SupplyManager.getInstance().getSupplyType(stack) == SupplyType.CONSTRUCTION) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        if (stack.getItem() instanceof ConstructionMaterialItem
            || SupplyManager.getInstance().getSupplyType(stack) == SupplyType.CONSTRUCTION) {
            // 避免某些方块物品用「对空气使用」进入放置流程
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }
}
