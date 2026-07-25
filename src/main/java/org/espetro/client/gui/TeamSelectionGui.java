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
            // 客户端本地记录已选攻守方，并立刻刷新选中边框（不等下一秒广播）。
            ClientGameState.setPlayerTeam(team);
            if (mc.screen instanceof TeamSelectionScreen screen) {
                TeamSelectionScreen.markLocalSelection(team);
                screen.refreshSelectionBordersPublic();
            }
            // 队伍选择阶段允许重新选择。界面必须由服务端的阶段切换替换，
            // 不能在一次点击后回到游戏画面。
        }
    }
}
