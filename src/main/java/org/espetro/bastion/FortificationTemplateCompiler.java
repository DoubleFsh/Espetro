package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads vanilla StructureTemplate data once and compiles all four rotations. */
final class FortificationTemplateCompiler {

    private static final Set<String> FORBIDDEN_BLOCKS = Set.of(
        "minecraft:command_block", "minecraft:chain_command_block",
        "minecraft:repeating_command_block", "minecraft:structure_block",
        "minecraft:jigsaw"
    );
    private static final TagKey<net.minecraft.world.level.block.Block> FORBIDDEN_TAG =
        TagKey.create(Registries.BLOCK,
            new ResourceLocation("espetro:forbidden_fortification_blocks"));

    private FortificationTemplateCompiler() {
    }

    static Map<String, CompiledTemplate> compile(MinecraftServer server,
                                                 FortificationConfig.FortificationDef def,
                                                 FortificationConfig.Limits limits) throws Exception {
        FortificationConfig.Placement placement = def.placement;
        if ("entity".equals(placement.type)) {
            ResourceLocation entityId = ResourceLocation.tryParse(placement.entityId);
            if (entityId == null) throw new IllegalArgumentException("非法 entity_id");
            CompoundTag root = placement.entityNbt == null
                ? new CompoundTag() : TagParser.parseTag(placement.entityNbt.toString());
            root.putString("id", entityId.toString());
            placement.sanitizedEntityNbt = FortificationNbtSanitizer.sanitizeRootEntity(
                root, entityId, limits.maxPassengerDepth, Math.min(65_536,
                    limits.maxTemplateNbtBytes));
            boolean registered = BuiltInRegistries.ENTITY_TYPE.containsKey(entityId);
            if (!registered && placement.fallbackTemplate == null) {
                throw new IllegalArgumentException("实体类型未注册且没有 fallback_template: " + entityId);
            }
            if (placement.fallbackTemplate == null) return Map.of();
            CompiledTemplate fallback = compileOne(server,
                requireId(placement.fallbackTemplate, "fallback_template"), placement, def,
                limits, false);
            return Map.of("default", fallback);
        }

        Map<String, ResourceLocation> sources = new LinkedHashMap<>();
        sources.put("default", requireId(placement.template, "template"));
        if (placement.templateByTeam != null) {
            for (Map.Entry<String, String> entry : placement.templateByTeam.entrySet()) {
                sources.put(entry.getKey().toLowerCase(Locale.ROOT),
                    requireId(entry.getValue(), "template_by_team." + entry.getKey()));
            }
        }
        Map<String, CompiledTemplate> result = new LinkedHashMap<>();
        for (Map.Entry<String, ResourceLocation> entry : sources.entrySet()) {
            result.put(entry.getKey(), compileOne(server, entry.getValue(), placement, def,
                limits, placement.includeEntities));
        }
        return Map.copyOf(result);
    }

    private static CompiledTemplate compileOne(MinecraftServer server, ResourceLocation id,
                                               FortificationConfig.Placement placement,
                                               FortificationConfig.FortificationDef def,
                                               FortificationConfig.Limits limits,
                                               boolean includeEntities) throws Exception {
        StructureTemplate template = server.getStructureManager().get(id)
            .orElseThrow(() -> new IllegalArgumentException("缺少 Structure NBT " + id));
        CompoundTag serialized = template.save(new CompoundTag());
        int estimatedBytes = serialized.toString().getBytes(StandardCharsets.UTF_8).length;
        if (estimatedBytes > limits.maxTemplateNbtBytes) {
            throw new IllegalArgumentException("模板 NBT 超过 " + limits.maxTemplateNbtBytes
                + " bytes: " + estimatedBytes);
        }
        Vec3i size = template.getSize();
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1
            || size.getX() > limits.maxTemplateAxis || size.getY() > limits.maxTemplateAxis
            || size.getZ() > limits.maxTemplateAxis) {
            throw new IllegalArgumentException("模板尺寸越界: " + size);
        }
        ListTag blocks = serialized.getList("blocks", Tag.TAG_COMPOUND);
        if (blocks.size() > limits.maxTemplateBlocks) {
            throw new IllegalArgumentException("模板方块数超过 " + limits.maxTemplateBlocks);
        }
        ListTag palette = selectPalette(serialized, placement.paletteIndex);
        List<RawBlock> rawBlocks = new ArrayList<>(blocks.size());
        int damageableBlocks = 0;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3) throw new IllegalArgumentException("blocks[" + i + "].pos 非法");
            int stateIndex = block.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                throw new IllegalArgumentException("blocks[" + i + "].state 越界");
            }
            BlockState state = NbtUtils.readBlockState(
                server.registryAccess().lookupOrThrow(Registries.BLOCK),
                palette.getCompound(stateIndex));
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (FORBIDDEN_BLOCKS.contains(blockId.toString()) || state.is(FORBIDDEN_TAG)) {
                throw new IllegalArgumentException("模板包含危险方块 " + blockId);
            }
            BlockPos local = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
            CompoundTag blockEntity = block.contains("nbt", Tag.TAG_COMPOUND)
                ? FortificationNbtSanitizer.sanitizeBlockEntity(block.getCompound("nbt"),
                    Math.min(65_536, limits.maxTemplateNbtBytes)) : null;
            Touch touch;
            if (state.is(Blocks.STRUCTURE_VOID)) touch = Touch.IGNORE;
            else if (state.isAir()) touch = Touch.EXPLICIT_AIR;
            else {
                touch = Touch.BLOCK;
                damageableBlocks++;
            }
            rawBlocks.add(new RawBlock(i, local, state, blockEntity, touch));
        }

        List<CompiledEntity> entities = new ArrayList<>();
        ListTag rawEntities = serialized.getList("entities", Tag.TAG_COMPOUND);
        if (rawEntities.size() > limits.maxTemplateEntities) {
            throw new IllegalArgumentException("模板实体数超过 " + limits.maxTemplateEntities);
        }
        if (includeEntities) {
            for (int i = 0; i < rawEntities.size(); i++) {
                CompoundTag entry = rawEntities.getCompound(i);
                ListTag pos = entry.getList("pos", Tag.TAG_DOUBLE);
                ListTag blockPos = entry.getList("blockPos", Tag.TAG_INT);
                if (pos.size() != 3 || blockPos.size() != 3
                    || !entry.contains("nbt", Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("entities[" + i + "] 格式非法");
                }
                CompoundTag sanitized = FortificationNbtSanitizer.sanitizeEntity(
                    entry.getCompound("nbt"), 0, limits.maxPassengerDepth,
                    Math.min(65_536, limits.maxTemplateNbtBytes));
                ResourceLocation type = FortificationNbtSanitizer.requireEntityType(sanitized);
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(type)) {
                    throw new IllegalArgumentException("结构实体类型未注册: " + type);
                }
                boolean damageable = def.durability.damageableStructureEntities.contains(i);
                entities.add(new CompiledEntity(i,
                    new Vec3(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)),
                    new BlockPos(blockPos.getInt(0), blockPos.getInt(1), blockPos.getInt(2)),
                    type, sanitized, damageable));
            }
        }
        for (Integer index : def.durability.damageableStructureEntities) {
            if (!includeEntities || index >= rawEntities.size()) {
                throw new IllegalArgumentException("damageable_structure_entities 索引越界: " + index);
            }
        }
        int damageableParts = damageableBlocks
            + (int) entities.stream().filter(CompiledEntity::damageable).count();
        if (damageableParts <= 0) throw new IllegalArgumentException("模板没有可损伤部件");

        BlockPos origin = vec(placement.originOffset);
        BlockPos pivot = vec(placement.pivot);
        Map<Direction, OrientedTemplate> rotations = new EnumMap<>(Direction.class);
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST,
            Direction.SOUTH, Direction.WEST)) {
            List<OrientedBlock> orientedBlocks = new ArrayList<>();
            List<BlockPos> footprint = new ArrayList<>();
            AABB bounds = null;
            for (RawBlock raw : rawBlocks) {
                if (raw.touch == Touch.IGNORE) continue;
                BlockPos relative = FortificationTransform.world(BlockPos.ZERO, origin,
                    raw.local, pivot, direction);
                BlockState rotated = raw.state.rotate(FortificationTransform.rotation(direction));
                orientedBlocks.add(new OrientedBlock(raw.index, relative, rotated,
                    raw.blockEntity == null ? null : raw.blockEntity.copy(), raw.touch));
                AABB cell = new AABB(relative);
                bounds = bounds == null ? cell : bounds.minmax(cell);
                if (raw.touch == Touch.BLOCK) footprint.add(relative);
            }
            List<OrientedEntity> orientedEntities = new ArrayList<>();
            for (CompiledEntity entity : entities) {
                Vec3 relative = transformEntity(entity.localPosition, origin, pivot, direction);
                BlockPos relativeBlock = FortificationTransform.world(BlockPos.ZERO, origin,
                    entity.localBlockPos, pivot, direction);
                orientedEntities.add(new OrientedEntity(entity.index, relative, relativeBlock,
                    entity.type, entity.visualNbt.copy(), entity.damageable));
                AABB cell = new AABB(relativeBlock);
                bounds = bounds == null ? cell : bounds.minmax(cell);
            }
            if (bounds == null) bounds = new AABB(BlockPos.ZERO);
            rotations.put(direction, new OrientedTemplate(List.copyOf(orientedBlocks),
                List.copyOf(orientedEntities), List.copyOf(footprint), bounds));
        }
        return new CompiledTemplate(id, placement.paletteIndex, size, damageableParts,
            Map.copyOf(rotations));
    }

    private static ListTag selectPalette(CompoundTag template, int index) {
        if (template.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = template.getList("palettes", Tag.TAG_LIST);
            if (index < 0 || index >= palettes.size()) {
                throw new IllegalArgumentException("palette_index " + index + " 越界（共 "
                    + palettes.size() + "）");
            }
            return palettes.getList(index);
        }
        if (index != 0) throw new IllegalArgumentException("单 palette 模板只能使用 palette_index=0");
        return template.getList("palette", Tag.TAG_COMPOUND);
    }

    private static Vec3 transformEntity(Vec3 local, BlockPos origin, BlockPos pivot,
                                        Direction direction) {
        double x = origin.getX() + local.x - pivot.getX();
        double y = origin.getY() + local.y - pivot.getY();
        double z = origin.getZ() + local.z - pivot.getZ();
        return switch (direction) {
            case NORTH -> new Vec3(x, y, z);
            case EAST -> new Vec3(-z, y, x);
            case SOUTH -> new Vec3(-x, y, -z);
            case WEST -> new Vec3(z, y, -x);
            default -> throw new IllegalArgumentException("facing must be horizontal");
        };
    }

    private static BlockPos vec(int[] values) {
        if (values == null || values.length != 3) throw new IllegalArgumentException("坐标必须为3项");
        return new BlockPos(values[0], values[1], values[2]);
    }

    private static ResourceLocation requireId(String raw, String path) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) throw new IllegalArgumentException(path + " 非法: " + raw);
        return id;
    }

    enum Touch { BLOCK, EXPLICIT_AIR, IGNORE }

    record CompiledTemplate(ResourceLocation id, int paletteIndex, Vec3i size,
                            int damageablePartCount,
                            Map<Direction, OrientedTemplate> rotations) {
        OrientedTemplate oriented(Direction facing) {
            OrientedTemplate result = rotations.get(facing);
            if (result == null) throw new IllegalArgumentException("facing must be horizontal");
            return result;
        }
    }

    record OrientedTemplate(List<OrientedBlock> blocks, List<OrientedEntity> entities,
                            List<BlockPos> damageableFootprint, AABB relativeBounds) {
        AABB boundsAt(BlockPos anchor) {
            return relativeBounds.move(anchor);
        }
    }

    record OrientedBlock(int templateIndex, BlockPos relativePos, BlockState state,
                         @Nullable CompoundTag blockEntityNbt, Touch touch) {
    }

    record CompiledEntity(int index, Vec3 localPosition, BlockPos localBlockPos,
                          ResourceLocation type, CompoundTag visualNbt, boolean damageable) {
    }

    record OrientedEntity(int templateIndex, Vec3 relativePosition, BlockPos relativeBlockPos,
                          ResourceLocation type, CompoundTag visualNbt, boolean damageable) {
    }

    private record RawBlock(int index, BlockPos local, BlockState state,
                            @Nullable CompoundTag blockEntity, Touch touch) {
    }
}
