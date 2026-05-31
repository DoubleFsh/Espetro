package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.DeployPointSelectPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 复活点选择界面
 * 玩家死亡后打开，选择在原部署点或兵站复活
 */
public class DeployPointSelectScreen extends Screen {

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
    protected void init() {
        super.init();
        startX = (this.width - BUTTON_WIDTH) / 2;

        int y = START_Y;

        // 原部署点按钮
        if (hasDeployPoint) {
            String label = "§e原部署点 §7(" + deployPointPos + ")";
            Button deployBtn = Button.builder(Component.literal(label), btn -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.connection.sendCommand("bastion deploy");
                }
            }).bounds(startX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
            this.addRenderableWidget(deployBtn);
            y += BUTTON_HEIGHT + VERTICAL_SPACING;
        }

        // 兵站按钮
        for (DeployPointSelectPacket.BastionItem b : bastions) {
            String label = "§a" + b.name + " §7(" + b.pos + ")";
            Button btn = Button.builder(Component.literal(label), button -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.connection.sendCommand(
                        "bastion select " + b.id.toString());
                }
            }).bounds(startX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
            this.addRenderableWidget(btn);
            y += BUTTON_HEIGHT + VERTICAL_SPACING;
        }

        // 没有可选复活点
        if (!hasDeployPoint && bastions.isEmpty()) {
            this.addRenderableWidget(
                Button.builder(Component.literal("§c没有可用的复活点！"), btn -> {})
                    .bounds(startX, y, BUTTON_WIDTH, 30)
                    .build()
            );
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // 标题
        graphics.drawCenteredString(this.font,
            Component.literal("§6§l选择复活位置"),
            this.width / 2, 15, 0xFFFFFF);

        graphics.drawCenteredString(this.font,
            Component.literal("§e请选择你要复活的位置"),
            this.width / 2, 32, 0xAAAAAA);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // 死亡时不可关闭
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
