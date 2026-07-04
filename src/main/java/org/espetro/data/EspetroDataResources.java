package org.espetro.data;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.espetro.Espetro;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Espetro 服务端数据包资源选择策略。
 *
 * 对同一个 data/espetro 路径，优先使用存档 datapacks 等外部数据包资源；
 * 找不到外部资源时再回退到模组内置资源。
 */
public final class EspetroDataResources {

    private EspetroDataResources() {
    }

    public static ResourceLocation location(String path) {
        return ResourceLocation.fromNamespaceAndPath(Espetro.MOD_ID, path);
    }

    public static Optional<Resource> getPreferred(ResourceManager resourceManager, ResourceLocation location) {
        List<Resource> stack = resourceManager.getResourceStack(location);
        return Optional.ofNullable(selectPreferred(stack));
    }

    public static Map<ResourceLocation, Resource> listPreferred(
        ResourceManager resourceManager,
        String path,
        Predicate<ResourceLocation> filter
    ) {
        Map<ResourceLocation, List<Resource>> stacks = resourceManager.listResourceStacks(path, filter);
        Map<ResourceLocation, Resource> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, List<Resource>> entry : stacks.entrySet()) {
            Resource resource = selectPreferred(entry.getValue());
            if (resource != null) {
                result.put(entry.getKey(), resource);
            }
        }
        return result;
    }

    public static String readUtf8(Resource resource) throws IOException {
        try (InputStream inputStream = resource.open()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static String describeSource(Resource resource) {
        return resource.sourcePackId() + (resource.isBuiltin() ? " (builtin)" : " (datapack)");
    }

    @Nullable
    private static Resource selectPreferred(List<Resource> stack) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Resource resource = stack.get(i);
            if (!resource.isBuiltin()) {
                return resource;
            }
        }

        for (int i = stack.size() - 1; i >= 0; i--) {
            Resource resource = stack.get(i);
            if (resource.isBuiltin()) {
                return resource;
            }
        }
        return null;
    }
}
