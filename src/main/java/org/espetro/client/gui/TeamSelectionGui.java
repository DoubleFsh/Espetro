package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;

/**
 * 队伍选择GUI控制器
 */
public class TeamSelectionGui {

    /**
     * 打开攻防方选择界面
     */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            mc.setScreen(new TeamSelectionScreen());
        }
    }

    /**
     * 选择攻防方阵营
     * @param team "ATTACK" 或 "DEFEND"
     */
    public static void selectTeam(String team) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            // 发送阵营选择包到服务器
            NetworkManager.sendFactionSelect(team);
            // 客户端本地记录已选攻守方
            ClientGameState.setPlayerTeam(team);
            // 关闭界面
            mc.setScreen(null);
        }
    }
}
