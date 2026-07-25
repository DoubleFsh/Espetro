package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.espetro.network.VehicleDeployScreenPacket;
import se.mickelus.mutil.gui.GuiElement;

import java.util.ArrayList;
import java.util.List;

/**
 * 载具部署界面。
 * 指挥官右键部署棍时打开，列出可用载具及状态。
 */
public class VehicleDeployScreen extends MutilScreen {

    private static final int PANEL_WIDTH = 560;
    private static final int PANEL_MIN_WIDTH = 300;
    private static final int PANEL_MARGIN = 28;
    private static final int HEADER_H = 46;
    private static final int PANEL_PADDING = 8;
    private static final int ROW_H = 30;
    private static final int ROW_GAP = 6;
    private static final int SCROLLBAR_RESERVED_W = 8;

    private static final int PANEL_BG = 0xB014171E;
    private static final int ROW_READY = 0x80303D32;
    private static final int ROW_READY_HOVER = 0xA03B4F3A;
    private static final int ROW_BLOCKED = 0x7040444A;
    private static final int ROW_BORDER = 0x704E5360;

    private final List<VehicleDeployScreenPacket.VehicleInfo> vehicles;

    public VehicleDeployScreen(List<VehicleDeployScreenPacket.VehicleInfo> vehicles) {
        super(Component.literal("载具部署"));
        this.vehicles = vehicles != null ? vehicles : new ArrayList<>();
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        int panelW = Math.min(PANEL_WIDTH, Math.max(PANEL_MIN_WIDTH, this.width - PANEL_MARGIN * 2));
        int listContentH = vehicles.isEmpty()
            ? ROW_H
            : vehicles.size() * ROW_H + Math.max(0, vehicles.size() - 1) * ROW_GAP;
        int maxListH = Math.max(ROW_H, this.height - HEADER_H - PANEL_PADDING * 2 - 36);
        int listH = Math.min(listContentH, maxListH);
        int panelH = HEADER_H + listH + PANEL_PADDING * 2;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(8, (this.height - panelH) / 2 - 18);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH, PANEL_BG, EspetroMutilWidgets.BORDER));
        root.addChild(EspetroMutilWidgets.centeredText(
            panelX, panelY + 12, panelW, "\u00a76\u00a7l载具部署面板", EspetroMutilWidgets.GOLD));
        root.addChild(EspetroMutilWidgets.centeredText(
            panelX, panelY + 29, panelW, "\u00a77载具将在预设位置出现", EspetroMutilWidgets.MUTED));
        root.addChild(EspetroMutilWidgets.rect(
            panelX + PANEL_PADDING, panelY + HEADER_H - 3, panelW - PANEL_PADDING * 2, 1, 0x35FFFFFF));

        int listX = panelX + PANEL_PADDING;
        int listY = panelY + HEADER_H + PANEL_PADDING;
        int listW = panelW - PANEL_PADDING * 2;
        ScrollableList list = new ScrollableList(listX, listY, listW, listH)
            .setScrollStep(ROW_H + ROW_GAP)
            .setAlwaysShowScrollbar(true);
        root.addChild(list);

        if (vehicles.isEmpty()) {
            list.addChild(vehicleButton(0, 0, listW, "\u00a7c当前编制无载具配置", false, null));
            return;
        }

        int y = 0;
        for (VehicleDeployScreenPacket.VehicleInfo vehicle : vehicles) {
            boolean enabled = vehicle.cooldownRemaining <= 0 && vehicle.current < vehicle.max;
            String label = buildVehicleLabel(vehicle, enabled);
            list.addChild(vehicleButton(0, y, listW, label, enabled, () -> deployVehicle(vehicle.type)));
            y += ROW_H + ROW_GAP;
        }
    }

    private EspetroMutilWidgets.ActionButton vehicleButton(
        int x,
        int y,
        int width,
        String label,
        boolean enabled,
        Runnable action
    ) {
        return EspetroMutilWidgets.button(x, y, width - SCROLLBAR_RESERVED_W, ROW_H, label, action)
            .setEnabled(enabled)
            .setColors(ROW_READY, ROW_READY_HOVER, ROW_READY_HOVER)
            .setDisabledColor(ROW_BLOCKED)
            .setBorderColor(ROW_BORDER)
            .setTextColor(enabled ? EspetroMutilWidgets.TEXT : EspetroMutilWidgets.DIM);
    }

    private static String buildVehicleLabel(VehicleDeployScreenPacket.VehicleInfo vehicle, boolean enabled) {
        String status;
        if (vehicle.cooldownRemaining > 0) {
            status = "\u00a7c冷却 " + vehicle.cooldownRemaining + "秒";
        } else if (vehicle.current >= vehicle.max) {
            status = "\u00a76已满 " + vehicle.current + "/" + vehicle.max;
        } else {
            status = "\u00a7a就绪 " + vehicle.current + "/" + vehicle.max;
        }

        String nameColor = enabled ? "\u00a7e" : "\u00a78";
        return nameColor + vehicle.displayName + "  " + status
            + "  \u00a77(" + vehicle.respawnMinutes + "分钟刷新)";
    }

    private static void deployVehicle(String type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.connection.sendCommand("vehicle spawn " + quoteCommandString(type));
        }
    }

    private static String quoteCommandString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
