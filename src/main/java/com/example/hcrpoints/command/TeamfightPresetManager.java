package com.example.hcrpoints.command;

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
 * 行动攻防模式预设管理器
 * 负责保存和加载行动攻防模式的预设，包括计划据点和队伍角色
 */
public class TeamfightPresetManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter())
            .create();
    
    private static final String PRESETS_DIR = "config/hcrpoints/presets";
    
    /**
     * 保存行动攻防模式预设
     * @param presetId 预设ID
     * @return 是否保存成功
     */
    public static boolean savePreset(int presetId) {
        try {
            // 获取当前状态
        CapturePointManager manager = CapturePointManager.getInstance();
        Map<String, String> teamRoles = manager.getTeamRoles();
        Map<String, Integer> teamReinforcements = manager.getTeamReinforcementsMap(); // 新增：获取队伍兵力数据
        
        // 转换计划据点为可序列化的格式
        Map<String, SerializablePlannedPoint> serializablePlannedPoints = new HashMap<>();
        for (Map.Entry<String, ?> entry : manager.getPlannedPointsMap().entrySet()) {
            Object plannedPoint = entry.getValue();
            try {
                // 使用反射获取计划据点的属性
                java.lang.reflect.Field nameField = plannedPoint.getClass().getDeclaredField("name");
                java.lang.reflect.Field pos1Field = plannedPoint.getClass().getDeclaredField("pos1");
                java.lang.reflect.Field pos2Field = plannedPoint.getClass().getDeclaredField("pos2");
                java.lang.reflect.Field batchField = plannedPoint.getClass().getDeclaredField("batch");
                
                nameField.setAccessible(true);
                pos1Field.setAccessible(true);
                pos2Field.setAccessible(true);
                batchField.setAccessible(true);
                
                String name = (String) nameField.get(plannedPoint);
                BlockPos pos1 = (BlockPos) pos1Field.get(plannedPoint);
                BlockPos pos2 = (BlockPos) pos2Field.get(plannedPoint);
                int batch = (int) batchField.get(plannedPoint);
                
                serializablePlannedPoints.put(name, new SerializablePlannedPoint(name, pos1, pos2, batch));
            } catch (Exception e) {
                ModLogger.error("获取计划据点属性失败: " + e.getMessage());
                return false;
            }
        }
        
        // 创建预设数据对象
        PresetData presetData = new PresetData(serializablePlannedPoints, teamRoles, teamReinforcements); // 新增：传递队伍兵力数据
            
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
            
            ModLogger.info("行动攻防模式预设 " + presetId + " 已保存，包含 " + serializablePlannedPoints.size() + " 个计划据点、" + teamRoles.size() + " 个队伍角色设置和 " + teamReinforcements.size() + " 个队伍兵力设置");
            return true;
        } catch (IOException e) {
            ModLogger.error("保存预设时发生异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 加载行动攻防模式预设
     * @param presetId 预设ID
     * @return 是否加载成功
     */
    public static boolean loadPreset(int presetId) {
        try {
            // 检查文件是否存在
            File presetFile = new File(PRESETS_DIR, "preset_" + presetId + ".json");
            if (!presetFile.exists()) {
                ModLogger.warn("预设文件不存在: " + presetFile.getAbsolutePath());
                return false;
            }
            
            // 读取预设数据
            PresetData presetData;
            try (FileReader reader = new FileReader(presetFile)) {
                Type presetDataType = new TypeToken<PresetData>() {}.getType();
                presetData = GSON.fromJson(reader, presetDataType);
            }
            
            if (presetData == null) {
                ModLogger.error("预设文件格式错误: " + presetFile.getAbsolutePath());
                return false;
            }
            
            // 恢复到管理器
            CapturePointManager manager = CapturePointManager.getInstance();
            
            // 清空现有数据
            manager.clearPlannedCapturePoints();
            manager.clearTeamRoles();
            
            // 恢复计划据点
            for (Map.Entry<String, SerializablePlannedPoint> entry : presetData.getPlannedPoints().entrySet()) {
                SerializablePlannedPoint serializablePoint = entry.getValue();
                manager.addPlannedCapturePoint(
                    serializablePoint.getName(),
                    serializablePoint.getPos1(),
                    serializablePoint.getPos2(),
                    serializablePoint.getBatch()
                );
            }
            
            // 恢复队伍角色和兵力
            Map<String, Integer> teamReinforcements = presetData.getTeamReinforcements();
            for (Map.Entry<String, String> entry : presetData.getTeamRoles().entrySet()) {
                String team = entry.getKey();
                String role = entry.getValue();
                // 从teamReinforcements中获取兵力，如果没有则使用默认值50
                int reinforcements = teamReinforcements != null ? teamReinforcements.getOrDefault(team, 50) : 50;
                manager.setTeamRole(team, role, reinforcements);
            }
            
            ModLogger.info("行动攻防模式预设 " + presetId + " 已加载，包含 " + presetData.getPlannedPoints().size() + " 个计划据点和 " + presetData.getTeamRoles().size() + " 个队伍角色设置");
            return true;
        } catch (IOException e) {
            ModLogger.error("加载预设时发生异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 预设数据类
     */
    private static class PresetData {
        private Map<String, SerializablePlannedPoint> plannedPoints;
        private Map<String, String> teamRoles;
        private Map<String, Integer> teamReinforcements; // 新增：保存队伍兵力数据
        
        public PresetData(Map<String, SerializablePlannedPoint> plannedPoints, Map<String, String> teamRoles, Map<String, Integer> teamReinforcements) {
            this.plannedPoints = plannedPoints;
            this.teamRoles = teamRoles;
            this.teamReinforcements = teamReinforcements;
        }
        
        public Map<String, SerializablePlannedPoint> getPlannedPoints() {
            return plannedPoints;
        }
        
        public Map<String, String> getTeamRoles() {
            return teamRoles;
        }
        
        // 新增：获取队伍兵力数据
        public Map<String, Integer> getTeamReinforcements() {
            return teamReinforcements;
        }
    }
    
    /**
     * 可序列化的计划据点类
     */
    private static class SerializablePlannedPoint {
        private String name;
        private BlockPos pos1;
        private BlockPos pos2;
        private int batch;
        
        public SerializablePlannedPoint(String name, BlockPos pos1, BlockPos pos2, int batch) {
            this.name = name;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.batch = batch;
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
        
        public int getBatch() {
            return batch;
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