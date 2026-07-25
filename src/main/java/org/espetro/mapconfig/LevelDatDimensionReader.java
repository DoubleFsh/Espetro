package org.espetro.mapconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads the overworld generator serialized in a vanilla level.dat and converts
 * it into the JSON representation used by a level-stem datapack entry.
 */
public final class LevelDatDimensionReader {

    private LevelDatDimensionReader() {
    }

    public static JsonObject readDimensionJson(Path levelDat) throws IOException {
        CompoundTag root = NbtIo.readCompressed(levelDat.toFile());
        CompoundTag data = requireCompound(root, "Data");
        CompoundTag settings = requireCompound(data, "WorldGenSettings");
        CompoundTag dimensions = requireCompound(settings, "dimensions");
        CompoundTag overworld = requireCompound(dimensions, "minecraft:overworld");
        CompoundTag generator = requireCompound(overworld, "generator");

        JsonObject result = new JsonObject();
        String dimensionType = overworld.getString("type");
        result.addProperty("type", dimensionType.isBlank() ? "minecraft:overworld" : dimensionType);
        JsonElement generatorJson = toJson(generator);
        if (!generatorJson.isJsonObject()
            || !generatorJson.getAsJsonObject().has("type")) {
            throw new IOException("level.dat 的 Overworld generator 缺少 type");
        }
        result.add("generator", generatorJson);
        return result;
    }

    private static CompoundTag requireCompound(CompoundTag parent, String key) throws IOException {
        Tag value = parent.get(key);
        if (!(value instanceof CompoundTag compound)) {
            throw new IOException("level.dat 缺少复合节点 " + key);
        }
        return compound;
    }

    static JsonElement toJson(Tag tag) throws IOException {
        if (tag instanceof CompoundTag compound) {
            JsonObject object = new JsonObject();
            for (String key : compound.getAllKeys()) {
                Tag value = compound.get(key);
                if (value != null) {
                    object.add(key, toJson(value));
                }
            }
            return object;
        }
        if (tag instanceof ListTag list) {
            JsonArray array = new JsonArray();
            for (Tag value : list) {
                array.add(toJson(value));
            }
            return array;
        }
        if (tag instanceof StringTag string) {
            return new JsonPrimitive(string.getAsString());
        }
        // Mojang's worldgen codecs serialize booleans as NBT bytes.
        if (tag instanceof ByteTag value) {
            return new JsonPrimitive(value.getAsByte() != 0);
        }
        if (tag instanceof IntTag value) {
            return new JsonPrimitive(value.getAsInt());
        }
        if (tag instanceof LongTag value) {
            return new JsonPrimitive(value.getAsLong());
        }
        if (tag instanceof ShortTag value) {
            return new JsonPrimitive(value.getAsShort());
        }
        if (tag instanceof FloatTag value) {
            return new JsonPrimitive(value.getAsFloat());
        }
        if (tag instanceof DoubleTag value) {
            return new JsonPrimitive(value.getAsDouble());
        }
        if (tag instanceof ByteArrayTag array) {
            JsonArray result = new JsonArray();
            for (byte value : array.getAsByteArray()) result.add(value);
            return result;
        }
        if (tag instanceof IntArrayTag array) {
            JsonArray result = new JsonArray();
            for (int value : array.getAsIntArray()) result.add(value);
            return result;
        }
        if (tag instanceof LongArrayTag array) {
            JsonArray result = new JsonArray();
            for (long value : array.getAsLongArray()) result.add(value);
            return result;
        }
        if (tag instanceof NumericTag numeric) {
            return new JsonPrimitive(numeric.getAsNumber());
        }
        throw new IOException("不支持的 level.dat NBT 类型: " + tag.getType().getName());
    }
}
