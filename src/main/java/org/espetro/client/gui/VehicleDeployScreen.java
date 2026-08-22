package org.espetro.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.espetro.network.VehicleDeployScreenPacket;
import org.espetro.client.aui.GuiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 载具部署界面：结构不变时原地刷新冷却/在场数，避免整页 rebuild 闪烁。
 */
public class VehicleDeployScreen extends EspetroMenuScreen {

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

    private List<VehicleDeployScreenPacket.VehicleInfo> vehicles;
    private final List<RowBinding> rowBindings = new ArrayList<>();
    private Object structureSignature;

    private static final class RowBinding {
        private final EspetroAuiWidgets.ActionButton button;
        private VehicleDeployScreenPacket.VehicleInfo info;

        private RowBinding(EspetroAuiWidgets.ActionButton button,
                           VehicleDeployScreenPacket.VehicleInfo info) {
            this.button = button;
            this.info = info;
        }
    }

    public VehicleDeployScreen(List<VehicleDeployScreenPacket.VehicleInfo> vehicles) {
        super(Component.literal("载具信息"));
        this.vehicles = vehicles != null ? new ArrayList<>(vehicles) : new ArrayList<>();
        this.structureSignature = signatureOf(this.vehicles);
    }

    /** 服务端推送刷新：类型列表变则 rebuild，否则原地改文案。 */
    public void updateFromPacket(List<VehicleDeployScreenPacket.VehicleInfo> next) {
        List<VehicleDeployScreenPacket.VehicleInfo> list =
            next != null ? new ArrayList<>(next) : new ArrayList<>();
        Object sig = signatureOf(list);
        this.vehicles = list;
        if (!Objects.equals(structureSignature, sig)) {
            structureSignature = sig;
            rebuildMenuRoot();
        } else {
            for (int i = 0; i < rowBindings.size() && i < list.size(); i++) {
                rowBindings.get(i).info = list.get(i);
            }
            refreshRows();
        }
    }

    private static Object signatureOf(List<VehicleDeployScreenPacket.VehicleInfo> list) {
        StringBuilder sb = new StringBuilder();
        for (VehicleDeployScreenPacket.VehicleInfo v : list) {
            sb.append(v.type).append('|');
        }
        return sb.toString();
    }

    @Override
    protected void buildMenuRoot(GuiElement root) {
        rowBindings.clear();
        int panelW = Math.min(PANEL_WIDTH, Math.max(PANEL_MIN_WIDTH, this.width - PANEL_MARGIN * 2));
        int listContentH = vehicles.isEmpty()
            ? ROW_H
            : vehicles.size() * ROW_H + Math.max(0, vehicles.size() - 1) * ROW_GAP;
        int maxListH = Math.max(ROW_H, this.height - HEADER_H - PANEL_PADDING * 2 - 36);
        int listH = Math.min(listContentH, maxListH);
        int panelH = HEADER_H + listH + PANEL_PADDING * 2;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(8, (this.height - panelH) / 2 - 18);

        root.addChild(EspetroAuiWidgets.panel(panelX, panelY, panelW, panelH, PANEL_BG, EspetroAuiWidgets.BORDER));
        root.addChild(EspetroAuiWidgets.centeredText(
            panelX, panelY + 12, panelW, "\u00a76\u00a7l载具信息", EspetroAuiWidgets.GOLD));
        root.addChild(EspetroAuiWidgets.centeredText(
            panelX, panelY + 29, panelW, "\u00a77冷却与在场数量实时更新", EspetroAuiWidgets.MUTED));
        root.addChild(EspetroAuiWidgets.rect(
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
            int remaining = computeRemainingSeconds(vehicle);
            String label = buildVehicleLabel(vehicle, remaining, false);
            var btn = vehicleButton(0, y, listW, label, false, null);
            list.addChild(btn);
            rowBindings.add(new RowBinding(btn, vehicle));
            y += ROW_H + ROW_GAP;
        }
    }

    private void refreshRows() {
        for (RowBinding row : rowBindings) {
            int remaining = computeRemainingSeconds(row.info);
            row.button.setLabel(buildVehicleLabel(row.info, remaining, false));
            row.button.setEnabled(false);
        }
    }

    private int computeRemainingSeconds(VehicleDeployScreenPacket.VehicleInfo vehicle) {
        long remaining = Math.max(0L, vehicle.readyAtEpochMs - System.currentTimeMillis());
        return (int) Math.min(Integer.MAX_VALUE, (remaining + 999L) / 1000L);
    }

    private EspetroAuiWidgets.ActionButton vehicleButton(
        int x, int y, int width, String label, boolean enabled, Runnable action
    ) {
        return EspetroAuiWidgets.button(x, y, width - SCROLLBAR_RESERVED_W, ROW_H, label, action)
            .setEnabled(enabled)
            .setColors(ROW_READY, ROW_READY_HOVER, ROW_READY_HOVER)
            .setDisabledColor(ROW_BLOCKED)
            .setBorderColor(ROW_BORDER)
            .setTextColor(enabled ? EspetroAuiWidgets.TEXT : EspetroAuiWidgets.DIM);
    }

    private static String buildVehicleLabel(VehicleDeployScreenPacket.VehicleInfo vehicle,
                                            int remaining, boolean enabled) {
        String status;
        if (remaining > 0) {
            status = "\u00a7c冷却 " + remaining + "秒";
        } else if (vehicle.current >= vehicle.max) {
            status = "\u00a76已满 " + vehicle.current + "/" + vehicle.max;
        } else {
            status = "\u00a7a就绪 " + vehicle.current + "/" + vehicle.max;
        }
        String nameColor = remaining > 0 || vehicle.current >= vehicle.max
            ? "\u00a78" : "\u00a7e";
        return nameColor + vehicle.displayName + "  " + status
            + "  \u00a77(" + vehicle.respawnMinutes + "分钟刷新)";
    }

    @Override
    public void tick() {
        super.tick();
        if (onceEverySecond()) {
            refreshRows();
        }
    }

    @Override
    protected void renderBeforeMenu(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroAuiWidgets.drawScreenShade(graphics, this.width, this.height);
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
