package org.espetro.client.gui;

import net.minecraft.network.chat.Component;
import se.mickelus.mutil.gui.GuiElement;

/** Peaceful overworld hub menu. Readiness was intentionally removed. */
public final class HubScreen extends MutilScreen {
    private int onlineCount;
    private String status;
    private EspetroMutilWidgets.Text onlineText;
    private EspetroMutilWidgets.Text statusText;

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
    protected void buildMutilRoot(GuiElement root) {
        int panelW = Math.min(260, width - 24);
        int panelH = 126;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;
        // 主城可保留小面板（非失明场景），无全屏遮罩。
        root.addChild(EspetroMutilWidgets.panel(x, y, panelW, panelH, 0xE0191C1E, EspetroMutilWidgets.BORDER));
        root.addChild(EspetroMutilWidgets.centeredText(x + 8, y + 13, panelW - 16,
            "§6§lEspetro 主城", EspetroMutilWidgets.GOLD));
        onlineText = EspetroMutilWidgets.centeredText(x + 8, y + 37, panelW - 16,
            "§e在线人数：§f" + onlineCount, EspetroMutilWidgets.TEXT);
        statusText = EspetroMutilWidgets.centeredText(x + 8, y + 52, panelW - 16,
            status, EspetroMutilWidgets.MUTED);
        root.addChild(onlineText);
        root.addChild(statusText);
        root.addChild(EspetroMutilWidgets.button(x + (panelW - 140) / 2, y + 76, 140, 20,
            "进入新手教程",
            () -> {
                // 关闭主城菜单后由服务端推送教程（HUD + 只读阶段 GUI）
                onClose();
                org.espetro.network.NetworkManager.sendTutorialReopen();
            }));
        root.addChild(EspetroMutilWidgets.button(x + (panelW - 140) / 2, y + 101, 140, 20,
            "关闭", this::onClose));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
