package com.example.hcrpoints.command;

import com.example.hcrpoints.capturepoint.CapturePoint;
import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.example.hcrpoints.config.ModConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * HCR模组命令类
 */
public class HCRCommand {
    
    /**
     * 注册命令
     * @param dispatcher 命令调度器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("hcrpi")
                .then(Commands.literal("help")
                    .executes(HCRCommand::executeHelp))
                // 新增send命令，向指定玩家发送消息弹窗
                .then(Commands.literal("send")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(HCRCommand::suggestExistingPlayers)
                        .then(Commands.argument("borderColor", StringArgumentType.word())
                            .suggests(HCRCommand::suggestBorderColors)
                            .then(Commands.argument("content", StringArgumentType.greedyString())
                                .executes(HCRCommand::executeSend)))))
                // 新增playsound命令，用于直接播放音频
                .then(Commands.literal("playsound")
                    .then(Commands.argument("soundName", StringArgumentType.word())
                        .suggests(HCRCommand::suggestSoundNames)
                        .executes(HCRCommand::executePlaySound)))
                // 原命令，行动攻防机制启用时提示使用teamfight子命令
                .then(Commands.literal("create")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                            .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                .executes(HCRCommand::executeCreate)))))
                .then(Commands.literal("clear")
                    .executes(HCRCommand::executeClear))
                .then(Commands.literal("list")
                    .executes(HCRCommand::executeList))
                .then(Commands.literal("del")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(HCRCommand::executeDel)))
                // 新增save命令，用于保存普通据点预设
                .then(Commands.literal("save")
                    .then(Commands.argument("presetId", IntegerArgumentType.integer(1))
                        .executes(HCRCommand::executeSave)))
                // 新增load命令，用于加载普通据点预设
                .then(Commands.literal("load")
                    .then(Commands.argument("presetId", IntegerArgumentType.integer(1))
                        .executes(HCRCommand::executeLoad)))
                // 新增mapctrl命令，控制地图是否显示玩家位置
                .then(Commands.literal("mapctrl")
                    .then(Commands.argument("state", StringArgumentType.word())
                        .suggests(HCRCommand::suggestBooleanValues)
                        .executes(HCRCommand::executeMapCtrl)))
                // 新增reload命令，重新加载配置文件
                .then(Commands.literal("reload")
                    .executes(HCRCommand::executeReload))
                // 新增teamfight子命令，用于行动攻防模式
                .then(Commands.literal("teamfight")
                    .requires(source -> ModConfig.enableOperationMode.get() || hasPermission(source, 4))
                    .then(Commands.literal("create")
                        .then(Commands.argument("batch", IntegerArgumentType.integer(1))
                            .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                    .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                        .executes(HCRCommand::executeTeamfightCreate)))
                            )
                        )
                    )
                    .then(Commands.literal("list")
                        .executes(HCRCommand::executeTeamfightList))
                    .then(Commands.literal("del")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(HCRCommand::executeTeamfightDel)))
                    .then(Commands.literal("clear")
                        .executes(HCRCommand::executeTeamfightClear))
                    .then(Commands.literal("teamconfig")
                        .then(Commands.argument("team", StringArgumentType.word())
                            .suggests(HCRCommand::suggestExistingTeams)
                            .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(HCRCommand::suggestTeamRoles)
                                .then(Commands.argument("reinforcements", IntegerArgumentType.integer(1))
                                    .executes(HCRCommand::executeTeamfightTeamConfig)))))
                    .then(Commands.literal("start")
                        .then(Commands.argument("totalBatches", IntegerArgumentType.integer(1))
                            .then(Commands.argument("endBehavior", StringArgumentType.word())
                                .suggests(HCRCommand::suggestEndBehavior)
                                .executes(HCRCommand::executeTeamfightStart))))
                    .then(Commands.literal("stop")
                        .executes(HCRCommand::executeTeamfightStop))
                    .then(Commands.literal("nextbatch")
                        .executes(HCRCommand::executeTeamfightNextBatch))
                    // 新增save/load命令，用于保存和加载预设
                    .then(Commands.literal("save")
                        .then(Commands.argument("preset", IntegerArgumentType.integer(1))
                            .executes(HCRCommand::executeTeamfightSave)))
                    .then(Commands.literal("load")
                        .then(Commands.argument("preset", IntegerArgumentType.integer(1))
                            .executes(HCRCommand::executeTeamfightLoad))))
                .executes(HCRCommand::executeHelp)
        );
    }
    
    /**
     * 执行帮助命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("HCR据点争夺模组命令帮助"), false);
        source.sendSuccess(() -> Component.literal("/hcrpi create <名称> <x1> <y1> <z1> <x2> <y2> <z2> - 创建据点（名称为单个大写字母，最多7个）"), false);
        source.sendSuccess(() -> Component.literal("/hcrpi del <名称> - 删除指定名称的据点"), false);
        source.sendSuccess(() -> Component.literal("/hcrpi clear - 清空所有据点（同步更新全客户端HUD）"), false);
        source.sendSuccess(() -> Component.literal("/hcrpi list - 列出所有据点信息（名称/状态/进度）"), false);
        source.sendSuccess(() -> Component.literal("/hcrpi save <序号> - 保存当前据点设置为预设"), false);
        source.sendSuccess(() -> Component.literal("/hcrpi load <序号> - 加载指定序号的据点预设"), false);
        source.sendSuccess(() -> Component.literal("/hcrpi send <玩家> <边框颜色> <内容> - 向指定玩家发送消息弹窗"), false);
        source.sendSuccess(() -> Component.literal("/hcrpi playsound <音效名> - 直接播放指定音效，例如：/hcrpi playsound lastStandBGM"), false);
        
        return 1;
    }
    
    /**
     * 执行playsound命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executePlaySound(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取音效名称
        String soundName = StringArgumentType.getString(context, "soundName");
        
        // 目前只支持lastStandBGM
        if (!soundName.equalsIgnoreCase("lastStandBGM")) {
            source.sendFailure(Component.literal("不支持的音效名称！当前只支持：lastStandBGM"));
            return 0;
        }
        
        // 发送音频播放消息给所有玩家
        com.example.hcrpoints.network.PlayLowReinforcementAudioMessage.broadcastToAll(true);
        
        source.sendSuccess(() -> Component.literal("已向所有玩家发送音频播放指令：" + soundName), true);
        return 1;
    }
    
    /**
     * 为playsound命令的soundName参数添加自动补全
     * @param context 命令上下文
     * @param builder 补全建议构建器
     * @return 补全建议的CompletableFuture
     */
    private static CompletableFuture<Suggestions> suggestSoundNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 目前只支持lastStandBGM
        builder.suggest("lastStandBGM", Component.literal("背水一战背景音乐"));
        
        return builder.buildFuture();
    }
    
    /**
     * 检查行动攻防机制是否启用，若启用则提示使用teamfight子命令
     * @param source 命令源
     * @return 如果需要阻止命令执行返回true，否则返回false
     */
    private static boolean checkOperationMode(CommandSourceStack source) {
        if (ModConfig.enableOperationMode.get()) {
            source.sendFailure(Component.literal("行动攻防机制已启用，需使用/hcrpi teamfight指令"));
            return true;
        }
        return false;
    }
    
    /**
     * 执行创建据点命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeCreate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查行动攻防机制是否启用
        if (checkOperationMode(source)) {
            return 0;
        }
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取参数
        String name = StringArgumentType.getString(context, "name");
        BlockPos pos1 = BlockPosArgument.getBlockPos(context, "pos1");
        BlockPos pos2 = BlockPosArgument.getBlockPos(context, "pos2");
        
        // 获取据点管理器
        CapturePointManager manager = CapturePointManager.getInstance();
        
        // 验证据点名称
        if (!manager.isValidPointName(name)) {
            source.sendFailure(Component.literal("创建失败，据点名称必须为单个大写字母（A-Z）"));
            return 0;
        }
        
        // 验证坐标
        if (!manager.isValidCoordinates(pos1, pos2)) {
            source.sendFailure(Component.literal("创建失败，两点需构成有效长方体区域"));
            return 0;
        }
        
        // 检查据点数量
        if (manager.getCapturePointCount() >= 7) {
            source.sendFailure(Component.literal("创建失败，已达最大据点数量（7个）"));
            return 0;
        }
        
        // 检查据点名称是否已存在
        if (manager.getCapturePoint(name) != null) {
            source.sendFailure(Component.literal("创建失败，据点名称【" + name + "】已存在"));
            return 0;
        }
        
        // 创建据点
        CapturePoint point = manager.createCapturePoint(name, pos1, pos2);
        if (point == null) {
            source.sendFailure(Component.literal("创建失败，未知错误"));
            return 0;
        }
        
        // 发送成功消息
        source.sendSuccess(() -> Component.literal("据点【" + name + "】创建成功，区域：(" + 
            pos1.getX() + "," + pos1.getY() + "," + pos1.getZ() + ")-(" + 
            pos2.getX() + "," + pos2.getY() + "," + pos2.getZ() + ")"), true);
        
        return 1;
    }
    
    /**
     * 执行清空据点命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查行动攻防机制是否启用
        if (checkOperationMode(source)) {
            return 0;
        }
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取据点管理器并清空所有据点
        CapturePointManager manager = CapturePointManager.getInstance();
        manager.clearAllCapturePoints();
        
        // 发送成功消息
        source.sendSuccess(() -> Component.literal("所有据点已清空，客户端HUD已同步更新"), true);
        
        return 1;
    }
    
    /**
     * 执行删除指定据点命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeDel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查行动攻防机制是否启用
        if (checkOperationMode(source)) {
            return 0;
        }
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取参数
        String name = StringArgumentType.getString(context, "name");
        
        // 获取据点管理器
        CapturePointManager manager = CapturePointManager.getInstance();
        
        // 检查据点是否存在
        if (manager.getCapturePoint(name) == null) {
            source.sendFailure(Component.literal("删除失败，未找到名称为【" + name + "】的据点"));
            return 0;
        }
        
        // 删除据点
        boolean success = manager.removeCapturePoint(name);
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("据点【" + name + "】已成功删除，客户端HUD已同步更新"), true);
            return 1;
        } else {
            // 发送失败消息
            source.sendFailure(Component.literal("删除据点【" + name + "】失败，未知错误"));
            return 0;
        }
    }
    
    /**
     * 执行列出据点命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查行动攻防机制是否启用
        if (checkOperationMode(source)) {
            return 0;
        }
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取据点管理器
        CapturePointManager manager = CapturePointManager.getInstance();
        
        // 检查是否有据点
        if (manager.getCapturePointCount() == 0) {
            source.sendSuccess(() -> Component.literal("当前无已创建据点"), false);
            return 1;
        }
        
        // 发送据点列表
        source.sendSuccess(() -> Component.literal("当前据点列表（共" + manager.getCapturePointCount() + "个）"), false);
        
        int index = 1;
        for (CapturePoint point : manager.getAllCapturePoints()) {
            final int currentIndex = index;
            source.sendSuccess(() -> Component.literal(currentIndex + ". " + point.getInfoString()), false);
            index++;
        }
        
        return 1;
    }
    
    /**
     * 执行保存普通据点预设命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeSave(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查行动攻防机制是否启用
        if (checkOperationMode(source)) {
            return 0;
        }
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取预设ID
        int presetId = IntegerArgumentType.getInteger(context, "presetId");
        
        // 保存预设
        boolean success = CapturePointPresetManager.savePreset(presetId);
        
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("普通据点预设 " + presetId + " 已成功保存！"), true);
        } else {
            // 发送失败消息
            source.sendFailure(Component.literal("保存预设失败！请查看服务器日志获取详细信息"));
        }
        
        return 1;
    }
    
    /**
     * 执行加载普通据点预设命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeLoad(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查行动攻防机制是否启用
        if (checkOperationMode(source)) {
            return 0;
        }
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取预设ID
        int presetId = IntegerArgumentType.getInteger(context, "presetId");
        
        // 加载预设
        boolean success = CapturePointPresetManager.loadPreset(presetId);
        
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("普通据点预设 " + presetId + " 已成功加载！"), true);
        } else {
            // 发送失败消息
            source.sendFailure(Component.literal("加载预设失败！预设文件可能不存在或格式错误"));
        }
        
        return 1;
    }
    
    /**
     * 检查命令执行者是否具有指定权限等级
     * @param source 命令源
     * @param level 权限等级
     * @return 是否具有权限
     */
    private static boolean hasPermission(CommandSourceStack source, int level) {
        // 检查是否为玩家
        if (!(source.getEntity() instanceof ServerPlayer)) {
            // 控制台默认具有最高权限
            return true;
        }
        
        ServerPlayer player = (ServerPlayer) source.getEntity();
        
        // 使用Forge的权限API检查权限等级
        // 这里简化处理，实际应使用Forge的权限系统
        return source.hasPermission(level);
    }
    
    /**
     * 执行创建带批次的据点命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightCreate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取参数
        int batch = IntegerArgumentType.getInteger(context, "batch");
        String name = StringArgumentType.getString(context, "name");
        BlockPos pos1 = BlockPosArgument.getBlockPos(context, "pos1");
        BlockPos pos2 = BlockPosArgument.getBlockPos(context, "pos2");
        
        // 获取据点管理器
        CapturePointManager manager = CapturePointManager.getInstance();
        
        // 验证据点名称
        if (!manager.isValidPointName(name)) {
            source.sendFailure(Component.literal("创建失败，据点名称必须为单个大写字母（A-Z）"));
            return 0;
        }
        
        // 验证坐标
        if (!manager.isValidCoordinates(pos1, pos2)) {
            source.sendFailure(Component.literal("创建失败，两点需构成有效长方体区域"));
            return 0;
        }
        
        // 添加到计划列表
        boolean success = manager.addPlannedCapturePoint(name, pos1, pos2, batch);
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("据点【" + name + "】（批次 " + batch + "）已添加到计划，区域：(" + 
                pos1.getX() + "," + pos1.getY() + "," + pos1.getZ() + ")-(" + 
                pos2.getX() + "," + pos2.getY() + "," + pos2.getZ() + ")"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("创建失败，据点名称【" + name + "】已存在于计划中"));
            return 0;
        }
    }
    
    /**
     * 执行列出据点命令（行动模式）
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取据点管理器
        CapturePointManager manager = CapturePointManager.getInstance();
        
        // 获取计划据点信息
        List<String> plannedPointsInfo = manager.getPlannedPointsInfo();
        
        // 检查是否有计划据点
        if (plannedPointsInfo.isEmpty()) {
            source.sendSuccess(() -> Component.literal("当前无计划据点"), false);
            return 1;
        }
        
        // 发送计划据点列表
        source.sendSuccess(() -> Component.literal("行动模式计划据点列表："), false);
        
        for (String info : plannedPointsInfo) {
            source.sendSuccess(() -> Component.literal(info), false);
        }
        
        return 1;
    }
    
    /**
     * 执行删除据点命令（行动模式）
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightDel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取参数
        String name = StringArgumentType.getString(context, "name");
        
        // 获取据点管理器
        CapturePointManager manager = CapturePointManager.getInstance();
        
        // 从计划中移除据点
        boolean success = manager.removePlannedCapturePoint(name);
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("计划据点【" + name + "】已成功移除"), true);
            return 1;
        } else {
            // 发送失败消息
            source.sendFailure(Component.literal("删除失败，未找到名称为【" + name + "】的计划据点"));
            return 0;
        }
    }
    
    /**
     * 执行清空据点命令（行动模式）
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取据点管理器并清空所有计划据点
        CapturePointManager manager = CapturePointManager.getInstance();
        manager.clearPlannedCapturePoints();
        
        // 发送成功消息
        source.sendSuccess(() -> Component.literal("所有计划据点已清空"), true);
        
        return 1;
    }
    
    /**
     * 执行设置队伍角色命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightTeamConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取参数
        String team = StringArgumentType.getString(context, "team");
        String role = StringArgumentType.getString(context, "role");
        int reinforcements = IntegerArgumentType.getInteger(context, "reinforcements");
        
        // 调用CapturePointManager设置队伍角色和兵力
        CapturePointManager manager = CapturePointManager.getInstance();
        boolean success = manager.setTeamRole(team, role, reinforcements);
        
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("队伍【" + team + "】已设置为" + role + "角色，兵力：" + reinforcements), true);
            return 1;
        } else {
            // 发送失败消息
            source.sendFailure(Component.literal("设置失败，已有队伍被设置为" + role + "角色或角色类型无效"));
            return 0;
        }
    }
    
    /**
     * 执行启动行动命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightStart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 检查是否已经设置了攻守两方队伍
        CapturePointManager manager = CapturePointManager.getInstance();
        if (!manager.hasBothRolesSet()) {
            source.sendFailure(Component.literal("启动失败，请先设置进攻方和防守方队伍"));
            return 0;
        }
        
        // 获取命令参数
        int totalBatches = IntegerArgumentType.getInteger(context, "totalBatches");
        String endBehavior = StringArgumentType.getString(context, "endBehavior");
        
        // 验证结束行为参数
        if (!endBehavior.equalsIgnoreCase("terminate") && !endBehavior.equalsIgnoreCase("loop")) {
            source.sendFailure(Component.literal("无效的结束行为！请使用 'terminate'(终止) 或 'loop'(循环)"));
            return 0;
        }
        
        // 调用CapturePointManager启动行动
        manager.startOperationMode(totalBatches, endBehavior);
        
        // 发送成功消息
        source.sendSuccess(() -> Component.literal("行动已启动！当前批次：1, 总批数：" + totalBatches + ", 结束行为：" + endBehavior), true);
        
        return 1;
    }
    
    /**
     * 为teamconfig命令的team参数添加自动补全
     * @param context 命令上下文
     * @param builder 补全建议构建器
     * @return 补全建议的CompletableFuture
     */
    private static CompletableFuture<Suggestions> suggestExistingTeams(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 获取服务器实例
        net.minecraft.server.MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return builder.buildFuture();
        }
        
        // 获取服务器的Scoreboard
        net.minecraft.world.scores.Scoreboard scoreboard = server.getScoreboard();
        
        // 为每个存在的队伍添加补全建议
        for (net.minecraft.world.scores.Team team : scoreboard.getPlayerTeams()) {
            builder.suggest(team.getName());
        }
        
        return builder.buildFuture();
    }
    
    /**
     * 为teamconfig命令的role参数添加自动补全
     * @param context 命令上下文
     * @param builder 补全建议构建器
     * @return 补全建议的CompletableFuture
     */
    private static CompletableFuture<Suggestions> suggestTeamRoles(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 只提供attacker和defender作为角色选项
        builder.suggest("attacker");
        builder.suggest("defender");
        
        return builder.buildFuture();
    }
    
    /**
     * 为teamfight start命令的endBehavior参数添加自动补全
     * @param context 命令上下文
     * @param builder 补全建议构建器
     * @return 补全建议的CompletableFuture
     */
    private static CompletableFuture<Suggestions> suggestEndBehavior(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 只提供terminate和loop作为结束行为选项
        builder.suggest("terminate");
        builder.suggest("loop");
        
        return builder.buildFuture();
    }
    
    /**
     * 执行停止行动命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightStop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 调用CapturePointManager停止行动
        CapturePointManager manager = CapturePointManager.getInstance();
        manager.stopOperationMode();
        
        // 发送成功消息
        source.sendSuccess(() -> Component.literal("行动已停止！"), true);
        
        return 1;
    }
    
    /**
     * 执行进入下一批次命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightNextBatch(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 调用CapturePointManager进入下一批次
        CapturePointManager manager = CapturePointManager.getInstance();
        boolean success = manager.nextBatch();
        
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("已进入下一批次！当前批次：" + manager.getCurrentBatch()), true);
        } else {
            // 发送失败消息
            source.sendFailure(Component.literal("没有更多批次了！"));
        }
        
        return 1;
    }
    
    /**
     * 执行保存预设命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightSave(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取预设ID
        int presetId = IntegerArgumentType.getInteger(context, "preset");
        
        // 保存预设
        boolean success = TeamfightPresetManager.savePreset(presetId);
        
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("行动攻防模式预设 " + presetId + " 已成功保存！"), true);
        } else {
            // 发送失败消息
            source.sendFailure(Component.literal("保存预设失败！请查看服务器日志获取详细信息"));
        }
        
        return 1;
    }
    
    /**
     * 执行加载预设命令
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeTeamfightLoad(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取预设ID
        int presetId = IntegerArgumentType.getInteger(context, "preset");
        
        // 加载预设
        boolean success = TeamfightPresetManager.loadPreset(presetId);
        
        if (success) {
            // 发送成功消息
            source.sendSuccess(() -> Component.literal("行动攻防模式预设 " + presetId + " 已成功加载！"), true);
        } else {
            // 发送失败消息
            source.sendFailure(Component.literal("加载预设失败！预设文件可能不存在或格式错误"));
        }
        
        return 1;
    }
    
    /**
     * 为send命令的player参数添加自动补全
     * @param context 命令上下文
     * @param builder 补全建议构建器
     * @return 补全建议的CompletableFuture
     */
    private static CompletableFuture<Suggestions> suggestExistingPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 获取服务器实例
        net.minecraft.server.MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return builder.buildFuture();
        }
        
        // 为每个在线玩家添加补全建议
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            builder.suggest(player.getGameProfile().getName());
        }
        
        return builder.buildFuture();
    }
    
    /**
     * 为send命令的borderColor参数添加自动补全
     * @param context 命令上下文
     * @param builder 补全建议构建器
     * @return 补全建议的CompletableFuture
     */
    private static CompletableFuture<Suggestions> suggestBorderColors(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 添加常用颜色的十六进制代码作为建议
        // 红色系
        builder.suggest("FF0000", Component.literal("红色"));
        builder.suggest("FF5500", Component.literal("橙色"));
        builder.suggest("FFFF00", Component.literal("黄色"));
        // 绿色系
        builder.suggest("00FF00", Component.literal("绿色"));
        builder.suggest("00FF55", Component.literal("浅绿色"));
        builder.suggest("00AA00", Component.literal("深绿色"));
        // 蓝色系
        builder.suggest("0000FF", Component.literal("蓝色"));
        builder.suggest("5500FF", Component.literal("紫色"));
        builder.suggest("0055FF", Component.literal("深蓝色"));
        // 其他常用颜色
        builder.suggest("FF00FF", Component.literal("粉色"));
        builder.suggest("FFFFFF", Component.literal("白色"));
        builder.suggest("888888", Component.literal("灰色"));
        builder.suggest("00FFFF", Component.literal("青色"));
        
        return builder.buildFuture();
    }
    
    /**
     * 执行send命令，向指定玩家发送消息弹窗
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeSend(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 检查权限
        if (!hasPermission(source, 2)) {
            source.sendFailure(Component.literal("权限不足，需2级管理员权限"));
            return 0;
        }
        
        // 获取命令参数
        String playerName = StringArgumentType.getString(context, "player");
        String borderColorHex = StringArgumentType.getString(context, "borderColor");
        String content = StringArgumentType.getString(context, "content");
        
        // 查找玩家
        net.minecraft.server.MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("无法获取服务器实例"));
            return 0;
        }
        
        ServerPlayer targetPlayer = server.getPlayerList().getPlayerByName(playerName);
        if (targetPlayer == null) {
            source.sendFailure(Component.literal("未找到玩家：" + playerName));
            return 0;
        }
        
        // 验证边框颜色格式（十六进制，不带#，6位字符）
        if (!borderColorHex.matches("[0-9A-Fa-f]{6}")) {
            source.sendFailure(Component.literal("无效的边框颜色格式，必须是6位十六进制字符（不带#），例如：FF5500"));
            return 0;
        }
        
        // 转换为整数颜色值
        int borderColor;
        try {
            borderColor = Integer.parseInt(borderColorHex, 16);
            // 添加不透明前缀
            borderColor = 0xFF000000 | borderColor;
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("无效的边框颜色值"));
            return 0;
        }
        
        // 使用HCRAPI发送消息弹窗，显示4秒
        com.example.hcrpoints.api.HCRAPI.showMessage(
            targetPlayer.getUUID(),
            content,
            4000,
            150, 40,
            0xFF000000, // 黑色背景
            0xFFFFFFFF, // 白色文字
            borderColor, // 自定义边框颜色
            2           // 边框宽度
        );
        
        // 发送成功消息
        source.sendSuccess(() -> Component.literal("已向玩家" + playerName + "发送消息：" + content), true);
        
        return 1;
    }
    
    /**
     * 提供布尔值建议（true/false）
     */
    private static CompletableFuture<Suggestions> suggestBooleanValues(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        List<String> booleanValues = new ArrayList<>();
        booleanValues.add("true");
        booleanValues.add("false");
        
        for (String value : booleanValues) {
            if (value.startsWith(builder.getRemaining())) {
                builder.suggest(value);
            }
        }
        
        return builder.buildFuture();
    }
    
    /**
     * 执行mapctrl命令，控制地图是否显示玩家位置
     */
    private static int executeMapCtrl(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String stateStr = context.getArgument("state", String.class);
        
        // 解析布尔值
        boolean newState;
        if (stateStr.equalsIgnoreCase("true")) {
            newState = true;
        } else if (stateStr.equalsIgnoreCase("false")) {
            newState = false;
        } else {
            source.sendFailure(Component.literal("无效的状态值，只能是true或false"));
            return 0;
        }
        
        // 更新配置
        com.example.hcrpoints.config.MapPlayerDisplayConfig config = com.example.hcrpoints.config.MapPlayerDisplayConfig.getInstance();
        config.setShowPlayerLocations(newState);
        
        // 向所有玩家广播配置更新
        com.example.hcrpoints.network.SyncMapPlayerDisplayMessage.broadcastToAll();
        
        // 发送成功消息
        source.sendSuccess(() -> Component.literal("已" + (newState ? "开启" : "关闭") + "地图玩家位置显示"), true);
        
        return 1;
    }
    
    /**
     * 执行reload命令，重新加载配置文件
     */
    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        // 重新加载地图玩家显示配置
        com.example.hcrpoints.config.MapPlayerDisplayConfig.getInstance().loadConfig();
        
        // 向所有玩家广播配置更新
        com.example.hcrpoints.network.SyncMapPlayerDisplayMessage.broadcastToAll();
        
        // 发送成功消息
        source.sendSuccess(() -> Component.literal("已重新加载配置文件，当前地图玩家位置显示：" + com.example.hcrpoints.config.MapPlayerDisplayConfig.getInstance().isShowPlayerLocations()), true);
        
        return 1;
    }
}