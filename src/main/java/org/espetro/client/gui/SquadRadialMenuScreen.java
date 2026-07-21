package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.Espetro;
import org.espetro.network.NetworkManager;
import org.espetro.network.RadialActionPacket;

public class SquadRadialMenuScreen extends Screen {

    private static final ResourceLocation RADIO =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/radio.png");
    private static final ResourceLocation RALLY =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/rally.png");
    private static final ResourceLocation CONSTRUCTION =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/construction_supply.png");
    private static final ResourceLocation AMMO =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/ammo_supply.png");

    private RadialActionPacket.Action selected;

    public SquadRadialMenuScreen() {
        super(Component.literal("战术交互"));
    }

    @Override
    public void tick() {
        super.tick();
        if (Espetro.KEY_RADIAL instanceof net.minecraft.client.KeyMapping key && !key.isDown()) {
            if (selected != null) {
                NetworkManager.sendRadialAction(selected);
            }
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        int centerY = height / 2;
        double dx = mouseX - centerX;
        double dy = mouseY - centerY;
        selected = select(dx, dy);

        graphics.fill(0, 0, width, height, 0x66000000);
        drawOption(graphics, centerX, centerY - 64, RADIO, 128, "部署 Radio",
            RadialActionPacket.Action.DEPLOY_RADIO);
        drawOption(graphics, centerX + 72, centerY, RALLY, 256, "部署 Rally",
            RadialActionPacket.Action.DEPLOY_RALLY);
        drawOption(graphics, centerX, centerY + 64, CONSTRUCTION, 128, "存入补给",
            RadialActionPacket.Action.DEPOSIT_SUPPLIES);
        drawOption(graphics, centerX - 72, centerY, AMMO, 128, "FOB 状态",
            RadialActionPacket.Action.FOB_STATUS);
        graphics.drawCenteredString(font, selected == null ? "移动鼠标选择" : label(selected),
            centerX, centerY - 5, 0xFFFFFFFF);
    }

    private void drawOption(GuiGraphics graphics, int x, int y, ResourceLocation icon,
                            int textureWidth, String label, RadialActionPacket.Action action) {
        boolean active = selected == action;
        int half = active ? 23 : 20;
        graphics.fill(x - half - 3, y - half - 3, x + half + 3, y + half + 3,
            active ? 0xE04B5D4B : 0xD0181A1D);
        graphics.renderOutline(x - half - 3, y - half - 3,
            (half + 3) * 2, (half + 3) * 2, active ? 0xFFFFFFFF : 0xFF777B80);
        RenderSystem.setShaderColor(1f, 1f, 1f, active ? 1f : 0.8f);
        graphics.blit(icon, x - half, y - half, half * 2, half * 2,
            0f, 0f, 128, 128, textureWidth, 128);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        graphics.drawCenteredString(font, label, x, y + half + 6,
            active ? 0xFFFFFFFF : 0xFFBFC3C7);
    }

    private RadialActionPacket.Action select(double dx, double dy) {
        if (dx * dx + dy * dy < 25 * 25) return null;
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx > 0 ? RadialActionPacket.Action.DEPLOY_RALLY
                : RadialActionPacket.Action.FOB_STATUS;
        }
        return dy > 0 ? RadialActionPacket.Action.DEPOSIT_SUPPLIES
            : RadialActionPacket.Action.DEPLOY_RADIO;
    }

    private String label(RadialActionPacket.Action action) {
        return switch (action) {
            case DEPLOY_RADIO -> "部署 Radio";
            case DEPLOY_RALLY -> "部署 Rally";
            case DEPOSIT_SUPPLIES -> "存入补给";
            case FOB_STATUS -> "查看 FOB 状态";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
