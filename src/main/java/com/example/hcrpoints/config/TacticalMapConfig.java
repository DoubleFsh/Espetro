package com.example.hcrpoints.config;

import com.example.hcrpoints.hud.MapDisplayMode;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 战术地图配置类
 */
public class TacticalMapConfig {
    // 配置规范构建器
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    // 显示模式配置
    public static final ForgeConfigSpec.EnumValue<MapDisplayMode> displayMode;
    
    // 迷你地图缩放比例配置 (百分比)
    public static final ForgeConfigSpec.IntValue miniMapScale;
    
    // 攻方进度条颜色配置
    public static final ForgeConfigSpec.ConfigValue<String> attackerProgressBarColor;
    
    // 守方进度条颜色配置
    public static final ForgeConfigSpec.ConfigValue<String> defenderProgressBarColor;
    
    static {
        // 开始构建配置
        BUILDER.push("tacticalMap");
        
        // 显示模式配置
        displayMode = BUILDER
                .comment("战术地图显示模式")
                .defineEnum("displayMode", MapDisplayMode.TOGGLE_KEY);
        
        // 迷你地图缩放比例配置 (25-100，默认75)
        miniMapScale = BUILDER
                .comment("迷你地图缩放比例 (百分比)")
                .defineInRange("miniMapScale", 75, 25, 100);
        
        // 攻方进度条颜色配置
        attackerProgressBarColor = BUILDER
                .comment("攻方进度条颜色 (十六进制，如：#FF5500)")
                .define("attackerProgressBarColor", "#FF5500");
        
        // 守方进度条颜色配置
        defenderProgressBarColor = BUILDER
                .comment("守方进度条颜色 (十六进制，如：#0055FF)")
                .define("defenderProgressBarColor", "#0055FF");
        
        // 结束构建配置
        BUILDER.pop();
        
        // 构建配置规范
        SPEC = BUILDER.build();
    }
}