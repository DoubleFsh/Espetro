package org.espetro.client.gui;

import net.minecraft.network.chat.Component;
import org.espetro.client.aui.GuiElement;

/** Peaceful overworld hub menu. Readiness was intentionally removed. */
public final class HubScreen extends EspetroMenuScreen {
    private int onlineCount;
    private String status;
    private EspetroAuiWidgets.Text onlineText;
    private EspetroAuiWidgets.Text statusText;

    public HubScreen(int onlineCount, String status) {
        super(Component.literal("Espetro 主城"));
        this.onlineCount = onlineCount;
        this.status = status == null ? "" : status;
    }

    /** 不重建整页，只改在线人数与状态行。 */
    public void updateStatus(int onlineCount, String status) {
        String nextStatus = status == null ? "" : status;
        if (onlineText != null && this.onlineCount != onlineCount) {
            onlineText.setText("§e在线人数：§f" + onlineCount);
        }
        if (statusText != null && !java.util.Objects.equals(this.status, nextStatus)) {
            statusText.setText(nextStatus);
        }
        this.onlineCount = onlineCount;
        this.status = nextStatus;
    }

    @Override
    protected boolean shadeWorld() {
        return false;
    }

    @Override
    protected void renderBeforeMenu(net.minecraft.client.gui.GuiGraphics graphics,
                                     int mouseX, int mouseY, float partialTick) {
        // 主城保留世界透视，不铺阶段全黑底。
    }

    @Override
    protected void buildMenuRoot(GuiElement root) {
        int panelW = Math.min(260, width - 24);
        int panelH = 126;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;
        // 主城可保留小面板（非失明场景），无全屏遮罩。
        root.addChild(EspetroAuiWidgets.panel(x, y, panelW, panelH, 0xE0191C1E, EspetroAuiWidgets.BORDER));
        root.addChild(EspetroAuiWidgets.centeredText(x + 8, y + 13, panelW - 16,
            "§6§lEspetro 主城", EspetroAuiWidgets.GOLD));
        onlineText = EspetroAuiWidgets.centeredText(x + 8, y + 37, panelW - 16,
            "§e在线人数：§f" + onlineCount, EspetroAuiWidgets.TEXT);
        statusText = EspetroAuiWidgets.centeredText(x + 8, y + 52, panelW - 16,
            status, EspetroAuiWidgets.MUTED);
        root.addChild(onlineText);
        root.addChild(statusText);
        root.addChild(EspetroAuiWidgets.button(x + (panelW - 140) / 2, y + 76, 140, 20,
            "进入新手教程",
            () -> {
                // 关闭主城菜单后由服务端推送教程（HUD + 只读阶段 GUI）
                onClose();
                org.espetro.network.NetworkManager.sendTutorialReopen();
            }));
        root.addChild(EspetroAuiWidgets.button(x + (panelW - 140) / 2, y + 101, 140, 20,
            "关闭", this::onClose));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
