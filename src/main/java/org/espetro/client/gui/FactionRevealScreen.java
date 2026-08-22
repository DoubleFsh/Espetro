package org.espetro.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.client.aui.GuiElement;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 双方最终编制揭示界面。
 */
public class FactionRevealScreen extends EspetroMenuScreen {

    /** 默认纹理（编制图片为 null 时回退使用）。 */
    private static final ResourceLocation DEFAULT_ATTACK_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/attack_faction.png");
    private static final ResourceLocation DEFAULT_DEFEND_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/defend_faction.png");

    /** 已解析的资源包/客户端本地编制图片，避免界面重建时重复解码。 */
    private static final Map<String, ResolvedTexture> TEXTURE_CACHE = new HashMap<>();

    private record ResolvedTexture(ResourceLocation location, int width, int height) {
    }

    /** 图片可用区域；实际绘制尺寸始终按源图片比例适配。 */
    private static final int IMG_MAX_W = 192;
    private static final int IMG_MAX_H = 108;
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
    private EspetroAuiWidgets.PhaseHeader phaseHeader;

    public FactionRevealScreen(String attackFactionName, String defendFactionName,
                               String attackFactionImage, String defendFactionImage,
                               int durationSeconds) {
        super(Component.literal("编制揭示"));
        this.attackFactionName = normalizeName(attackFactionName);
        this.defendFactionName = normalizeName(defendFactionName);
        this.ticksRemaining = Math.max(1, durationSeconds) * 20;

        ResolvedTexture attack = resolveTexture(attackFactionImage);
        ResolvedTexture defend = resolveTexture(defendFactionImage);
        if (attack == null) attack = resolveResourceTexture(DEFAULT_ATTACK_TEXTURE);
        if (defend == null) defend = resolveResourceTexture(DEFAULT_DEFEND_TEXTURE);

        this.attackTexture = attack != null ? attack.location() : DEFAULT_ATTACK_TEXTURE;
        this.defendTexture = defend != null ? defend.location() : DEFAULT_DEFEND_TEXTURE;
        this.attackTexW = attack != null ? attack.width() : 128;
        this.attackTexH = attack != null ? attack.height() : 128;
        this.defendTexW = defend != null ? defend.width() : 128;
        this.defendTexH = defend != null ? defend.height() : 128;
    }

    /** 先解析资源包位置，再回退到客户端根目录 EsFactions 下的相对图片。 */
    private static ResolvedTexture resolveTexture(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return null;
        String key = configuredPath.trim();
        ResolvedTexture cached = TEXTURE_CACHE.get(key);
        if (cached != null) return cached;

        ResourceLocation resourceLocation = ResourceLocation.tryParse(key);
        ResolvedTexture resource = resolveResourceTexture(resourceLocation);
        if (resource != null) {
            TEXTURE_CACHE.put(key, resource);
            return resource;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        Path imagePath = FactionSelectionImageResolver.resolveClientFile(
            mc.gameDirectory.toPath(), key);
        if (imagePath == null) return null;

        try (InputStream in = Files.newInputStream(imagePath)) {
            NativeImage image = NativeImage.read(in);
            int width = image.getWidth();
            int height = image.getHeight();
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation dynamicLocation = ResourceLocation.fromNamespaceAndPath(
                "espetro", "dynamic/faction_reveal_" + Integer.toHexString(key.hashCode()));
            mc.getTextureManager().register(dynamicLocation, texture);
            ResolvedTexture resolved = new ResolvedTexture(dynamicLocation, width, height);
            TEXTURE_CACHE.put(key, resolved);
            return resolved;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 从资源包读取纹理实际像素尺寸；读取完成立即释放临时像素数据。 */
    private static ResolvedTexture resolveResourceTexture(ResourceLocation location) {
        if (location == null) return null;
        String cacheKey = "resource:" + location;
        ResolvedTexture cached = TEXTURE_CACHE.get(cacheKey);
        if (cached != null) return cached;
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isEmpty()) return null;
            try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                ResolvedTexture resolved = new ResolvedTexture(
                    location, image.getWidth(), image.getHeight());
                TEXTURE_CACHE.put(cacheKey, resolved);
                return resolved;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    protected void buildMenuRoot(GuiElement root) {
        phaseHeader = EspetroAuiWidgets.addMutablePhaseHeader(root, this.width,
            "\u00a76\u00a7l双方编制确认",
            "\u00a7f双方最终编制已经确定",
            "\u00a78" + getSecondsRemaining() + "秒后进入部署",
            EspetroAuiWidgets.GOLD);
        int headerH = EspetroAuiWidgets.PHASE_HEADER_HEIGHT;
        boolean stacked = this.width < IMG_MAX_W * 2 + IMG_GAP + 60;
        int cardW = stacked ? Math.min(IMG_MAX_W + 32, this.width - 28) : IMG_MAX_W + 32;
        int gap = IMG_GAP;
        int contentW = stacked ? cardW : cardW * 2 + gap;
        int panelW = Math.min(this.width - 18, Math.max(contentW + 20, stacked ? cardW + 40 : 430));
        int cardH = IMG_MAX_H + 22;
        int panelH = stacked ? cardH * 2 + gap : cardH;
        int panelX = (this.width - panelW) / 2;
        int panelY = headerH + Math.max(8, (this.height - headerH - panelH) / 2);

        root.addChild(EspetroAuiWidgets.panel(panelX, panelY, panelW, panelH, 0x00000000, 0x00000000));

        int startX = panelX + (panelW - contentW) / 2;
        int startY = panelY;
        if (stacked) {
            addFactionCard(root, startX, startY, cardW,
                attackTexture, attackTexW, attackTexH, attackFactionName, EspetroAuiWidgets.ATTACK);
            addFactionCard(root, startX, startY + cardH + gap, cardW,
                defendTexture, defendTexW, defendTexH, defendFactionName, EspetroAuiWidgets.DEFEND);
        } else {
            addFactionCard(root, startX, startY, cardW,
                attackTexture, attackTexW, attackTexH, attackFactionName, EspetroAuiWidgets.ATTACK);
            addFactionCard(root, startX + cardW + gap, startY, cardW,
                defendTexture, defendTexW, defendTexH, defendFactionName, EspetroAuiWidgets.DEFEND);
        }
    }

    private void addFactionCard(GuiElement root, int x, int y, int cardW,
                                ResourceLocation texture, int texW, int texH,
                                String factionName, int textColor) {
        AspectFit.Size fitted = AspectFit.within(texW, texH, IMG_MAX_W, IMG_MAX_H);
        int imgX = x + (cardW - fitted.width()) / 2;
        int imgY = y + (IMG_MAX_H - fitted.height()) / 2;
        root.addChild(new FactionImageElement(imgX, imgY, fitted.width(), fitted.height(),
            texW, texH, texture));
        root.addChild(EspetroAuiWidgets.centeredText(x, y + IMG_MAX_H + 5, cardW,
            fitText("\u00a7l" + factionName, cardW - 8), textColor));
    }

    /** Draws the complete texture at an aspect-fitted size without cropping or stretching. */
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
        if (mc.font.width(EspetroAuiWidgets.stripFormatting(text)) <= maxWidth) {
            return text;
        }

        String plain = EspetroAuiWidgets.stripFormatting(text);
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
    protected void renderBeforeMenu(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroAuiWidgets.drawScreenShade(graphics, this.width, this.height);
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
