package com.example.hcrpoints.command;

import com.example.hcrpoints.capturepoint.CapturePoint;
import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.example.hcrpoints.util.ModLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * 普通据点预设管理器
 * 负责保存和加载普通据点的预设，包括据点位置、名称等信息
 */
public class CapturePointPresetManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter())
            .create();
    
    private static final String PRESETS_DIR = "config/hcrpoints/capture_presets";
    
    /**
     * 保存普通据点预设
     * @param presetId 预设ID
     * @return 是否保存成功
     */
    public static boolean savePreset(int presetId) {
        try {
            // 获取当前状态
            CapturePointManager manager = CapturePointManager.getInstance();
            
            // 转换普通据点为可序列化的格式
            Map<String, SerializableCapturePoint> serializablePoints = new HashMap<>();
            for (CapturePoint point : manager.getAllCapturePoints()) {
                serializablePoints.put(point.getName(), new SerializableCapturePoint(
                    point.getName(),
                    point.getPos1(),
                    point.getPos2()
                ));
            }
            
            // 创建预设数据对象
            PresetData presetData = new PresetData(serializablePoints);
                
            // 确保预设目录存在
            File presetsDir = new File(PRESETS_DIR);
            if (!presetsDir.exists()) {
                presetsDir.mkdirs();
            }
            
            // 保存到文件
            File presetFile = new File(presetsDir, "preset_" + presetId + ".json");
            try (FileWriter writer = new FileWriter(presetFile)) {
                GSON.toJson(presetData, writer);
            }
            
            ModLogger.info("普通据点预设 " + presetId + " 已保存，包含 " + serializablePoints.size() + " 个据点");
            return true;
        } catch (IOException e) {
            ModLogger.error("保存普通据点预设时发生异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 加载普通据点预设
     * @param presetId 预设ID
     * @return 是否加载成功
     */
    public static boolean loadPreset(int presetId) {
        try {
            // 检查文件是否存在
            File presetFile = new File(PRESETS_DIR, "preset_" + presetId + ".json");
            if (!presetFile.exists()) {
                ModLogger.warn("普通据点预设文件不存在: " + presetFile.getAbsolutePath());
                return false;
            }
            
            // 读取预设数据
            PresetData presetData;
            try (FileReader reader = new FileReader(presetFile)) {
                Type presetDataType = new TypeToken<PresetData>() {}.getType();
                presetData = GSON.fromJson(reader, presetDataType);
            }
            
            if (presetData == null) {
                ModLogger.error("普通据点预设文件格式错误: " + presetFile.getAbsolutePath());
                return false;
            }
            
            // 恢复到管理器
            CapturePointManager manager = CapturePointManager.getInstance();
            
            // 清空现有据点
            manager.clearAllCapturePoints();
            
            // 恢复普通据点
            for (Map.Entry<String, SerializableCapturePoint> entry : presetData.getCapturePoints().entrySet()) {
                SerializableCapturePoint serializablePoint = entry.getValue();
                manager.createCapturePoint(
                    serializablePoint.getName(),
                    serializablePoint.getPos1(),
                    serializablePoint.getPos2()
                );
            }
            
            ModLogger.info("普通据点预设 " + presetId + " 已加载，包含 " + presetData.getCapturePoints().size() + " 个据点");
            return true;
        } catch (IOException e) {
            ModLogger.error("加载普通据点预设时发生异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 预设数据类
     */
    private static class PresetData {
        private Map<String, SerializableCapturePoint> capturePoints;
        
        public PresetData(Map<String, SerializableCapturePoint> capturePoints) {
            this.capturePoints = capturePoints;
        }
        
        public Map<String, SerializableCapturePoint> getCapturePoints() {
            return capturePoints;
        }
    }
    
    /**
     * 可序列化的普通据点类
     */
    private static class SerializableCapturePoint {
        private String name;
        private BlockPos pos1;
        private BlockPos pos2;
        
        public SerializableCapturePoint(String name, BlockPos pos1, BlockPos pos2) {
            this.name = name;
            this.pos1 = pos1;
            this.pos2 = pos2;
        }
        
        public String getName() {
            return name;
        }
        
        public BlockPos getPos1() {
            return pos1;
        }
        
        public BlockPos getPos2() {
            return pos2;
        }
    }
    
    /**
     * BlockPos适配器，用于Gson序列化和反序列化
     */
    private static class BlockPosAdapter implements com.google.gson.JsonSerializer<BlockPos>, com.google.gson.JsonDeserializer<BlockPos> {
        @Override
        public com.google.gson.JsonElement serialize(BlockPos src, java.lang.reflect.Type typeOfSrc, com.google.gson.JsonSerializationContext context) {
            com.google.gson.JsonObject jsonObject = new com.google.gson.JsonObject();
            jsonObject.addProperty("x", src.getX());
            jsonObject.addProperty("y", src.getY());
            jsonObject.addProperty("z", src.getZ());
            return jsonObject;
        }
        
        @Override
        public BlockPos deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type typeOfT, com.google.gson.JsonDeserializationContext context) throws com.google.gson.JsonParseException {
            com.google.gson.JsonObject jsonObject = json.getAsJsonObject();
            int x = jsonObject.get("x").getAsInt();
            int y = jsonObject.get("y").getAsInt();
            int z = jsonObject.get("z").getAsInt();
            return new BlockPos(x, y, z);
        }
    }
}