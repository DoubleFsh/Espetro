package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.VehicleDeployScreenPacket;
import org.espetro.network.NetworkManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 载具部署界面
 * 指挥官右键部署棍时打开，列出可用载具及状态
 */
public class VehicleDeployScreen extends Screen {

    private final List<VehicleDeployScreenPacket.VehicleInfo> vehicles;

    private static final int BUTTON_WIDTH = 260;
    private static final int BUTTON_HEIGHT = 28;
    private static final int VERTICAL_SPACING = 6;
    private static final int START_Y = 45;
    private int startX;

    public VehicleDeployScreen(List<VehicleDeployScreenPacket.VehicleInfo> vehicles) {
        super(Component.literal("载具部署"));
        this.vehicles = vehicles != null ? vehicles : new ArrayList<>();
    }

    @Override
    protected void init() {
        super.init();
        startX = (this.width - BUTTON_WIDTH) / 2;

        if (vehicles.isEmpty()) {
            this.addRenderableWidget(
                Button.builder(Component.literal("§c当前编制无载具配置"), btn -> {})
                    .bounds(startX, START_Y + 10, BUTTON_WIDTH, 30)
                    .build()
            );
            return;
        }

        int y = START_Y;
        for (VehicleDeployScreenPacket.VehicleInfo v : vehicles) {
            String status;
            boolean enabled;
            if (v.cooldownRemaining > 0) {
                status = "§c冷却 " + v.cooldownRemaining + "秒";
                enabled = false;
            } else if (v.current >= v.max) {
                status = "§6已满 " + v.current + "/" + v.max;
                enabled = false;
            } else {
                status = "§a就绪 " + v.current + "/" + v.max;
                enabled = true;
            }

            String label = v.displayName + "  " + status + "  §7(" + v.respawnMinutes + "分钟刷新)";
            Button button = Button.builder(Component.literal(label), btn -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.connection.sendCommand("vehicle spawn " + v.type);
                }
            }).bounds(startX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
            button.active = enabled;

            this.addRenderableWidget(button);
            y += BUTTON_HEIGHT + VERTICAL_SPACING;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // 标题
        graphics.drawCenteredString(this.font,
            Component.literal("§6§l载具部署面板"),
            this.width / 2, 12, 0xFFFFFF);

        graphics.drawCenteredString(this.font,
            Component.literal("§7载具将部署在原部署点附近"),
            this.width / 2, 27, 0xAAAAAA);

        if (vehicles.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal("§c当前编制无载具配置"),
                this.width / 2, 60, 0xFF5555);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
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
