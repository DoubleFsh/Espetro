package org.espetro.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 兵力统计HUD叠加层
 * 在屏幕左上角仅显示己方兵力。
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
    static void drawElement(GuiGraphics graphics, Minecraft mc) {
        if (!visible || mc.level == null) return;

        String myTeam = ClientGameState.getPlayerTeam();
        if (myTeam == null) return;

        int troops = "ATTACK".equals(myTeam) ? attackTroops : defendTroops;
        String label = "ATTACK".equals(myTeam) ? "§c■ 进攻方" : "§9■ 防守方";
        String color = troops > 50 ? "§a" : (troops > 20 ? "§e" : "§c");

        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        graphics.drawString(mc.font, Component.literal(label + ": " + color + troops), 10, 10, 0xFFFFFF);
        poseStack.popPose();
    }
}
