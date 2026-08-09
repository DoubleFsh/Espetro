package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.OutpostSupplySyncPacket;
import se.mickelus.mutil.gui.GuiElement;

/** 靠近前哨基地时的 Radio 血量、弹药/建材存量与兵站状态 HUD。 */
public final class OutpostSupplyHud {

    private static final int HUD_X = 8;
    private static final int HUD_Y = 34;
    private static final int PANEL_WIDTH = 158;
    private static final int PANEL_HEIGHT = 52;

    private static final ResourceLocation FOB_STATUS_ICON =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/fob_status.png");
    private static final ResourceLocation AMMO_ICON =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/ammo_supply.png");
    private static final ResourceLocation CONSTRUCTION_ICON =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/construction_supply.png");

    private static final int PANEL_BACKGROUND = 0xD0111111;
    private static final int PANEL_BORDER = 0xB05E646C;
    private static final int BAR_BACKGROUND = 0xFF333333;
    private static final int BAR_HEALTH = 0xFF4A9BFF;
    private static final int HAB_ENABLED_COLOR = 0xFF55AAFF;
    private static final int HAB_DISABLED_COLOR = 0xFFFF5555;

    private static boolean inRange;
    private static int radioHealth;
    private static int radioMaxHealth = 1;
    private static int ammunition;
    private static int construction;
    private static boolean habEnabled;
    private static OutpostSupplyElement activeElement;

    private OutpostSupplyHud() {
    }

    public static void update(OutpostSupplySyncPacket packet) {
        if (packet == null || !packet.isInRange()) {
            clear();
            return;
        }
        inRange = true;
        radioHealth = Math.max(0, packet.getRadioHealth());
        radioMaxHealth = Math.max(1, packet.getRadioMaxHealth());
        ammunition = Math.max(0, packet.getAmmunition());
        construction = Math.max(0, packet.getConstruction());
        habEnabled = packet.isHabEnabled();
        applyState();
    }

    public static void clear() {
        if (!inRange) return;
        inRange = false;
        applyState();
    }

    static GuiElement createElement() {
        if (activeElement != null) activeElement.dispose();
        activeElement = new OutpostSupplyElement();
        activeElement.applyState();
        return activeElement;
    }

    private static void applyState() {
        if (activeElement != null) activeElement.applyState();
    }

    private static final class OutpostSupplyElement extends GuiElement {

        private OutpostSupplyElement() {
            super(HUD_X, HUD_Y, PANEL_WIDTH, PANEL_HEIGHT);
        }

        private void applyState() {
            setVisible(inRange);
        }

        @Override
        public void draw(GuiGraphics graphics, int parentX, int parentY, int drawWidth,
                         int drawHeight, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) return;
            int bx = parentX + getX();
            int by = parentY + getY();
            int bw = getWidth();
            int bh = getHeight();

            graphics.fill(bx, by, bx + bw, by + bh, PANEL_BACKGROUND);
            graphics.renderOutline(bx, by, bw, bh, PANEL_BORDER);

            // 左上角 fob_status 图标 + 电台血量蓝条
            int iconSize = 12;
            int iconX = bx + 6;
            int iconY = by + 6;
            graphics.blit(FOB_STATUS_ICON, iconX, iconY,
                iconSize, iconSize, 0f, 0f, 16, 16, 16, 16);

            int barX = iconX + iconSize + 4;
            int barY = by + 9;
            int barW = bw - (barX - bx) - 6;
            int barH = 6;
            graphics.fill(barX, barY, barX + barW, barY + barH, BAR_BACKGROUND);
            int healthW = Math.max(0, (int) Math.round(
                (double) Math.min(radioHealth, radioMaxHealth) * barW / radioMaxHealth));
            if (healthW > 0) {
                graphics.fill(barX, barY, barX + healthW, barY + barH, BAR_HEALTH);
            }

            // 弹药 / 建材存量行
            int supplyY = by + 24;
            int supplyIconSize = 10;
            graphics.blit(AMMO_ICON, bx + 6, supplyY,
                supplyIconSize, supplyIconSize,
                0f, 0f, 16, 16, 16, 16);
            graphics.drawString(Minecraft.getInstance().font,
                Component.literal(String.valueOf(ammunition)),
                bx + 6 + supplyIconSize + 3, supplyY + 1, 0xFFFFFFFF, false);

            int constructionIconX = bx + 78;
            graphics.blit(CONSTRUCTION_ICON, constructionIconX, supplyY,
                supplyIconSize, supplyIconSize,
                0f, 0f, 16, 16, 16, 16);
            graphics.drawString(Minecraft.getInstance().font,
                Component.literal(String.valueOf(construction)),
                constructionIconX + supplyIconSize + 3, supplyY + 1, 0xFFFFFFFF, false);

            // 最底下一行：兵站是否启用
            int statusY = by + 40;
            if (habEnabled) {
                graphics.drawString(Minecraft.getInstance().font,
                    Component.literal("重生功能已启用"),
                    bx + 6, statusY, HAB_ENABLED_COLOR, false);
            } else {
                graphics.drawString(Minecraft.getInstance().font,
                    Component.literal("兵站未启用"),
                    bx + 6, statusY, HAB_DISABLED_COLOR, false);
            }

            super.draw(graphics, parentX, parentY, drawWidth, drawHeight,
                mouseX, mouseY, partialTick);
        }

        private void dispose() {
            // Direct-draw HUD keeps no textures to release.
        }
    }
}
