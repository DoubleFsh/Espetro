package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;

/**
 * 职业选择GUI控制器
 */
public class ClassSelectionGui {

    /**
     * 打开职业选择界面
     * - 如果客户端无攻守方 → 先打开攻守方选择界面
     * - 如果已有攻守方 → 向服务端请求该阵营的职业数据
     */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            // 中途加入的玩家必须先选择攻守方
            if (ClientGameState.getPlayerTeam() == null) {
                TeamSelectionScreen.open();
            } else {
                String factionId = ClientGameState.getPlayerFactionId();
                NetworkManager.requestClassSelection(factionId);
            }
        }
    }

    /**
     * 打开指定阵营的职业选择界面（通过服务端获取数据）
     */
    public static void open(String factionId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            // 向服务端请求完整职业数据，服务端会回传 OpenClassSelectionPacket 自动打开GUI
            NetworkManager.requestClassSelection(factionId);
        }
    }

    /**
     * 选择职业
     */
    public static void selectClass(String factionId, String classId) {
        // 发送网络包到服务器
        NetworkManager.sendClassSelect(factionId, classId);
    }
}
