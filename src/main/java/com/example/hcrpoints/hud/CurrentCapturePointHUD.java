package com.example.hcrpoints.hud;

import com.example.hcrpoints.capturepoint.CapturePoint;
import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.example.hcrpoints.capturepoint.DisplayState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 当前据点状态HUD渲染类
 * 负责在准星下方显示玩家当前所在据点的状态信息
 */
public class CurrentCapturePointHUD implements IGuiOverlay {
    private static final int BOX_SIZE = 16;       // 据点状态方块大小
    private static final int PROGRESS_BAR_WIDTH = 80; // 进度条宽度
    private static final int PROGRESS_BAR_HEIGHT = 5; // 进度条高度
    private static final int BOX_TO_PROGRESS_MARGIN = 2; // 方块到进度条的间距

    /**
     * 渲染HUD界面
     * @param gui Forge GUI对象
     * @param guiGraphics GUI图形对象
     * @param partialTick 部分刻度
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     */
    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        // 获取Minecraft实例
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        // 获取玩家当前所在的据点
        CapturePointManager manager = CapturePointManager.getInstance();
        CapturePoint currentPoint = manager.checkPlayerInCapturePoint(mc.player);
        if (currentPoint == null) {
            return; // 如果不在据点内，不渲染
        }

        // 计算HUD起始位置（右上角）
        int margin = 10; // 距离屏幕右边缘的边距
        int hudStartX = screenWidth - BOX_SIZE - margin;
        int hudStartY = 10 + 15; // 屏幕顶部下方（与原顶部HUD同高度）

        // 渲染据点状态方块
        renderCapturePointBox(guiGraphics, currentPoint, hudStartX, hudStartY, mc);

        // 渲染进度条（右对齐到方块下方）
        int progressBarX = screenWidth - PROGRESS_BAR_WIDTH - margin;
        renderProgressBar(guiGraphics, currentPoint, progressBarX, hudStartY + BOX_SIZE + BOX_TO_PROGRESS_MARGIN);
    }

    /**
     * 渲染据点状态方块
     * @param guiGraphics GUI图形对象
     * @param point 据点对象
     * @param x 方块X坐标
     * @param y 方块Y坐标
     * @param mc Minecraft实例
     */
    private void renderCapturePointBox(GuiGraphics guiGraphics, CapturePoint point, int x, int y, Minecraft mc) {
        // 获取显示状态对应的颜色
        int color = getStatusColor(point.getDisplayState(), point.getCaptorName(), mc);

        // 绘制背景方块
        guiGraphics.fill(x, y, x + BOX_SIZE, y + BOX_SIZE, color);

        // 绘制边框
        guiGraphics.fill(x, y, x + BOX_SIZE, y + 1, 0xFF000000);           // 上边框
        guiGraphics.fill(x, y, x + 1, y + BOX_SIZE, 0xFF000000);           // 左边框
        guiGraphics.fill(x, y + BOX_SIZE - 1, x + BOX_SIZE, y + BOX_SIZE, 0xFF000000); // 下边框
        guiGraphics.fill(x + BOX_SIZE - 1, y, x + BOX_SIZE, y + BOX_SIZE, 0xFF000000); // 右边框

        // 绘制据点名称（在方块内部居中）
        String name = point.getName();
        // 如果名称较长，只取第一个字符
        if (name.length() > 2) {
            name = name.substring(0, 2);
        }
        guiGraphics.drawString(
                mc.font,
                name,
                x + BOX_SIZE / 2 - mc.font.width(name) / 2,
                y + BOX_SIZE / 2 - 4,
                0xFFFFFF
        );
    }

    /**
     * 渲染进度条
     * @param guiGraphics GUI图形对象
     * @param point 据点对象
     * @param x 进度条X坐标
     * @param y 进度条Y坐标
     */
    private void renderProgressBar(GuiGraphics guiGraphics, CapturePoint point, int x, int y) {
        // 获取显示状态对应的颜色
        int color = getProgressBarColor(point);

        // 绘制进度条背景
        guiGraphics.fill(x, y, x + PROGRESS_BAR_WIDTH, y + PROGRESS_BAR_HEIGHT, 0xFF555555);

        // 绘制进度条
        int progressWidth = (int) (point.getProgress() / 100.0 * PROGRESS_BAR_WIDTH);
        guiGraphics.fill(x, y, x + progressWidth, y + PROGRESS_BAR_HEIGHT, color);
    }

    /**
     * 根据显示状态获取对应颜色
     * @param displayState 显示状态
     * @param captorName 占领者名称
     * @param mc Minecraft实例
     * @return 对应的颜色值（ARGB格式）
     */
    private int getStatusColor(DisplayState displayState, String captorName, Minecraft mc) {
        switch (displayState) {
            case NEUTRAL:
                return 0xFF808080; // 灰色 - 中立状态

            case CAPTURING_FLAG_SINGLE:
                return 0xFFFFFF00; // 黄色 - 单人升旗状态

            case CAPTURING_CONTESTED_MULTI:
                return 0xFFFF8000; // 橙色 - 争夺中升旗状态

            case CONTESTED_MULTI:
                return 0xFFFF8000; // 橙色 - 争夺状态

            case CAPTURING_DOWN:
                return 0xFFFF0000; // 红色 - 降旗状态

            case CAPTURED:
                if (mc.player != null) {
                    // 检查当前玩家是否属于占领者
                    if (isFriendlyCapture(mc, captorName)) {
                        return 0xFF00FF00; // 绿色 - 友方占领
                    } else {
                        return 0xFFFF0000; // 红色 - 敌方占领
                    }
                } else {
                    return 0xFFFF0000; // 红色 - 敌方占领
                }

            default:
                return 0xFF808080; // 默认灰色
        }
    }
    
    /**
     * 检查占领是否为友方
     * @param mc Minecraft实例
     * @param captorName 占领者名称（玩家名称或队伍名称）
     * @return 如果是友方占领返回true，否则返回false
     */
    private boolean isFriendlyCapture(Minecraft mc, String captorName) {
        if (mc.player == null) {
            return false;
        }
        
        // 检查当前玩家的队伍
        net.minecraft.world.scores.Team playerTeam = mc.player.getTeam();
        
        // 如果占领者是玩家名称
        if (captorName.equals(mc.player.getName().getString())) {
            return true;
        }
        
        // 如果占领者是队伍名称，且当前玩家在该队伍中
        if (playerTeam != null && captorName.equals(playerTeam.getName())) {
            return true;
        }
        
        return false;
    }

    /**
     * 获取进度条颜色
     * @param point 据点对象
     * @return 进度条颜色
     */
    private int getProgressBarColor(CapturePoint point) {
        DisplayState displayState = point.getDisplayState();
        switch (displayState) {
            case CAPTURED:
                // 检查占领是否为友方，根据情况显示不同颜色
                Minecraft mc = Minecraft.getInstance();
                if (isFriendlyCapture(mc, point.getCaptorName())) {
                    return 0xFF55FF55; // 绿色 - 友方占领
                } else {
                    return 0xFFFF5555; // 红色 - 敌方占领
                }
            case CAPTURING_FLAG_SINGLE:
            case CAPTURING_CONTESTED_MULTI:
            case CONTESTED_MULTI:
            case CAPTURING_DOWN:
                return 0xFFFFAA00; // 橙色
            case NEUTRAL:
            default:
                return 0xFFAAAAAA; // 灰色
        }
    }
}
