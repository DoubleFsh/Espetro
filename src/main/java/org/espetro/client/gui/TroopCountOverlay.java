package org.espetro.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 兵力统计HUD叠加层
 * 在屏幕左上角显示双方兵力
 */
public class TroopCountOverlay {

    private static int attackTroops = 0;
    private static int defendTroops = 0;
    private static boolean visible = false;

    /**
     * 更新兵力数据
     */
    public static void updateTroopCounts(int attack, int defend) {
        attackTroops = attack;
        defendTroops = defend;
        visible = true;
    }

    /**
     * 隐藏兵力统计
     */
    public static void hide() {
        visible = false;
    }

    /**
     * 显示兵力统计
     */
    public static void show() {
        visible = true;
    }

    /**
     * 渲染叠加层
     */
    public static void render(GuiGraphics graphics, Minecraft mc) {
        if (!visible || mc.level == null) return;

        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        int x = 10;
        int y = 10;

        // 攻方兵力
        String attackColor = attackTroops > 50 ? "§a" : (attackTroops > 20 ? "§e" : "§c");
        graphics.drawString(mc.font, Component.literal("§c■ 进攻方: " + attackColor + attackTroops),
            x, y, 0xFFFFFF);

        // 守方兵力（右侧，间距100像素）
        String defendColor = defendTroops > 50 ? "§a" : (defendTroops > 20 ? "§e" : "§c");
        graphics.drawString(mc.font, Component.literal("§9■ 防守方: " + defendColor + defendTroops),
            x + 110, y, 0xFFFFFF);

        poseStack.popPose();
    }
}
