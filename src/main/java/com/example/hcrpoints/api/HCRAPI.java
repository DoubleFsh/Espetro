package com.example.hcrpoints.api;

import com.example.hcrpoints.hud.MessagePopup;
import com.example.hcrpoints.hud.TacticalMapHUD;
import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.example.hcrpoints.network.ShowMessagePopupMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * HCR Points 模组的统一API管理类
 * 集中管理所有对外暴露的API接口
 */
public class HCRAPI {
    private static final CapturePointManager CAPTURE_POINT_MANAGER = CapturePointManager.getInstance();
    
    /**
     * 显示胜利消息
     * @param playerUUID 玩家UUID
     */
    public static void showWinMessage(UUID playerUUID) {
        showMessage(playerUUID, "胜利", 4000);
    }
    
    /**
     * 显示失败消息
     * @param playerUUID 玩家UUID
     */
    public static void showLoseMessage(UUID playerUUID) {
        showMessage(playerUUID, "失败", 4000);
    }
    
    /**
     * 显示普通消息
     * @param playerUUID 玩家UUID
     * @param message 消息内容
     * @param duration 显示持续时间（毫秒）
     */
    public static void showMessage(UUID playerUUID, String message, long duration) {
        showMessage(playerUUID, message, duration, 150, 40, 
                   0xFF000000, 0xFFFFFFFF, 0xFFAAAAAA, 2);
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
    public static void showMessage(UUID playerUUID, String message, long duration, 
                                 int width, int height, int backgroundColor, 
                                 int textColor, int borderColor, int borderWidth) {
        // 检查是否在服务器端运行
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            // 服务器端：通过网络消息发送给客户端
            for (ServerPlayer player : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
                if (player.getUUID().equals(playerUUID)) {
                    ShowMessagePopupMessage.sendToPlayer(player, playerUUID, message, duration, width, height,
                                                       backgroundColor, textColor, borderColor, borderWidth);
                    break;
                }
            }
        } else {
            // 客户端端：直接调用MessagePopup显示
            MessagePopup.getInstance().showMessage(playerUUID, message, duration, width, height, 
                                backgroundColor, textColor, borderColor, borderWidth);
        }
    }
    
    /**
     * 切换战术地图的可见性
     */
    public static void toggleTacticalMap() {
        TacticalMapHUD.getInstance().toggleMapVisibility();
    }
    
    /**
     * 获取战术地图的可见性
     * @return 地图是否可见
     */
    public static boolean isTacticalMapVisible() {
        return TacticalMapHUD.getInstance().isMapVisible();
    }
    
    /**
     * 循环切换战术地图的显示模式
     */
    public static void cycleTacticalMapDisplayMode() {
        TacticalMapHUD.getInstance().cycleDisplayMode();
    }
    
    /**
     * 获取当前的战术地图显示模式
     * @return 显示模式
     */
    public static com.example.hcrpoints.hud.MapDisplayMode getTacticalMapDisplayMode() {
        return TacticalMapHUD.getInstance().getDisplayMode();
    }
    
    /**
     * 获取据点管理器实例
     * @return 据点管理器实例
     */
    public static CapturePointManager getCapturePointManager() {
        return CAPTURE_POINT_MANAGER;
    }
}