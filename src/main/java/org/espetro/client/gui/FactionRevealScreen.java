package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import se.mickelus.mutil.gui.GuiElement;

import java.util.Objects;

/**
 * 双方最终编制揭示界面。
 */
public class FactionRevealScreen extends MutilScreen {

    private static final ResourceLocation ATTACK_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/attack_faction.png");
    private static final ResourceLocation DEFEND_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/defend_faction.png");

    private static final int ATTACK_TEX_W = 105;
    private static final int ATTACK_TEX_H = 81;
    private static final int DEFEND_TEX_W = 102;
    private static final int DEFEND_TEX_H = 79;

    private final String attackFactionName;
    private final String defendFactionName;
    private int ticksRemaining;
    private EspetroMutilWidgets.PhaseHeader phaseHeader;

    public FactionRevealScreen(String attackFactionName, String defendFactionName, int durationSeconds) {
        super(Component.literal("编制揭示"));
        this.attackFactionName = normalizeName(attackFactionName);
        this.defendFactionName = normalizeName(defendFactionName);
        this.ticksRemaining = Math.max(1, durationSeconds) * 20;
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        phaseHeader = EspetroMutilWidgets.addMutablePhaseHeader(root, this.width,
            "\u00a76\u00a7l双方编制确认",
            "\u00a7f双方最终编制已经确定",
            "\u00a78" + getSecondsRemaining() + "秒后进入部署",
            EspetroMutilWidgets.GOLD);
        int headerH = EspetroMutilWidgets.PHASE_HEADER_HEIGHT;
        boolean stacked = this.width < 430;
        int imgMax = stacked ? 82 : 118;
        int cardW = stacked ? Math.min(190, this.width - 28) : 178;
        int gap = stacked ? 18 : 42;
        int contentW = stacked ? cardW : cardW * 2 + gap;
        int panelW = Math.min(this.width - 18, Math.max(contentW + 20, stacked ? 220 : 430));
        int cardH = imgMax + 22;
        int panelH = stacked ? cardH * 2 + gap : cardH;
        int panelX = (this.width - panelW) / 2;
        int panelY = headerH + Math.max(8, (this.height - headerH - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH, 0x00000000, 0x00000000));

        int startX = panelX + (panelW - contentW) / 2;
        int startY = panelY;
        if (stacked) {
            addFactionCard(root, startX, startY, cardW, imgMax,
                ATTACK_TEXTURE, ATTACK_TEX_W, ATTACK_TEX_H, attackFactionName, EspetroMutilWidgets.ATTACK);
            addFactionCard(root, startX, startY + cardH + gap, cardW, imgMax,
                DEFEND_TEXTURE, DEFEND_TEX_W, DEFEND_TEX_H, defendFactionName, EspetroMutilWidgets.DEFEND);
        } else {
            addFactionCard(root, startX, startY, cardW, imgMax,
                ATTACK_TEXTURE, ATTACK_TEX_W, ATTACK_TEX_H, attackFactionName, EspetroMutilWidgets.ATTACK);
            addFactionCard(root, startX + cardW + gap, startY, cardW, imgMax,
                DEFEND_TEXTURE, DEFEND_TEX_W, DEFEND_TEX_H, defendFactionName, EspetroMutilWidgets.DEFEND);
        }
    }

    private void addFactionCard(GuiElement root, int x, int y, int cardW, int imgMax,
                                ResourceLocation texture, int texW, int texH,
                                String factionName, int textColor) {
        int imgX = x + (cardW - imgMax) / 2;
        root.addChild(new EspetroMutilWidgets.ImageButton(imgX, y, imgMax, imgMax, texW, texH, texture, null)
            .setBorderColor(0x00000000)
            .setHoverBorderColor(0x00000000));
        root.addChild(EspetroMutilWidgets.centeredText(x, y + imgMax + 5, cardW,
            fitText("\u00a7l" + factionName, cardW - 8), textColor));
    }

    private String fitText(String text, int maxWidth) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font.width(EspetroMutilWidgets.stripFormatting(text)) <= maxWidth) {
            return text;
        }

        String plain = EspetroMutilWidgets.stripFormatting(text);
        return mc.font.plainSubstrByWidth(plain, Math.max(0, maxWidth - mc.font.width("..."))) + "...";
    }

    private int getSecondsRemaining() {
        return Math.max(0, (ticksRemaining + 19) / 20);
    }

    public boolean matches(String attackName, String defendName) {
        return Objects.equals(attackFactionName, normalizeName(attackName))
            && Objects.equals(defendFactionName, normalizeName(defendName));
    }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xD03A3A3A);
    }

    @Override
    public void tick() {
        ticksRemaining--;
        if (ticksRemaining % 20 == 0 && phaseHeader != null) {
            phaseHeader.setDetail("\u00a78" + getSecondsRemaining() + "秒后进入部署");
        }
        if (ticksRemaining <= 0 && Minecraft.getInstance().screen == this) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String normalizeName(String value) {
        return value == null || value.isEmpty() ? "未确定" : value;
    }
}
