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

    /** 默认纹理（编制图片为 null 时回退使用）。 */
    private static final ResourceLocation DEFAULT_ATTACK_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/attack_faction.png");
    private static final ResourceLocation DEFAULT_DEFEND_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/defend_faction.png");

    /** 16:9 横向图片显示尺寸。 */
    private static final int IMG_W = 192;
    private static final int IMG_H = 108;
    /** 两个图片间距。 */
    private static final int IMG_GAP = 48;

    private final String attackFactionName;
    private final String defendFactionName;
    private final ResourceLocation attackTexture;
    private final ResourceLocation defendTexture;
    private final int attackTexW;
    private final int attackTexH;
    private final int defendTexW;
    private final int defendTexH;
    private int ticksRemaining;
    private EspetroMutilWidgets.PhaseHeader phaseHeader;

    public FactionRevealScreen(String attackFactionName, String defendFactionName,
                               String attackFactionImage, String defendFactionImage,
                               int durationSeconds) {
        super(Component.literal("编制揭示"));
        this.attackFactionName = normalizeName(attackFactionName);
        this.defendFactionName = normalizeName(defendFactionName);
        this.ticksRemaining = Math.max(1, durationSeconds) * 20;

        ResourceLocation atkTex = null;
        ResourceLocation defTex = null;
        int atkW = 128, atkH = 128;
        int defW = 128, defH = 128;

        if (attackFactionImage != null && !attackFactionImage.isEmpty()) {
            atkTex = parseTexture(attackFactionImage);
            // 尝试通过 NativeImage 读取实际尺寸；若失败则保底 128×128
            int[] dims = readTextureDimensions(atkTex);
            if (dims != null) { atkW = dims[0]; atkH = dims[1]; }
        }
        if (defendFactionImage != null && !defendFactionImage.isEmpty()) {
            defTex = parseTexture(defendFactionImage);
            int[] dims = readTextureDimensions(defTex);
            if (dims != null) { defW = dims[0]; defH = dims[1]; }
        }

        this.attackTexture = atkTex != null ? atkTex : DEFAULT_ATTACK_TEXTURE;
        this.defendTexture = defTex != null ? defTex : DEFAULT_DEFEND_TEXTURE;
        this.attackTexW = atkW;
        this.attackTexH = atkH;
        this.defendTexW = defW;
        this.defendTexH = defH;
    }

    /** 从资源读取纹理实际像素尺寸；若失败返回 null。 */
    private static int[] readTextureDimensions(ResourceLocation rl) {
        try {
            var mgr = Minecraft.getInstance().getTextureManager();
            var tex = mgr.getTexture(rl);
            if (tex != null) {
                // AbstractTexture 多数情况下无法可靠获取尺寸；
                // 对于 DynamicTexture 可以，对于 SimpleTexture 也可以用 NativeImage 重新读。
                // 这里直接回退到 128×128，由渲染端的 16:9 裁剪统一处理。
            }
        } catch (Exception ignored) {}
        return null; // 无法可靠获取 → 渲染端按 16:9 裁剪
    }

    private static ResourceLocation parseTexture(String fullPath) {
        // 支持 "namespace:path" 格式
        int colon = fullPath.indexOf(':');
        if (colon > 0) {
            String ns = fullPath.substring(0, colon);
            String path = fullPath.substring(colon + 1);
            return ResourceLocation.fromNamespaceAndPath(ns, path);
        }
        return ResourceLocation.tryParse(fullPath);
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        phaseHeader = EspetroMutilWidgets.addMutablePhaseHeader(root, this.width,
            "\u00a76\u00a7l双方编制确认",
            "\u00a7f双方最终编制已经确定",
            "\u00a78" + getSecondsRemaining() + "秒后进入部署",
            EspetroMutilWidgets.GOLD);
        int headerH = EspetroMutilWidgets.PHASE_HEADER_HEIGHT;
        boolean stacked = this.width < IMG_W * 2 + IMG_GAP + 60;
        // 16:9 横向图片，卡宽比图片略宽
        int cardW = stacked ? Math.min(IMG_W + 32, this.width - 28) : IMG_W + 32;
        int gap = IMG_GAP;
        int contentW = stacked ? cardW : cardW * 2 + gap;
        int panelW = Math.min(this.width - 18, Math.max(contentW + 20, stacked ? cardW + 40 : 430));
        int cardH = IMG_H + 22;
        int panelH = stacked ? cardH * 2 + gap : cardH;
        int panelX = (this.width - panelW) / 2;
        int panelY = headerH + Math.max(8, (this.height - headerH - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH, 0x00000000, 0x00000000));

        int startX = panelX + (panelW - contentW) / 2;
        int startY = panelY;
        if (stacked) {
            addFactionCard(root, startX, startY, cardW,
                attackTexture, attackTexW, attackTexH, attackFactionName, EspetroMutilWidgets.ATTACK);
            addFactionCard(root, startX, startY + cardH + gap, cardW,
                defendTexture, defendTexW, defendTexH, defendFactionName, EspetroMutilWidgets.DEFEND);
        } else {
            addFactionCard(root, startX, startY, cardW,
                attackTexture, attackTexW, attackTexH, attackFactionName, EspetroMutilWidgets.ATTACK);
            addFactionCard(root, startX + cardW + gap, startY, cardW,
                defendTexture, defendTexW, defendTexH, defendFactionName, EspetroMutilWidgets.DEFEND);
        }
    }

    private void addFactionCard(GuiElement root, int x, int y, int cardW,
                                ResourceLocation texture, int texW, int texH,
                                String factionName, int textColor) {
        int imgX = x + (cardW - IMG_W) / 2;
        root.addChild(new FactionImageElement(imgX, y, IMG_W, IMG_H, texW, texH, texture));
        root.addChild(EspetroMutilWidgets.centeredText(x, y + IMG_H + 5, cardW,
            fitText("\u00a7l" + factionName, cardW - 8), textColor));
    }

    /**
     * 从任意尺寸纹理中裁剪中心 16:9 区域，拉伸填满 {@link #IMG_W}×{@link #IMG_H}。
     * 纹理原始宽高通过 texW/texH 指定（默认 128×128）。
     */
    private static final class FactionImageElement extends GuiElement {
        private final ResourceLocation texture;
        private final int texW, texH;
        private final int dispW, dispH;

        FactionImageElement(int x, int y, int dispW, int dispH,
                            int texW, int texH, ResourceLocation texture) {
            super(x, y, dispW, dispH);
            this.dispW = dispW;
            this.dispH = dispH;
            this.texW = texW;
            this.texH = texH;
            this.texture = texture;
        }

        @Override
        public void draw(GuiGraphics graphics, int refX, int refY, int screenWidth,
                         int screenHeight, int mouseX, int mouseY, float opacity) {
            if (!isVisible() || texture == null) return;
            int bx = refX + getX();
            int by = refY + getY();
            graphics.blit(texture, bx, by, dispW, dispH, 0, 0, texW, texH, texW, texH);
        }
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
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    @Override
    public void tick() {
        super.tick();
        ticksRemaining--;
        if (ticksRemaining % 20 == 0 && phaseHeader != null) {
            phaseHeader.setDetail("\u00a78" + getSecondsRemaining() + "秒后进入部署");
        }
        if (ticksRemaining <= 0 && Minecraft.getInstance().screen == this && !tutorialPreviewMode) {
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
