package com.example.hcrpoints.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 通用消息弹窗类，用于显示各种类型的消息
 */
public class MessagePopup implements IGuiOverlay {
    // 单例实例
    private static final MessagePopup INSTANCE = new MessagePopup();
    
    // 弹窗持续时间（毫秒）
    private static final int DEFAULT_DURATION = 4000;
    // 动画时长（毫秒）
    private static final int ANIMATION_DURATION = 1000;
    
    // 弹窗默认样式
    private static final int DEFAULT_WIDTH = 150;
    private static final int DEFAULT_HEIGHT = 40;
    private static final int DEFAULT_BACKGROUND_COLOR = 0xFF000000;
    private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
    private static final int DEFAULT_BORDER_COLOR = 0xFFAAAAAA;
    private static final int DEFAULT_BORDER_WIDTH = 2;
    
    // 消息条目类
    private static class MessageEntry {
        String message;
        long startTime;
        long endTime;
        int width;
        int height;
        int backgroundColor;
        int textColor;
        int borderColor;
        int borderWidth;
        
        MessageEntry(String message, long startTime, long endTime, int width, int height, 
                   int backgroundColor, int textColor, int borderColor, int borderWidth) {
            this.message = message;
            this.startTime = startTime;
            this.endTime = endTime;
            this.width = width;
            this.height = height;
            this.backgroundColor = backgroundColor;
            this.textColor = textColor;
            this.borderColor = borderColor;
            this.borderWidth = borderWidth;
        }
    }
    
    // 玩家消息映射（玩家UUID -> 消息列表）
    private final ConcurrentMap<UUID, List<MessageEntry>> playerMessages = new ConcurrentHashMap<>();
    
    private MessagePopup() {
        // 私有构造函数，防止外部实例化
    }
    
    /**
     * 获取MessagePopup实例
     * @return 单例实例
     */
    public static MessagePopup getInstance() {
        return INSTANCE;
    }
    
    /**
     * 显示胜利消息
     * @param playerUUID 玩家UUID
     */
    public void showWinMessage(UUID playerUUID) {
        showMessage(playerUUID, "胜利", DEFAULT_DURATION);
    }
    
    /**
     * 显示失败消息
     * @param playerUUID 玩家UUID
     */
    public void showLoseMessage(UUID playerUUID) {
        showMessage(playerUUID, "失败", DEFAULT_DURATION);
    }
    
    /**
     * 显示普通消息
     * @param playerUUID 玩家UUID
     * @param message 消息内容
     * @param duration 显示持续时间（毫秒）
     */
    public void showMessage(UUID playerUUID, String message, long duration) {
        showMessage(playerUUID, message, duration, DEFAULT_WIDTH, DEFAULT_HEIGHT, 
                   DEFAULT_BACKGROUND_COLOR, DEFAULT_TEXT_COLOR, DEFAULT_BORDER_COLOR, DEFAULT_BORDER_WIDTH);
    }
    
    /**
     * 显示自定义样式的消息
     * @param playerUUID 玩家UUID
     * @param message 消息内容
     * @param duration 显示持续时间（毫秒）
     * @param width 弹窗宽度
     * @param height 弹窗高度
     * @param backgroundColor 背景颜色
     * @param textColor 文字颜色
     * @param borderColor 边框颜色
     * @param borderWidth 边框宽度
     */
    public void showMessage(UUID playerUUID, String message, long duration, 
                          int width, int height, int backgroundColor, 
                          int textColor, int borderColor, int borderWidth) {
        long currentTime = System.currentTimeMillis();
        long endTime = currentTime + duration;
        
        MessageEntry entry = new MessageEntry(message, currentTime, endTime, width, height, 
                                            backgroundColor, textColor, borderColor, borderWidth);
        
        // 获取或创建玩家的消息列表
        List<MessageEntry> messages = playerMessages.computeIfAbsent(playerUUID, k -> new ArrayList<>());
        messages.add(entry);
    }
    
    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        
        UUID playerUUID = minecraft.player.getUUID();
        List<MessageEntry> messages = playerMessages.get(playerUUID);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // 遍历消息列表，渲染并清理过期消息
        messages.removeIf(entry -> {
            if (currentTime > entry.endTime) {
                return true; // 清理过期消息
            }
            
            // 渲染消息
            renderMessage(guiGraphics, screenWidth, screenHeight, entry, currentTime);
            return false;
        });
        
        // 如果消息列表为空，移除映射
        if (messages.isEmpty()) {
            playerMessages.remove(playerUUID);
        }
    }
    
    /**
     * 渲染单条消息
     * @param guiGraphics GUI图形对象
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @param entry 消息条目
     * @param currentTime 当前时间
     */
    private void renderMessage(GuiGraphics guiGraphics, int screenWidth, int screenHeight, 
                              MessageEntry entry, long currentTime) {
        Minecraft minecraft = Minecraft.getInstance();
        
        // 计算动画进度（0.0-1.0）
        long animationProgress = currentTime - entry.startTime;
        float timeProgress = Math.min(1.0f, (float)animationProgress / ANIMATION_DURATION);
        
        // 应用easeOutCubic缓动曲线
        float easeProgress = easeOutCubic(timeProgress);
        
        // 计算弹窗位置：从右侧滑入
        int finalX = screenWidth - entry.width - 10; // 最终位置：距离右侧10像素
        int startX = screenWidth; // 开始位置：屏幕右侧边缘
        int windowX = (int)(startX + (finalX - startX) * easeProgress); // 当前X坐标
        int windowY = (screenHeight - entry.height) / 2; // 垂直居中
        
        // 渲染背景
        guiGraphics.fill(windowX, windowY, windowX + entry.width, windowY + entry.height, entry.backgroundColor);
        
        // 渲染边框
        int borderWidth = entry.borderWidth;
        if (borderWidth > 0) {
            // 上边框
            guiGraphics.fill(windowX, windowY, windowX + entry.width, windowY + borderWidth, entry.borderColor);
            // 下边框
            guiGraphics.fill(windowX, windowY + entry.height - borderWidth, 
                           windowX + entry.width, windowY + entry.height, entry.borderColor);
            // 左边框
            guiGraphics.fill(windowX, windowY, windowX + borderWidth, windowY + entry.height, entry.borderColor);
            // 右边框
            guiGraphics.fill(windowX + entry.width - borderWidth, windowY, 
                           windowX + entry.width, windowY + entry.height, entry.borderColor);
        }
        
        // 渲染消息文字
        int textWidth = minecraft.font.width(entry.message);
        int textX = windowX + (entry.width - textWidth) / 2; // 水平居中
        int textY = windowY + (entry.height - minecraft.font.lineHeight) / 2; // 垂直居中
        
        guiGraphics.drawString(
            minecraft.font,
            entry.message,
            textX,
            textY,
            entry.textColor,
            false
        );
    }
    
    /**
     * easeOutCubic缓动函数，实现先快后慢的效果
     * @param t 时间进度（0.0-1.0）
     * @return 缓动后的进度（0.0-1.0）
     */
    private float easeOutCubic(float t) {
        return 1.0f - (float)Math.pow(1.0f - t, 3.0f);
    }
    

}