package org.espetro.mapconfig;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic dimension ID generation and validation for EsDimensions.json.
 */
public final class DimensionIdUtil {

    public static final String NAMESPACE = "espetro";

    private DimensionIdUtil() {
    }

    public static ResourceLocation generate(String mapFolderName) {
        String slug = PathSafety.slugify(mapFolderName);
        if (slug.isEmpty()) {
            slug = "map_" + PathSafety.stableShortHash(mapFolderName);
        }
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, slug);
    }

    public static Optional<String> validateManualId(String raw, Set<String> alreadyUsed) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("dimension_id 为空");
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return Optional.of("dimension_id 不是合法 ResourceLocation: " + raw);
        }
        String ns = rl.getNamespace();
        if ("minecraft".equals(ns) || "forge".equals(ns)) {
            return Optional.of("dimension_id 不得使用 minecraft/forge 命名空间: " + raw);
        }
        if (alreadyUsed != null && alreadyUsed.contains(rl.toString())) {
            return Optional.of("dimension_id 冲突: " + rl);
        }
        return Optional.empty();
    }

    public static ResourceLocation parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ResourceLocation.tryParse(raw.trim().toLowerCase(Locale.ROOT));
    }
}
