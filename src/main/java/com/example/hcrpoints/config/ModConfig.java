package com.example.hcrpoints.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 模组配置类
 */
public class ModConfig {
    // HUD配置
    public static ForgeConfigSpec.BooleanValue enableHUD;
    public static ForgeConfigSpec.BooleanValue enableCarousel;
    
    // 队伍配置
    public static ForgeConfigSpec.BooleanValue enableTeams;
    public static ForgeConfigSpec.BooleanValue enableTeamIndicator;
    
    // 性能配置
    public static ForgeConfigSpec.IntValue checkInterval;
    
    // 奖励配置
    public static ForgeConfigSpec.IntValue pointRewardInterval;
    public static ForgeConfigSpec.IntValue pointRewardAmount;
    public static ForgeConfigSpec.IntValue killRewardAmount;
    public static ForgeConfigSpec.IntValue captureRewardAmount;
    public static ForgeConfigSpec.IntValue capturedRewardInterval;
    public static ForgeConfigSpec.IntValue capturedRewardAmount;
    public static ForgeConfigSpec.IntValue capturedRewardDelay;
    public static ForgeConfigSpec.IntValue friendlyFirePenalty;
    public static ForgeConfigSpec.BooleanValue enableFriendlyFirePenalty;
    
    // 行动攻防机制配置
    public static ForgeConfigSpec.BooleanValue enableOperationMode;
    public static ForgeConfigSpec.DoubleValue lowReinforcementThreshold;
    
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    static {
        BUILDER.push("HUD Settings");
        enableHUD = BUILDER
                .comment("启用HUD显示")
                .define("enableHUD", true);
        enableCarousel = BUILDER
                .comment("启用据点信息轮播")
                .define("enableCarousel", true);
        BUILDER.pop();
        
        BUILDER.push("Team Settings");
        enableTeams = BUILDER
                .comment("启用队伍机制，避免同队伍玩家互相争夺同一据点")
                .define("enableTeams", false);
        enableTeamIndicator = BUILDER
                .comment("在玩家头顶显示敌我标识（友军绿色箭头/敌军红色倒三角），需要队伍机制同时启用")
                .define("enableTeamIndicator", true);
        BUILDER.pop();
        
        BUILDER.push("Performance Settings");
        checkInterval = BUILDER
                .comment("据点检查间隔（tick）")
                .defineInRange("checkInterval", 5, 1, 100);
        BUILDER.pop();
        
        BUILDER.push("Reward Settings");
        pointRewardInterval = BUILDER
                .comment("据点内获得点数的时间间隔（秒）")
                .defineInRange("pointRewardInterval", 60, 1, 3600);
        pointRewardAmount = BUILDER
                .comment("据点内每次获得的点数")
                .defineInRange("pointRewardAmount", 5, 1, 1000);
        killRewardAmount = BUILDER
                .comment("击杀玩家获得的点数")
                .defineInRange("killRewardAmount", 50, 1, 1000);
        captureRewardAmount = BUILDER
                .comment("占领据点获得的点数")
                .defineInRange("captureRewardAmount", 100, 1, 1000);
        capturedRewardInterval = BUILDER
                .comment("占领据点后，获得持续奖励的时间间隔（秒）")
                .defineInRange("capturedRewardInterval", 60, 1, 3600);
        capturedRewardAmount = BUILDER
                .comment("占领据点后，每次获得的持续奖励点数")
                .defineInRange("capturedRewardAmount", 10, 1, 1000);
        capturedRewardDelay = BUILDER
                .comment("占领据点后，开始获得持续奖励的延迟时间（秒）")
                .defineInRange("capturedRewardDelay", 5, 1, 3600);
        enableFriendlyFirePenalty = BUILDER
                .comment("启用友军击杀惩罚")
                .define("enableFriendlyFirePenalty", true);
        friendlyFirePenalty = BUILDER
                .comment("友军击杀扣除的点数")
                .defineInRange("friendlyFirePenalty", 200, 1, 10000);
        BUILDER.pop();
        
        BUILDER.push("Operation Settings");
        enableOperationMode = BUILDER
                .comment("启用行动攻防机制，类似于战地1的行动模式")
                .define("enableOperationMode", false);
        lowReinforcementThreshold = BUILDER
                .comment("当一方兵力低于此百分比时播放背水一战背景音乐（0-100，设置为0则不播放）")
                .defineInRange("lowReinforcementThreshold", 10.0, 0.0, 100.0);
        BUILDER.pop();
        
        SPEC = BUILDER.build();
    }
}