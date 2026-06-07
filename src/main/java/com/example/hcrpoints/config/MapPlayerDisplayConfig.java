package com.example.hcrpoints.config;

import com.example.hcrpoints.util.ModLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 地图玩家显示配置类
 * 用于管理地图显示玩家位置的状态，并持久化到JSON文件
 */
public class MapPlayerDisplayConfig {
    // 配置文件名
    private static final String CONFIG_FILE_NAME = "hcr_map_player_display.json";
    
    // 默认配置值
    private boolean showPlayerLocations = true;
    
    // 单例实例
    private static MapPlayerDisplayConfig instance;
    
    private MapPlayerDisplayConfig() {
        // 私有构造函数
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized MapPlayerDisplayConfig getInstance() {
        if (instance == null) {
            instance = new MapPlayerDisplayConfig();
            instance.loadConfig();
        }
        return instance;
    }
    
    /**
     * 加载配置
     */
    public void loadConfig() {
        try {
            Path configPath = getConfigPath();
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath, StandardCharsets.UTF_8);
                Gson gson = new Gson();
                MapPlayerDisplayConfig loadedConfig = gson.fromJson(content, MapPlayerDisplayConfig.class);
                if (loadedConfig != null) {
                    this.showPlayerLocations = loadedConfig.showPlayerLocations;
                    ModLogger.info("地图玩家显示配置已加载: showPlayerLocations = " + this.showPlayerLocations);
                }
            } else {
                // 配置文件不存在，使用默认值并保存
                saveConfig();
            }
        } catch (IOException e) {
            ModLogger.error("加载地图玩家显示配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 保存配置
     */
    public void saveConfig() {
        try {
            Path configPath = getConfigPath();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String content = gson.toJson(this);
            Files.writeString(configPath, content, StandardCharsets.UTF_8);
            ModLogger.info("地图玩家显示配置已保存: showPlayerLocations = " + this.showPlayerLocations);
        } catch (IOException e) {
            ModLogger.error("保存地图玩家显示配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取配置文件路径
     */
    private Path getConfigPath() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return Paths.get(server.getServerDirectory().getAbsolutePath(), "config", CONFIG_FILE_NAME);
        }
        // 客户端或服务端未启动时，使用当前目录
        return Paths.get("config", CONFIG_FILE_NAME);
    }
    
    /**
     * 获取是否显示玩家位置
     */
    public boolean isShowPlayerLocations() {
        return showPlayerLocations;
    }
    
    /**
     * 设置是否显示玩家位置
     */
    public void setShowPlayerLocations(boolean showPlayerLocations) {
        this.showPlayerLocations = showPlayerLocations;
        saveConfig();
    }
}