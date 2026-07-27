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
        return resolve(value, value);
    }

    /**
     * Resolve class icon for scoreboard / HUD.
     * Tries disk {@code iconImage} first, then jar slug, then basename of a path,
     * then {@code classId} as slug.
     */
    static ResourceLocation resolveForScoreboard(String iconImage, String iconSlug, String classId) {
        ResourceLocation loc = resolve(iconImage, iconSlug);
        if (loc != null) {
            return loc;
        }
        if (iconImage != null && !iconImage.isBlank()) {
            String base = basenameSlug(iconImage);
            loc = resolveSlug(base);
            if (loc != null) {
                return loc;
            }
            // Common Icon/ file names use camelCase while jar slugs use snake_case.
            loc = resolveSlug(toSnakeSlug(base));
            if (loc != null) {
                return loc;
            }
        }
        if (classId != null && !classId.isBlank()) {
            loc = resolveSlug(classId);
            if (loc != null) {
                return loc;
            }
            // classId may be "us_redone_rifleman" — try last segment
            int idx = classId.lastIndexOf('_');
            if (idx > 0 && idx < classId.length() - 1) {
                // try progressive suffixes for multi-part role ids
                for (int i = 0; i < classId.length(); i++) {
                    if (classId.charAt(i) == '_' && i + 1 < classId.length()) {
                        loc = resolveSlug(classId.substring(i + 1));
                        if (loc != null) {
                            return loc;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String basenameSlug(String pathText) {
        String v = pathText.trim().replace('\\', '/');
        int slash = v.lastIndexOf('/');
        String name = slash >= 0 ? v.substring(slash + 1) : v;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    /** lightAT / HeavyAT / machineGunner → light_at / heavy_at / machine_gunner (best-effort). */
    private static String toSnakeSlug(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().replaceAll("_+", "_");
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
