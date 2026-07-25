package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.espetro.network.UnifiedDeployScreenPacket.LoadoutPreview;

/**
 * 客户端缓存的人物模型渲染器。
 * <p>
 * 接收服务端权威 {@link LoadoutPreview}，在一次渲染调用内暂时应用到本地玩家，再使用原版
 * {@link InventoryScreen#renderEntityInInventoryFollowsMouse} 渲染人物模型。
 * <p>
 * 安全约束：
 * <ul>
 *   <li>装备替换与还原都在客户端渲染线程中完成，不会发送网络包。</li>
 *   <li>无论渲染器或第三方盔甲层是否抛出异常，{@code finally} 都会还原真实装备。</li>
 *   <li>复用已正常 tick 的本地玩家，避免未加入世界的虚拟玩家与动画/盔甲模组不兼容。</li>
 * </ul>
 */
public final class ClassPreviewRenderer {

    private LoadoutPreview currentPreview;

    public ClassPreviewRenderer() {
    }

    /**
     * 更新当前预览装备。实体装备只在 {@link #render} 的单帧范围内写入，避免虚拟实体
     * 未 tick 时与第三方玩家动画或盔甲渲染器产生不一致的姿态状态。
     */
    public void update(LoadoutPreview preview) {
        LoadoutPreview next = preview != null ? preview : LoadoutPreview.empty();
        if (samePreview(next, currentPreview)) {
            return;
        }
        currentPreview = next;
    }

    /**
     * 在指定位置渲染人物模型。
     *
     * @param graphics      GUI 绘制上下文
     * @param centerX       模型中心 X（屏幕坐标）
     * @param centerY       模型中心 Y（屏幕坐标，通常略低于区域中心以给头部留空间）
     * @param scale         原版 size 参数（控制模型缩放，例如 30）
     * @param mouseDeltaX   鼠标相对 centerX 的偏移
     * @param mouseDeltaY   鼠标相对 centerY 的偏移
     */
    public void render(GuiGraphics graphics, int centerX, int centerY, int scale,
                       float mouseDeltaX, float mouseDeltaY) {
        LoadoutPreview preview = currentPreview;
        LocalPlayer player = Minecraft.getInstance().player;
        if (preview == null || player == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        int selectedSlot = inventory.selected;
        ItemStack originalHead = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack originalChest = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack originalLegs = player.getItemBySlot(EquipmentSlot.LEGS);
        ItemStack originalFeet = player.getItemBySlot(EquipmentSlot.FEET);
        ItemStack originalOffHand = player.getItemBySlot(EquipmentSlot.OFFHAND);
        ItemStack originalMainHand = inventory.getItem(selectedSlot);

        try {
            player.setItemSlot(EquipmentSlot.HEAD, preview.head);
            player.setItemSlot(EquipmentSlot.CHEST, preview.chest);
            player.setItemSlot(EquipmentSlot.LEGS, preview.legs);
            player.setItemSlot(EquipmentSlot.FEET, preview.feet);
            player.setItemSlot(EquipmentSlot.OFFHAND, preview.offHand);
            inventory.setItem(selectedSlot, preview.mainHand);
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                graphics, centerX, centerY, scale, mouseDeltaX, mouseDeltaY, player);
        } finally {
            player.setItemSlot(EquipmentSlot.HEAD, originalHead);
            player.setItemSlot(EquipmentSlot.CHEST, originalChest);
            player.setItemSlot(EquipmentSlot.LEGS, originalLegs);
            player.setItemSlot(EquipmentSlot.FEET, originalFeet);
            player.setItemSlot(EquipmentSlot.OFFHAND, originalOffHand);
            inventory.setItem(selectedSlot, originalMainHand);
            inventory.selected = selectedSlot;
        }
    }

    /** 清理缓存的预览数据。应在 {@code Screen.removed()} 中调用。 */
    public void clear() {
        currentPreview = null;
    }

    public boolean isReady() {
        return currentPreview != null && Minecraft.getInstance().player != null;
    }

    private static boolean samePreview(LoadoutPreview a, LoadoutPreview b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return ItemStack.matches(a.head, b.head)
            && ItemStack.matches(a.chest, b.chest)
            && ItemStack.matches(a.legs, b.legs)
            && ItemStack.matches(a.feet, b.feet)
            && ItemStack.matches(a.mainHand, b.mainHand)
            && ItemStack.matches(a.offHand, b.offHand);
    }
}
