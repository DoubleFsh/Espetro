package com.example.hcrpoints.hud;

import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 兵力显示HUD类，当战术地图未显示时，在屏幕顶部显示攻/守双方剩余增援兵力
 */
public class ReinforcementsHUD implements IGuiOverlay {
    private static final int HUD_HEIGHT = 30;
    private static final int BAR_HEIGHT = 10;
    private static final int TEXT_PADDING = 2;
    
    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        // 检查是否应该渲染兵力HUD
        if (!shouldRender()) {
            return;
        }
        
        CapturePointManager manager = CapturePointManager.getInstance();
        
        // 获取攻防双方队伍名称
        String attackerTeam = manager.getAttackerTeam();
        String defenderTeam = manager.getDefenderTeam();
        
        if (attackerTeam == null || defenderTeam == null) {
            return; // 队伍未设置完整，不显示
        }
        
        // 获取双方兵力
        int attackerReinforcements = manager.getTeamReinforcements(attackerTeam);
        int defenderReinforcements = manager.getTeamReinforcements(defenderTeam);
        
        // 计算进度条位置和尺寸
        // 显示在快速据点状态HUD下方，宽度设置为400像素，并确保距离屏幕边缘有足够距离
        int minMargin = 30; // 距离屏幕边缘的最小距离
        int targetBarWidth = 400; // 目标宽度
        
        // 确保进度条距离屏幕左右边缘至少有minMargin距离
        int maxPossibleWidth = screenWidth - 2 * minMargin;
        int barWidth = Math.min(targetBarWidth, maxPossibleWidth); // 如果屏幕宽度不足，则调整宽度
        
        int barTop = 50; // 屏幕顶部下方50像素处，下移10像素
        int barLeft = (screenWidth - barWidth) / 2; // 居中显示
        int barRight = barLeft + barWidth;
        int halfBarWidth = barWidth / 2;
        
        // 从配置中获取颜色代码
        String defenderHexColor = com.example.hcrpoints.config.TacticalMapConfig.defenderProgressBarColor.get();
        String attackerHexColor = com.example.hcrpoints.config.TacticalMapConfig.attackerProgressBarColor.get();
        
        // 将颜色代码转换为整数颜色值
        int defenderColor = com.example.hcrpoints.util.ModLogger.hexToColor(defenderHexColor, 0xFF0055FF); // 默认蓝色
        int attackerColor = com.example.hcrpoints.util.ModLogger.hexToColor(attackerHexColor, 0xFFFF5500); // 默认红色
        
        // 渲染守方进度条
        int defenderInitialReinforcements = manager.getTeamInitialReinforcements(defenderTeam);
        int defenderBarWidth = 0;
        if (defenderInitialReinforcements > 0) {
            defenderBarWidth = (int)((double)defenderReinforcements / defenderInitialReinforcements * halfBarWidth);
        }
        guiGraphics.fill(barLeft, barTop, barLeft + halfBarWidth, barTop + BAR_HEIGHT, 0x44000000); // 背景
        guiGraphics.fill(barLeft, barTop, barLeft + defenderBarWidth, barTop + BAR_HEIGHT, defenderColor); // 进度条
        
        // 渲染攻方进度条
        int attackerInitialReinforcements = manager.getTeamInitialReinforcements(attackerTeam);
        int attackerBarWidth = 0;
        if (attackerInitialReinforcements > 0) {
            attackerBarWidth = (int)((double)attackerReinforcements / attackerInitialReinforcements * halfBarWidth);
        }
        int attackerBarLeft = barLeft + halfBarWidth;
        guiGraphics.fill(attackerBarLeft, barTop, attackerBarLeft + halfBarWidth, barTop + BAR_HEIGHT, 0x44000000); // 背景
        guiGraphics.fill(attackerBarLeft + halfBarWidth - attackerBarWidth, barTop, attackerBarLeft + halfBarWidth, barTop + BAR_HEIGHT, attackerColor); // 进度条
        
        // 绘制队伍名称和兵力数量
        Minecraft minecraft = Minecraft.getInstance();
        
        // 守方信息（左对齐）
        String defenderText = defenderTeam + ": " + defenderReinforcements;
        guiGraphics.drawString(
            minecraft.font,
            defenderText,
            barLeft,
            barTop + BAR_HEIGHT + TEXT_PADDING,
            0xFFFFFF,
            false
        );
        
        // 攻方信息（右对齐）
        String attackerText = attackerTeam + ": " + attackerReinforcements;
        guiGraphics.drawString(
            minecraft.font,
            attackerText,
            barRight - minecraft.font.width(attackerText),
            barTop + BAR_HEIGHT + TEXT_PADDING,
            0xFFFFFF,
            false
        );
    }
    
    /**
     * 检查是否应该渲染兵力HUD
     * @return 是否应该渲染
     */
    private boolean shouldRender() {
        // 已禁用兵力计数功能
        return false;
    }
}