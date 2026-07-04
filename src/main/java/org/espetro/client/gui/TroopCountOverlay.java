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
    private static Component attackText = buildAttackText();
    private static Component defendText = buildDefendText();

    /**
     * 更新兵力数据
     */
    public static void updateTroopCounts(int attack, int defend) {
        attackTroops = attack;
        defendTroops = defend;
        attackText = buildAttackText();
        defendText = buildDefendText();
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

        graphics.drawString(mc.font, attackText, x, y, 0xFFFFFF);
        graphics.drawString(mc.font, defendText, x + 110, y, 0xFFFFFF);

        poseStack.popPose();
    }

    private static Component buildAttackText() {
        String color = attackTroops > 50 ? "§a" : (attackTroops > 20 ? "§e" : "§c");
        return Component.literal("§c■ 进攻方: " + color + attackTroops);
    }

    private static Component buildDefendText() {
        String color = defendTroops > 50 ? "§a" : (defendTroops > 20 ? "§e" : "§c");
        return Component.literal("§9■ 防守方: " + color + defendTroops);
    }
}
