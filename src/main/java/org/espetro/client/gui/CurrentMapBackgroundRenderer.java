package org.espetro.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Renders a blurred, darkened client-local preview for the current map. */
final class CurrentMapBackgroundRenderer {
    private static final int MAX_TEXTURE_WIDTH = 320;
    private static final int MAX_TEXTURE_HEIGHT = 180;
    private static final int BLUR_RADIUS = 5;
    private static final int DARK_OVERLAY = 0xB3000000;

    private static final Map<String, BackgroundTexture> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    private record BackgroundTexture(ResourceLocation location, int width, int height) {
    }

    record Crop(int u, int v, int width, int height) {
    }

    private CurrentMapBackgroundRenderer() {
    }

    static void render(GuiGraphics graphics, int screenWidth, int screenHeight, String mapFolder) {
        BackgroundTexture texture = getOrLoad(mapFolder);
        if (texture == null || screenWidth <= 0 || screenHeight <= 0) {
            EspetroMutilWidgets.drawScreenShade(graphics, screenWidth, screenHeight);
            return;
        }

        Crop crop = aspectFillCrop(texture.width, texture.height, screenWidth, screenHeight);
        graphics.blit(texture.location, 0, 0, screenWidth, screenHeight,
            crop.u, crop.v, crop.width, crop.height, texture.width, texture.height);
        graphics.fill(0, 0, screenWidth, screenHeight, DARK_OVERLAY);
    }

    static Crop aspectFillCrop(int textureWidth, int textureHeight,
                               int screenWidth, int screenHeight) {
        if (textureWidth <= 0 || textureHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return new Crop(0, 0, Math.max(1, textureWidth), Math.max(1, textureHeight));
        }

        long textureScaled = (long) textureWidth * screenHeight;
        long screenScaled = (long) screenWidth * textureHeight;
        if (textureScaled > screenScaled) {
            int cropWidth = Math.max(1,
                Math.min(textureWidth, (int) ((long) textureHeight * screenWidth / screenHeight)));
            return new Crop((textureWidth - cropWidth) / 2, 0, cropWidth, textureHeight);
        }
        if (textureScaled < screenScaled) {
            int cropHeight = Math.max(1,
                Math.min(textureHeight, (int) ((long) textureWidth * screenHeight / screenWidth)));
            return new Crop(0, (textureHeight - cropHeight) / 2, textureWidth, cropHeight);
        }
        return new Crop(0, 0, textureWidth, textureHeight);
    }

    private static BackgroundTexture getOrLoad(String mapFolder) {
        String key = mapFolder == null ? "" : mapFolder.trim();
        if (key.isEmpty() || FAILED.contains(key)) {
            return null;
        }
        BackgroundTexture cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        Path previewPath = MapVotePreviewResolver.resolve(minecraft.gameDirectory.toPath(), key);
        if (previewPath == null) {
            FAILED.add(key);
            return null;
        }

        try (InputStream input = Files.newInputStream(previewPath);
             NativeImage source = NativeImage.read(input)) {
            NativeImage blurred = createBlurredImage(source);
            DynamicTexture dynamicTexture = new DynamicTexture(blurred);
            dynamicTexture.setFilter(true, false);
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                "espetro", "map_background/" + Integer.toUnsignedString(key.hashCode(), 16));
            minecraft.getTextureManager().register(location, dynamicTexture);
            BackgroundTexture loaded = new BackgroundTexture(
                location, blurred.getWidth(), blurred.getHeight());
            CACHE.put(key, loaded);
            return loaded;
        } catch (IOException | RuntimeException ignored) {
            FAILED.add(key);
            return null;
        }
    }

    private static NativeImage createBlurredImage(NativeImage source) {
        double scale = Math.min(1.0, Math.min(
            (double) MAX_TEXTURE_WIDTH / source.getWidth(),
            (double) MAX_TEXTURE_HEIGHT / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            int sourceY = Math.min(source.getHeight() - 1,
                (int) ((long) y * source.getHeight() / height));
            for (int x = 0; x < width; x++) {
                int sourceX = Math.min(source.getWidth() - 1,
                    (int) ((long) x * source.getWidth() / width));
                pixels[y * width + x] = source.getPixelRGBA(sourceX, sourceY);
            }
        }

        int[] blurredPixels = boxBlur(pixels, width, height, BLUR_RADIUS);
        NativeImage blurred = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                blurred.setPixelRGBA(x, y, blurredPixels[y * width + x]);
            }
        }
        return blurred;
    }

    static int[] boxBlur(int[] source, int width, int height, int radius) {
        if (source == null || source.length != width * height || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
        if (radius <= 0) {
            return source.clone();
        }
        int[] horizontal = new int[source.length];
        int[] output = new int[source.length];
        blurPass(source, horizontal, width, height, radius, true);
        blurPass(horizontal, output, width, height, radius, false);
        return output;
    }

    private static void blurPass(int[] source, int[] target, int width, int height,
                                 int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int lineLength = horizontal ? width : height;
        for (int line = 0; line < lines; line++) {
            long a = 0;
            long b = 0;
            long c = 0;
            long d = 0;
            int count = 0;
            for (int position = -radius; position <= radius; position++) {
                if (position >= 0 && position < lineLength) {
                    int pixel = source[index(horizontal, line, position, width)];
                    a += pixel >>> 24;
                    b += (pixel >>> 16) & 0xFF;
                    c += (pixel >>> 8) & 0xFF;
                    d += pixel & 0xFF;
                    count++;
                }
            }

            for (int position = 0; position < lineLength; position++) {
                target[index(horizontal, line, position, width)] =
                    ((int) (a / count) << 24)
                        | ((int) (b / count) << 16)
                        | ((int) (c / count) << 8)
                        | (int) (d / count);

                int outgoing = position - radius;
                if (outgoing >= 0) {
                    int pixel = source[index(horizontal, line, outgoing, width)];
                    a -= pixel >>> 24;
                    b -= (pixel >>> 16) & 0xFF;
                    c -= (pixel >>> 8) & 0xFF;
                    d -= pixel & 0xFF;
                    count--;
                }
                int incoming = position + radius + 1;
                if (incoming < lineLength) {
                    int pixel = source[index(horizontal, line, incoming, width)];
                    a += pixel >>> 24;
                    b += (pixel >>> 16) & 0xFF;
                    c += (pixel >>> 8) & 0xFF;
                    d += pixel & 0xFF;
                    count++;
                }
            }
        }
    }

    private static int index(boolean horizontal, int line, int position, int width) {
        return horizontal ? line * width + position : position * width + line;
    }
}
