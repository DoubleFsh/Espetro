package org.espetro.dimension;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.mapconfig.ActiveMapConfig;
import org.espetro.mapconfig.ExternalConfigBootstrap;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registers battlefield dimensions via an in-memory SERVER_DATA pack at startup.
 * Does not write datapacks into the world save.
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DimensionPackBootstrap {

    public static final String PACK_ID = "espetro_dimensions_runtime";

    private DimensionPackBootstrap() {
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        ExternalConfigBootstrap.bootstrapIfNeeded();
        Map<String, byte[]> dimensionResources = new LinkedHashMap<>();
        for (ActiveMapConfig map : ExternalConfigBootstrap.getUsableMaps()) {
            String path = "data/" + map.dimensionId.getNamespace() + "/dimension/"
                + map.dimensionId.getPath() + ".json";
            dimensionResources.put(path.toLowerCase(Locale.ROOT),
                map.dimensionJson.getBytes(StandardCharsets.UTF_8));
        }
        int registeredDimensions = dimensionResources.size();
        dimensionResources.putAll(loadBuiltinStructures());
        // Also register rejected maps that still have unique IDs so registry is stable? Plan says
        // invalid maps refuse registration. So only usable.
        if (registeredDimensions == 0) {
            Espetro.LOGGER.warn("无可用地图维度可注册；/espetro prestart 将不可用直到配置有效地图");
        }

        event.addRepositorySource(consumer -> {
            Pack.ResourcesSupplier resources = new MemoryDimensionPackResources(dimensionResources);
            Pack pack = Pack.readMetaAndCreate(
                PACK_ID,
                Component.literal("Espetro Dimensions"),
                true,
                resources,
                PackType.SERVER_DATA,
                Pack.Position.TOP,
                PackSource.BUILT_IN
            );
            if (pack != null) {
                consumer.accept(pack);
                Espetro.LOGGER.info("已添加内存 SERVER_DATA 包: {} ({} 项资源)", PACK_ID, dimensionResources.size());
            } else {
                // Forge 1.20.1 Pack.readMetaAndCreate needs pack.mcmeta via resources.
                // MemoryDimensionPackResources provides it; if still null, log loudly.
                Espetro.LOGGER.error("无法创建 Espetro 维度数据包（readMetaAndCreate 返回 null）");
            }
        });
    }

    /**
     * Text SNBT lives in source control so reviews can inspect the exact shape.
     * It is converted to the compressed vanilla Structure NBT resource consumed
     * by StructureTemplateManager before the first datapack load completes.
     */
    private static Map<String, byte[]> loadBuiltinStructures() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : Set.of(
            "radio", "hab_attack", "hab_defend", "ammo_crate", "sandbag_wall",
            "vehicle_supply_station_fallback")) {
            String source = "/data/espetro/structure_sources/fortifications/" + name + ".snbt";
            try (InputStream input = DimensionPackBootstrap.class.getResourceAsStream(source)) {
                if (input == null) throw new IllegalStateException("缺少 " + source);
                String snbt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.TagParser.parseTag(snbt);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.writeCompressed(tag, bytes);
                result.put("data/espetro/structures/fortifications/" + name + ".nbt",
                    bytes.toByteArray());
            } catch (Exception e) {
                Espetro.LOGGER.error("无法编译内置 Structure NBT {}", source, e);
            }
        }
        return result;
    }

    /**
     * Minimal PackResources implementation for Forge 1.20.1.
     */
    public static final class MemoryDimensionPackResources implements Pack.ResourcesSupplier {

        private final Map<String, byte[]> dimensionResources;

        public MemoryDimensionPackResources(Map<String, byte[]> dimensionResources) {
            this.dimensionResources = Map.copyOf(dimensionResources);
        }

        @Override
        public net.minecraft.server.packs.PackResources open(String id) {
            return new MemoryPackResources(id, dimensionResources);
        }
    }

    private static final class MemoryPackResources implements net.minecraft.server.packs.PackResources {

        private static final String PACK_MCMETA = """
            {
              "pack": {
                "description": "Espetro runtime dimensions",
                "pack_format": 15
              }
            }
            """;

        private final String packId;
        private final Map<String, byte[]> dimensionResources;
        private boolean closed;

        private MemoryPackResources(String packId, Map<String, byte[]> dimensionResources) {
            this.packId = packId;
            this.dimensionResources = dimensionResources;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getRootResource(String... pathParts) {
            String joined = String.join("/", pathParts);
            if ("pack.mcmeta".equals(joined)) {
                return () -> new ByteArrayInputStream(PACK_MCMETA.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getResource(PackType type, net.minecraft.resources.ResourceLocation location) {
            if (type != PackType.SERVER_DATA) {
                return null;
            }
            String path = "data/" + location.getNamespace() + "/" + location.getPath();
            byte[] content = dimensionResources.get(path.toLowerCase(Locale.ROOT));
            if (content != null) {
                return () -> new ByteArrayInputStream(content);
            }
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.SERVER_DATA) {
                return;
            }
            String prefix = "data/" + namespace + "/" + path;
            for (Map.Entry<String, byte[]> entry : dimensionResources.entrySet()) {
                String dimPath = entry.getKey();
                if (dimPath.startsWith(prefix.toLowerCase(Locale.ROOT)) || dimPath.startsWith(prefix)) {
                    // dimPath = data/ns/dimension/name.json
                    String withoutData = dimPath.substring("data/".length());
                    int slash = withoutData.indexOf('/');
                    if (slash < 0) continue;
                    String ns = withoutData.substring(0, slash);
                    String rest = withoutData.substring(slash + 1);
                    net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.resources.ResourceLocation.tryParse(ns + ":" + rest.replace(".json", ""));
                    // ResourceLocation path should include .json for listResources in 1.20.1
                    rl = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ns, rest);
                    if (rl != null && ns.equals(namespace) && rest.startsWith(path.isEmpty() ? "" : path)) {
                        byte[] content = entry.getValue();
                        output.accept(rl, () -> new ByteArrayInputStream(content));
                    }
                }
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            if (type != PackType.SERVER_DATA) {
                return Set.of();
            }
            Set<String> ns = new LinkedHashSet<>();
            for (String p : dimensionResources.keySet()) {
                // data/<ns>/...
                String[] parts = p.split("/");
                if (parts.length >= 2) {
                    ns.add(parts[1]);
                }
            }
            return ns;
        }

        @Override
        public @Nullable <T> T getMetadataSection(net.minecraft.server.packs.metadata.MetadataSectionSerializer<T> deserializer) {
            if ("pack".equals(deserializer.getMetadataSectionName())) {
                try {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(PACK_MCMETA).getAsJsonObject();
                    return deserializer.fromJson(obj.getAsJsonObject("pack"));
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public String packId() {
            return packId;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
