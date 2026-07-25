package org.espetro.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.espetro.Espetro;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析职业图标：优先磁盘 {@code IconImage} 完整路径，否则 jar 内 roles 短名。
 */
final class RoleIconResources {
    static final int TEXTURE_SIZE = 128;

    private static final Map<String, ResourceLocation> DISK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> DISK_FAILED = new ConcurrentHashMap<>();

    private RoleIconResources() {
    }

    /** 部署/记分板：路径优先，失败回退 slug。 */
    static ResourceLocation resolve(String iconImagePath, String iconSlug) {
        ResourceLocation fromDisk = resolveDiskPath(iconImagePath);
        if (fromDisk != null) {
            return fromDisk;
        }
        return resolveSlug(iconSlug);
    }

    static ResourceLocation resolve(String icon) {
        return resolveSlug(icon);
    }

    /** 记分板等：classIcon 可能是磁盘路径或 slug。 */
    static ResourceLocation resolveDiskOrSlug(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("/") || v.contains(":\\") || v.contains("/") || v.contains("\\")) {
            ResourceLocation disk = resolveDiskPath(v);
            if (disk != null) {
                return disk;
            }
        }
        return resolveSlug(v);
    }

    static ResourceLocation resolveSlug(String icon) {
        if (icon == null || icon.isBlank() || icon.contains("..")
                || !icon.matches("[a-z0-9][a-z0-9_/-]*")) {
            return null;
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            "espetro", "textures/gui/roles/" + icon + ".png");
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent()
            ? location
            : null;
    }

    static ResourceLocation resolveDiskPath(String pathText) {
        if (pathText == null || pathText.isBlank()) {
            return null;
        }
        String key = pathText.trim();
        if (DISK_FAILED.containsKey(key)) {
            return null;
        }
        ResourceLocation cached = DISK_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Path path = Path.of(key);
            if (!path.isAbsolute()) {
                // 相对游戏目录
                Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
                path = gameDir.resolve(key).normalize();
            }
            if (!Files.isRegularFile(path)) {
                DISK_FAILED.put(key, Boolean.TRUE);
                return null;
            }
            try (InputStream in = Files.newInputStream(path)) {
                NativeImage image = NativeImage.read(in);
                DynamicTexture texture = new DynamicTexture(image);
                String safe = Integer.toHexString(key.hashCode());
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    "espetro", "dynamic/class_icon_" + safe);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                DISK_CACHE.put(key, id);
                return id;
            }
        } catch (Exception e) {
            DISK_FAILED.put(key, Boolean.TRUE);
            Espetro.LOGGER.debug("IconImage 加载失败: {} ({})", key, e.toString());
            return null;
        }
    }
}
