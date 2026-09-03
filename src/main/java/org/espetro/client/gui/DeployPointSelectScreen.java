package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.espetro.network.DeployPointSelectPacket;
import org.espetro.client.aui.GuiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 复活点选择界面
 * 玩家死亡后打开，选择在原部署点或兵站复活
 */
public class DeployPointSelectScreen extends EspetroMenuScreen {

    private final boolean hasDeployPoint;
    private final String deployPointPos;
    private final List<DeployPointSelectPacket.BastionItem> bastions;

    private static final int BUTTON_WIDTH = 260;
    private static final int BUTTON_HEIGHT = 26;
    private static final int VERTICAL_SPACING = 5;
    private static final int START_Y = 55;
    private int startX;

    public DeployPointSelectScreen(boolean hasDeployPoint, String deployPointPos,
                                   List<DeployPointSelectPacket.BastionItem> bastions) {
        super(Component.literal("选择复活点"));
        this.hasDeployPoint = hasDeployPoint;
        this.deployPointPos = deployPointPos;
        this.bastions = bastions != null ? bastions : new ArrayList<>();
    }

    @Override
    protected void buildMenuRoot(GuiElement root) {
        startX = (this.width - BUTTON_WIDTH) / 2;
        EspetroAuiWidgets.addPhaseHeader(root, this.width,
            "§6§l选择复活位置", "§e请选择你要复活的位置", "",
            EspetroAuiWidgets.GOLD);

        int y = START_Y;

        // 主基地按钮
        if (hasDeployPoint) {
            String label = "§e主基地 §7(" + deployPointPos + ")";
            root.addChild(EspetroAuiWidgets.button(startX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                label, () -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.connection.sendCommand("bastion deploy");
                }
            }));
            y += BUTTON_HEIGHT + VERTICAL_SPACING;
        }

        // 兵站按钮
        for (DeployPointSelectPacket.BastionItem b : bastions) {
            String label = "§a" + b.name + " §7(" + b.pos + ")";
            root.addChild(EspetroAuiWidgets.button(startX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                label, () -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.connection.sendCommand(
                        "bastion select " + b.id.toString());
                }
            }));
            y += BUTTON_HEIGHT + VERTICAL_SPACING;
        }

        // 没有可选复活点
        if (!hasDeployPoint && bastions.isEmpty()) {
            root.addChild(EspetroAuiWidgets.button(startX, y, BUTTON_WIDTH, 30,
                "§c没有可用的复活点！", null).setEnabled(false));
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // 死亡时不可关闭
    }

    @Override
    public void onClose() {
        // 兼容旧复活点包：成功选择复活点前始终保持本界面。
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
