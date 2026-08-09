package org.espetro.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.espetro.Espetro;
import org.espetro.network.FobSupplySyncPacket;
import se.mickelus.mutil.gui.GuiElement;

import java.io.IOException;

/**
 * Radio 范围补给状态缓存与 MUtil HUD 元素。
 *
 * <p>关键信息变化时，整个面板在 CPU 侧重新生成并上传成一张动态纹理；
 * 相同数据不会重建或上传。普通帧只由一个 MUtil 元素提交已经生成的纹理。</p>
 */
public final class FobSupplyHud {

    private static final int HUD_X = 8;
    private static final int HUD_Y = 8;
    private static final int PANEL_WIDTH = 104;
    private static final int PANEL_HEIGHT = 22;
    private static final int BAR_Y = 3;
    private static final int BAR_HEIGHT = 3;
    private static final int BAR_X = 4;
    private static final int BAR_WIDTH = 96;
    private static final int HALF_BAR_WIDTH = BAR_WIDTH / 2;
    private static final int AMMO_BAR_X = 4;
    private static final int CONSTRUCTION_BAR_X = AMMO_BAR_X + HALF_BAR_WIDTH;
    private static final int ICON_SIZE = 10;

    private static final int PANEL_BACKGROUND = 0xD0111111;
    private static final int PANEL_BORDER = 0xB05E646C;
    private static final int BAR_BACKGROUND = 0xFF333333;
    private static final int BAR_HEALTH_COLOR = 0xFF4A9BFF;
    private static final int AMMO_COLOR = 0xFFCC4444;
    private static final int CONSTRUCTION_COLOR = 0xFFD6B300;

    private static final ResourceLocation AMMO_ICON = ResourceLocation.fromNamespaceAndPath(
        "espetro", "textures/gui/squad/ammo_supply.png");
    private static final ResourceLocation CONSTRUCTION_ICON = ResourceLocation.fromNamespaceAndPath(
        "espetro", "textures/gui/squad/construction_supply.png");
    private static final ResourceLocation VANILLA_ASCII_FONT = ResourceLocation.fromNamespaceAndPath(
        "minecraft", "textures/font/ascii.png");

    private static boolean inRange;
    private static int construction;
    private static int ammunition;
    private static int maxConstruction = 1;
    private static int maxAmmunition = 1;
    private static int radioHealth;
    private static int radioMaxHealth = 1;
    private static SupplyElement activeElement;
    private static long stateRevision;

    private FobSupplyHud() {
    }

    /** Kept for binary/source compatibility; rendering is owned by {@link MutilHudOverlay}. */
    public static void register() {
    }

    public static void update(FobSupplySyncPacket packet) {
        if (packet == null || !packet.isInRange()) {
            clear();
            return;
        }
        int nextConstruction = Math.max(0, packet.getConstruction());
        int nextAmmunition = Math.max(0, packet.getAmmunition());
        int nextMaxConstruction = Math.max(1, packet.getMaxConstruction());
        int nextMaxAmmunition = Math.max(1, packet.getMaxAmmunition());
        int nextRadioHealth = Math.max(0, packet.getRadioHealth());
        int nextRadioMaxHealth = Math.max(1, packet.getRadioMaxHealth());
        if (inRange
            && construction == nextConstruction
            && ammunition == nextAmmunition
            && maxConstruction == nextMaxConstruction
            && maxAmmunition == nextMaxAmmunition
            && radioHealth == nextRadioHealth
            && radioMaxHealth == nextRadioMaxHealth) {
            return;
        }
        inRange = true;
        construction = nextConstruction;
        ammunition = nextAmmunition;
        maxConstruction = nextMaxConstruction;
        maxAmmunition = nextMaxAmmunition;
        radioHealth = nextRadioHealth;
        radioMaxHealth = nextRadioMaxHealth;
        stateRevision++;
        applyStateToElement();
    }

    public static void clear() {
        if (!inRange) return;
        inRange = false;
        stateRevision++;
        applyStateToElement();
    }

    /** Resource-pack reloads invalidate the cached source sprites, not the HUD state. */
    public static void onResourceReload() {
        if (activeElement != null) {
            activeElement.invalidateResources();
        }
    }

    static GuiElement createElement() {
        if (activeElement != null) activeElement.dispose();
        activeElement = new SupplyElement();
        activeElement.applyState();
        return activeElement;
    }

    private static void applyStateToElement() {
        if (activeElement != null) activeElement.applyState();
    }

    static int scaledBarWidth(int value, int maximum, int width) {
        if (value <= 0 || maximum <= 0 || width <= 0) return 0;
        long clamped = Math.min((long) value, (long) maximum);
        return Math.max(1, (int) Math.min(width,
            Math.round(clamped * width / (double) maximum)));
    }

    static long stateRevisionForTest() {
        return stateRevision;
    }

    static void resetStateForTest() {
        inRange = false;
        construction = 0;
        ammunition = 0;
        maxConstruction = 1;
        maxAmmunition = 1;
        radioHealth = 0;
        radioMaxHealth = 1;
        stateRevision = 0;
        activeElement = null;
    }

    /**
     * Event-redrawn MUtil texture. The bitmap is rebuilt only after applyState marks
     * it dirty; normal render frames never redraw its individual contents.
     */
    private static final class SupplyElement extends GuiElement {

        private NativeImage pixels;
        private DynamicTexture texture;
        private ResourceLocation textureLocation;
        private NativeImage ammoIcon;
        private NativeImage constructionIcon;
        private NativeImage asciiFont;
        private boolean resourcesLoaded;
        private boolean dirty = true;
        private boolean failed;

        private SupplyElement() {
            super(HUD_X, HUD_Y, PANEL_WIDTH, PANEL_HEIGHT);
        }

        /** Network/root events only: mark one future bitmap upload as necessary. */
        private void applyState() {
            setVisible(inRange);
            if (inRange) dirty = true;
        }

        private void invalidateResources() {
            resourcesLoaded = false;
            failed = false;
            dirty = true;
        }

        @Override
        public void draw(GuiGraphics graphics, int parentX, int parentY, int drawWidth,
                         int drawHeight, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) return;
            if (dirty && !failed) rebuildTexture();
            if (textureLocation == null) {
                drawSafeFallback(graphics, parentX + getX(), parentY + getY());
                return;
            }
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            graphics.blit(textureLocation, parentX + getX(), parentY + getY(),
                0.0f, 0.0f, PANEL_WIDTH, PANEL_HEIGHT, PANEL_WIDTH, PANEL_HEIGHT);
        }

        private void rebuildTexture() {
            try {
                if (pixels == null) {
                    pixels = new NativeImage(PANEL_WIDTH, PANEL_HEIGHT, true);
                }
                loadResourcesIfNecessary();
                paintBitmap();
                if (texture == null) {
                    texture = new DynamicTexture(pixels);
                    texture.setFilter(false, false);
                    textureLocation = Minecraft.getInstance().getTextureManager()
                        .register("espetro_fob_supply", texture);
                } else {
                    texture.upload();
                }
                dirty = false;
            } catch (RuntimeException exception) {
                failed = true;
                Espetro.LOGGER.warn("Radio 补给面板动态纹理生成失败，已回退直接绘制", exception);
            }
        }

        private void paintBitmap() {
            pixels.fillRect(0, 0, PANEL_WIDTH, PANEL_HEIGHT, 0x00000000);
            fillArgb(0, 0, PANEL_WIDTH, PANEL_HEIGHT, PANEL_BACKGROUND);
            fillArgb(0, 0, PANEL_WIDTH, 1, PANEL_BORDER);
            fillArgb(0, PANEL_HEIGHT - 1, PANEL_WIDTH, 1, PANEL_BORDER);
            fillArgb(0, 1, 1, PANEL_HEIGHT - 2, PANEL_BORDER);
            fillArgb(PANEL_WIDTH - 1, 1, 1, PANEL_HEIGHT - 2, PANEL_BORDER);

            fillArgb(BAR_X, BAR_Y, BAR_WIDTH, BAR_HEIGHT, BAR_BACKGROUND);
            int healthWidth = scaledBarWidth(radioHealth, radioMaxHealth, BAR_WIDTH);
            if (healthWidth > 0) {
                fillArgb(BAR_X, BAR_Y, healthWidth, BAR_HEIGHT, BAR_HEALTH_COLOR);
            }

            drawIcon(ammoIcon, 8, 9, false);
            drawIcon(constructionIcon, 57, 9, true);
            drawNumber(Integer.toString(ammunition), 19, 10);
            drawNumber(Integer.toString(construction), 68, 10);
        }

        private void fillArgb(int x, int y, int width, int height, int argb) {
            if (width <= 0 || height <= 0) return;
            pixels.fillRect(x, y, width, height, argbToAbgr(argb));
        }

        private void drawNumber(String value, int x, int y) {
            if (asciiFont == null) return;
            int cursor = x;
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (character < '0' || character > '9') continue;
                int glyphX = (character & 15) * 8;
                int glyphY = ((character >>> 4) & 15) * 8;
                int left = 8;
                int right = -1;
                for (int row = 0; row < 8; row++) {
                    for (int column = 0; column < 8; column++) {
                        if ((asciiFont.getPixelRGBA(glyphX + column, glyphY + row) >>> 24) != 0) {
                            left = Math.min(left, column);
                            right = Math.max(right, column);
                        }
                    }
                }
                if (right < left) {
                    cursor += 4;
                    continue;
                }
                for (int row = 0; row < 8; row++) {
                    for (int column = left; column <= right; column++) {
                        int alpha = asciiFont.getPixelRGBA(glyphX + column, glyphY + row) >>> 24;
                        int targetX = cursor + column - left;
                        int targetY = y + row;
                        if (alpha != 0 && targetX < PANEL_WIDTH && targetY < PANEL_HEIGHT) {
                            pixels.blendPixel(targetX, targetY, (alpha << 24) | 0x00FFFFFF);
                        }
                    }
                }
                cursor += right - left + 2;
            }
        }

        private void loadResourcesIfNecessary() {
            if (resourcesLoaded) return;
            closeSourceImages();
            ammoIcon = loadScaledIcon(AMMO_ICON);
            constructionIcon = loadScaledIcon(CONSTRUCTION_ICON);
            asciiFont = loadIcon(VANILLA_ASCII_FONT);
            resourcesLoaded = true;
        }

        private NativeImage loadScaledIcon(ResourceLocation location) {
            NativeImage source = loadIcon(location);
            if (source == null) return null;
            try {
                int width = source.getWidth();
                int height = source.getHeight();
                int[] sourcePixels = new int[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        sourcePixels[y * width + x] = source.getPixelRGBA(x, y);
                    }
                }
                int[] fitted = IconRasterizer.fitAbgr(
                    sourcePixels, width, height, ICON_SIZE, ICON_SIZE);
                NativeImage scaled = new NativeImage(ICON_SIZE, ICON_SIZE, true);
                try {
                    for (int y = 0; y < ICON_SIZE; y++) {
                        for (int x = 0; x < ICON_SIZE; x++) {
                            scaled.setPixelRGBA(x, y, fitted[y * ICON_SIZE + x]);
                        }
                    }
                    return scaled;
                } catch (RuntimeException exception) {
                    scaled.close();
                    throw exception;
                }
            } finally {
                source.close();
            }
        }

        private NativeImage loadIcon(ResourceLocation location) {
            try {
                var resource = Minecraft.getInstance().getResourceManager().getResource(location);
                if (resource.isEmpty()) return null;
                return NativeImage.read(resource.get().open());
            } catch (IOException | RuntimeException exception) {
                Espetro.LOGGER.warn("无法读取 HUD 图标 {}，使用内置像素图标", location, exception);
                return null;
            }
        }

        private void drawIcon(NativeImage source, int targetX, int targetY,
                              boolean constructionType) {
            if (source == null) {
                drawFallbackIcon(targetX, targetY, constructionType);
                return;
            }
            for (int dy = 0; dy < ICON_SIZE; dy++) {
                for (int dx = 0; dx < ICON_SIZE; dx++) {
                    int sampled = source.getPixelRGBA(dx, dy);
                    if ((sampled >>> 24) != 0) {
                        pixels.blendPixel(targetX + dx, targetY + dy, sampled);
                    }
                }
            }
        }

        private void drawFallbackIcon(int x, int y, boolean constructionType) {
            int symbol = argbToAbgr(constructionType ? CONSTRUCTION_COLOR : AMMO_COLOR);
            int center = ICON_SIZE / 2;
            int radius = Math.max(2, (ICON_SIZE - 2) / 2);
            for (int row = 0; row < ICON_SIZE; row++) {
                for (int column = 0; column < ICON_SIZE; column++) {
                    int dx = column - center;
                    int dy = row - center;
                    if (dx * dx + dy * dy <= radius * radius) {
                        pixels.setPixelRGBA(x + column, y + row, 0xFF080808);
                    }
                }
            }
            if (constructionType) {
                pixels.setPixelRGBA(x + 3, y + 2, symbol);
                pixels.setPixelRGBA(x + 4, y + 2, symbol);
                pixels.setPixelRGBA(x + 5, y + 3, symbol);
                pixels.setPixelRGBA(x + 5, y + 4, symbol);
                pixels.setPixelRGBA(x + 4, y + 5, symbol);
                pixels.setPixelRGBA(x + 3, y + 6, symbol);
                pixels.setPixelRGBA(x + 2, y + 7, symbol);
            } else {
                for (int bullet = 0; bullet < 3; bullet++) {
                    for (int i = 0; i < 3; i++) {
                        int px = x + 2 + bullet + i;
                        int py = y + 6 - i - bullet;
                        if (px < x + ICON_SIZE && py >= y) {
                            pixels.setPixelRGBA(px, py, symbol);
                        }
                    }
                }
            }
        }

        private void drawSafeFallback(GuiGraphics graphics, int x, int y) {
            graphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, PANEL_BACKGROUND);
            graphics.renderOutline(x, y, PANEL_WIDTH, PANEL_HEIGHT, PANEL_BORDER);
            graphics.fill(x + BAR_X, y + BAR_Y,
                x + BAR_X + BAR_WIDTH, y + BAR_Y + BAR_HEIGHT, BAR_BACKGROUND);
            int healthWidth = scaledBarWidth(radioHealth, radioMaxHealth, BAR_WIDTH);
            if (healthWidth > 0) {
                graphics.fill(x + BAR_X, y + BAR_Y,
                    x + BAR_X + healthWidth, y + BAR_Y + BAR_HEIGHT, BAR_HEALTH_COLOR);
            }
            graphics.drawString(Minecraft.getInstance().font, Integer.toString(ammunition),
                x + 19, y + 11, 0xFFFFFFFF, false);
            graphics.drawString(Minecraft.getInstance().font, Integer.toString(construction),
                x + 68, y + 11, 0xFFFFFFFF, false);
        }

        private void dispose() {
            if (textureLocation != null) {
                Minecraft.getInstance().getTextureManager().release(textureLocation);
            } else if (texture != null) {
                texture.close();
            } else if (pixels != null) {
                pixels.close();
            }
            closeSourceImages();
            textureLocation = null;
            texture = null;
            pixels = null;
            ammoIcon = null;
            constructionIcon = null;
            asciiFont = null;
        }

        private void closeSourceImages() {
            if (ammoIcon != null) ammoIcon.close();
            if (constructionIcon != null) constructionIcon.close();
            if (asciiFont != null) asciiFont.close();
            ammoIcon = null;
            constructionIcon = null;
            asciiFont = null;
        }

        private static int argbToAbgr(int argb) {
            return (argb & 0xFF00FF00)
                | ((argb & 0x00FF0000) >>> 16)
                | ((argb & 0x000000FF) << 16);
        }
    }
}
