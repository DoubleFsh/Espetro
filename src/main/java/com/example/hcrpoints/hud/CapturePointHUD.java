package com.example.hcrpoints.hud;

import com.example.hcrpoints.capturepoint.CapturePoint;
import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.example.hcrpoints.capturepoint.DisplayState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.ArrayList;
import java.util.List;

/**
 * 据点HUD渲染类
 * 负责在游戏界面上渲染据点状态信息
 */
public class CapturePointHUD implements IGuiOverlay {
    private static final int BOX_SIZE = 16;       // 据点状态方块大小
    private static final int BOX_SPACING = 4;     // 方块间距
    private static final int HUD_MARGIN = 10;     // HUD边距
    
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
        return; // 已禁用顶部据点状态UI
        /*
        // 获取据点管理器实例
        CapturePointManager manager = CapturePointManager.getInstance();
        List<CapturePoint> capturePoints = new ArrayList<>(manager.getAllCapturePoints());
        
        if (capturePoints.isEmpty()) {
            return; // 没有据点时不渲染
        }
        
        // 获取Minecraft实例
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        
        // 计算HUD起始位置（屏幕顶部中间，区域HUD下方）
        int totalWidth = capturePoints.size() * (BOX_SIZE + BOX_SPACING) - BOX_SPACING;
        int hudStartX = (screenWidth - totalWidth) / 2;
        int hudStartY = HUD_MARGIN + 15; // 区域HUD在上方，距离屏幕顶部10像素，快速据点状态HUD在下方，距离区域HUD约5像素
        
        // 为每个据点渲染状态方块（水平排列）
        int boxRowY = hudStartY; // 据点状态方块的Y坐标
        for (int i = 0; i < capturePoints.size(); i++) {
            CapturePoint point = capturePoints.get(i);
            int x = hudStartX + i * (BOX_SIZE + BOX_SPACING);
            int y = boxRowY;
            
            // 渲染据点状态方块
            renderCapturePointBox(guiGraphics, point, x, y, mc);
        }
        */
    }
    
    /**
     * 渲染单个据点状态方块
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
        guiGraphics.drawString(Minecraft.getInstance().font, name, 
            x + BOX_SIZE/2 - Minecraft.getInstance().font.width(name)/2, 
            y + BOX_SIZE/2 - 4, 0xFFFFFF);
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
}