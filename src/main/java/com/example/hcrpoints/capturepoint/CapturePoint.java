package com.example.hcrpoints.capturepoint;

import com.example.hcrpoints.util.ModLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Team;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 据点类 - 表示一个据点区域及其状态
 * 实现了可序列化接口，用于网络传输和持久化存储
 */
public class CapturePoint {
    // 据点基本信息
    private final String name;           // 据点名称
    private final BlockPos pos1;         // 第一个坐标点
    private final BlockPos pos2;         // 第二个坐标点
    private final int batch;             // 据点所属批次
    
    // 据点状态信息
    private CaptureState state;          // 当前核心状态
    private DisplayState displayState;   // 当前显示状态
    private String captorName;           // 占领者名称
    private int progress;                // 占领进度 (0-100)
    private long capturedStateStartTime; // 已占领状态开始时间（用于进度恢复）
    
    // 边界坐标（预计算以提高性能）
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    
    /**
     * 构造函数 - 创建一个新的据点
     * @param name 据点名称
     * @param pos1 第一个坐标点
     * @param pos2 第二个坐标点
     */
    public CapturePoint(String name, BlockPos pos1, BlockPos pos2) {
        this(name, pos1, pos2, 1); // 默认批次为1
    }
    
    /**
     * 构造函数 - 创建一个新的据点，带批次信息
     * @param name 据点名称
     * @param pos1 第一个坐标点
     * @param pos2 第二个坐标点
     * @param batch 据点所属批次
     */
    public CapturePoint(String name, BlockPos pos1, BlockPos pos2, int batch) {
        this.name = name;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.batch = batch;
        
        // 计算边界坐标
        this.minX = Math.min(pos1.getX(), pos2.getX());
        this.maxX = Math.max(pos1.getX(), pos2.getX());
        this.minY = Math.min(pos1.getY(), pos2.getY());
        this.maxY = Math.max(pos1.getY(), pos2.getY());
        this.minZ = Math.min(pos1.getZ(), pos2.getZ());
        this.maxZ = Math.max(pos1.getZ(), pos2.getZ());
        
        // 初始化状态
        this.state = CaptureState.NEUTRAL;
        this.displayState = DisplayState.NEUTRAL;
        this.captorName = "";
        this.progress = 0;
        this.capturedStateStartTime = 0;
        
        ModLogger.info("创建据点: " + name + " (批次 " + batch + ") 从 (" + pos1.getX() + "," + pos1.getY() + "," + pos1.getZ() + ") 到 (" + pos2.getX() + "," + pos2.getY() + "," + pos2.getZ() + ")");
        
    }
    
    /**
     * 检查指定位置是否在据点范围内
     * @param pos 要检查的位置
     * @return 如果位置在据点范围内则返回true，否则返回false
     */
    public boolean isPositionInside(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        
        return x >= minX && x <= maxX &&
               y >= minY && y <= maxY &&
               z >= minZ && z <= maxZ;
    }
    
    /**
     * 更新据点状态
     * @param playersInPoint 在据点内的玩家列表
     */
    public void updateStatus(List<net.minecraft.world.entity.player.Player> playersInPoint) {
        try {
            // 将Player列表转换为ServerPlayer列表，以便获取队伍信息
            List<ServerPlayer> serverPlayers = playersInPoint.stream()
                    .filter(player -> player instanceof ServerPlayer)
                    .map(player -> (ServerPlayer) player)
                    .collect(Collectors.toList());
            
            ModLogger.info("更新据点 " + name + " 状态，当前状态: " + state + ", 玩家数量: " + serverPlayers.size());
            
            switch (state) {
                case NEUTRAL:
                    handleNeutralState(serverPlayers);
                    break;
                case CAPTURING_FLAG:
                    handleCapturingFlagState(serverPlayers);
                    break;
                case CONTESTED:
                    handleContestedState(serverPlayers);
                    break;
                case CAPTURING_CONTESTED:
                    handleCapturingContestedState(serverPlayers);
                    break;
                case CAPTURING_DOWN:
                    handleCapturingDownState(serverPlayers);
                    break;
                case CAPTURED:
                    handleCapturedState(serverPlayers);
                    break;
            }
            
            // 更新显示状态
            updateDisplayState(serverPlayers);
            
            ModLogger.info("据点 " + name + " 更新后状态: " + state + ", 显示状态: " + getDisplayState() + ", 进度: " + progress + ", 占领者: " + captorName);
        } catch (Exception e) {
            ModLogger.error("更新据点状态时发生异常: " + e.getMessage());
        }
    }
    
    /**
     * 处理中立状态
     */
    private void handleNeutralState(List<ServerPlayer> playersInPoint) {
        if (playersInPoint.isEmpty()) {
            // 无玩家在场，保持中立状态
            progress = 0;
            captorName = ""; // 确保中立状态下没有占领者
            ModLogger.info("据点 " + name + " 保持中立状态，无玩家在地图");
        } else {
            // 根据队伍分组
            Map<String, List<ServerPlayer>> teamGroups = getTeamGroups(playersInPoint);
            
            if (teamGroups.size() == 1) {
                // 只有一个队伍在场，开始升旗
                state = CaptureState.CAPTURING_FLAG;
                // 从中立状态开始升旗时，进度必须从0开始，避免直接占领
                progress = 5;
                ModLogger.info("据点 " + name + " 开始升旗，进度: " + progress + "，队伍: " + teamGroups.keySet().iterator().next());
            } else {
                // 多个队伍在场，进入争夺状态
                state = CaptureState.CAPTURING_CONTESTED;
                ModLogger.info("据点 " + name + " 进入升旗争议状态，队伍数量: " + teamGroups.size());
            }
        }
    }
    
    /**
     * 处理升旗状态
     */
    private void handleCapturingFlagState(List<ServerPlayer> playersInPoint) {
        if (playersInPoint.isEmpty()) {
            // 玩家全部离开，回到中立状态并重置进度
            state = CaptureState.NEUTRAL;
            progress = 0; // 重置进度为0，避免下一次进入时从高进度开始
            captorName = ""; // 确保中立状态下没有占领者
            ModLogger.info("据点 " + name + " 玩家离开，回到中立状态");
        } else {
            // 根据队伍分组
            Map<String, List<ServerPlayer>> teamGroups = getTeamGroups(playersInPoint);
            
            if (teamGroups.size() == 1) {
                // 只有一个队伍在场
                if (progress >= 100) {
                    // 进度达到100%，据点被占领
                    state = CaptureState.CAPTURED;
                    capturedStateStartTime = 0; // 重置已占领状态开始时间
                    // 如果启用队伍机制，使用队伍名称作为占领者，否则使用第一个玩家名称
                    captorName = getCaptorName(teamGroups);
                    ModLogger.info("据点 " + name + " 被占领，占领者: " + captorName);
                } else {
                    // 继续增加进度
                    progress = Math.min(progress + 5, 100);
                    ModLogger.info("据点 " + name + " 继续升旗，进度: " + progress + "，队伍: " + teamGroups.keySet().iterator().next());
                }
            } else {
                // 出现多个队伍，进入争夺状态
                state = CaptureState.CAPTURING_CONTESTED;
                ModLogger.info("据点 " + name + " 出现多个队伍，进入升旗争夺状态，队伍数量: " + teamGroups.size());
            }
        }
    }
    
    /**
     * 处理争夺状态
     */
    private void handleContestedState(List<ServerPlayer> playersInPoint) {
        if (playersInPoint.isEmpty()) {
            // 玩家全部离开，回到已占领状态
            state = CaptureState.CAPTURED;
            capturedStateStartTime = 0; // 重置已占领状态开始时间
            ModLogger.info("据点 " + name + " 玩家离开，回到已占领状态");
        } else {
            // 根据队伍分组
            Map<String, List<ServerPlayer>> teamGroups = getTeamGroups(playersInPoint);
            
            if (teamGroups.size() == 1) {
                String teamName = teamGroups.keySet().iterator().next();
                
                if (teamName.equals(captorName) || teamGroups.values().iterator().next().get(0).getName().getString().equals(captorName)) {
                    // 只有占领者队伍在场，回到已占领状态
                    state = CaptureState.CAPTURED;
                    capturedStateStartTime = 0; // 重置已占领状态开始时间
                    ModLogger.info("据点 " + name + " 只有占领者队伍在场，回到已占领状态");
                } else {
                    // 只有非占领者队伍在场，开始降旗
                    state = CaptureState.CAPTURING_DOWN;
                    ModLogger.info("据点 " + name + " 只有非占领者队伍在场，开始降旗");
                }
            } else {
                // 多个队伍在场，继续保持争夺状态
                ModLogger.info("据点 " + name + " 仍有多个队伍在场，保持争夺状态，队伍数量: " + teamGroups.size());
            }
        }
    }
    
    /**
     * 处理升旗争夺状态
     */
    private void handleCapturingContestedState(List<ServerPlayer> playersInPoint) {
        if (playersInPoint.isEmpty()) {
            // 玩家全部离开，回到中立状态
            state = CaptureState.NEUTRAL;
            progress = 0; // 重置进度为0，避免后续玩家快速占领
            captorName = ""; // 确保中立状态下没有占领者
            ModLogger.info("据点 " + name + " 玩家全部离开，回到中立状态");
        } else {
            // 根据队伍分组
            Map<String, List<ServerPlayer>> teamGroups = getTeamGroups(playersInPoint);
            
            if (teamGroups.size() == 1) {
                // 只剩一个队伍，恢复升旗状态
                state = CaptureState.CAPTURING_FLAG;
                ModLogger.info("据点 " + name + " 只剩一个队伍，恢复升旗状态，队伍: " + teamGroups.keySet().iterator().next());
            } else {
                // 多个队伍在场，继续保持升旗争夺状态
                ModLogger.info("据点 " + name + " 仍有多个队伍在场，保持升旗争夺状态，队伍数量: " + teamGroups.size());
            }
        }
    }
    
    /**
     * 处理降旗状态
     */
    private void handleCapturingDownState(List<ServerPlayer> playersInPoint) {
        if (playersInPoint.isEmpty()) {
            // 玩家全部离开，根据当前进度决定状态
            if (progress <= 0) {
                // 进度已经很低，回到中立状态
                state = CaptureState.NEUTRAL;
                captorName = "";
                ModLogger.info("据点 " + name + " 玩家离开，进度已耗尽，回到中立状态");
            } else {
                // 进度还很高，回到已占领状态
                state = CaptureState.CAPTURED;
                capturedStateStartTime = 0; // 重置已占领状态开始时间
                ModLogger.info("据点 " + name + " 玩家离开，进度仍高，回到已占领状态");
            }
        } else {
            // 根据队伍分组
            Map<String, List<ServerPlayer>> teamGroups = getTeamGroups(playersInPoint);
            
            if (teamGroups.size() == 1) {
                String teamName = teamGroups.keySet().iterator().next();
                List<ServerPlayer> teamPlayers = teamGroups.values().iterator().next();
                String playerName = teamPlayers.get(0).getName().getString();
                
                if (teamName.equals(captorName) || playerName.equals(captorName)) {
                    // 占领者队伍回来，回到已占领状态
                    state = CaptureState.CAPTURED;
                    capturedStateStartTime = 0; // 重置已占领状态开始时间
                    ModLogger.info("据点 " + name + " 占领者队伍回来，回到已占领状态");
                } else {
                    // 继续降旗
                    progress = Math.max(progress - 5, 0);
                    ModLogger.info("据点 " + name + " 继续降旗，进度: " + progress);
                    if (progress <= 0) {
                        // 进度降到0，回到中立状态
                        state = CaptureState.NEUTRAL;
                        captorName = "";
                        progress = 0; // 显式重置进度为0，避免状态转换异常
                        capturedStateStartTime = 0; // 重置已占领状态开始时间
                        ModLogger.info("据点 " + name + " 降旗完成，回到中立状态");
                    }
                }
            } else {
                // 多个队伍在场，进入争夺状态
                state = CaptureState.CONTESTED;
                ModLogger.info("据点 " + name + " 出现多个队伍，进入争夺状态");
            }
        }
    }
    
    /**
     * 处理已占领状态
     */
    private void handleCapturedState(List<ServerPlayer> playersInPoint) {
        // 记录已占领状态开始时间
        if (capturedStateStartTime == 0) {
            capturedStateStartTime = System.currentTimeMillis();
        }
        
        if (playersInPoint.isEmpty()) {
            // 无玩家在场，保持已占领状态
            ModLogger.info("据点 " + name + " 无玩家在场，保持已占领状态");
        } else {
            // 根据队伍分组
            Map<String, List<ServerPlayer>> teamGroups = getTeamGroups(playersInPoint);
            
            boolean hasEnemyTeam = false;
            for (String teamName : teamGroups.keySet()) {
                List<ServerPlayer> teamPlayers = teamGroups.get(teamName);
                String playerName = teamPlayers.get(0).getName().getString();
                
                if (!teamName.equals(captorName) && !playerName.equals(captorName)) {
                    hasEnemyTeam = true;
                    break;
                }
            }
            
            if (hasEnemyTeam) {
                if (teamGroups.size() == 1) {
                    // 只有一个敌方队伍在场，开始降旗
                    state = CaptureState.CAPTURING_DOWN;
                    capturedStateStartTime = 0; // 重置已占领状态开始时间
                    ModLogger.info("据点 " + name + " 敌方队伍在场，开始降旗");
                } else {
                    // 多个队伍在场，进入争夺状态
                    state = CaptureState.CONTESTED;
                    capturedStateStartTime = 0; // 重置已占领状态开始时间
                    ModLogger.info("据点 " + name + " 多个队伍在场，进入争夺状态");
                }
            } else {
                // 只有占领者队伍在场，保持已占领状态
                ModLogger.info("据点 " + name + " 只有占领者队伍在场，保持已占领状态");
            }
        }
        
        // 检查进度是否未满，如果未满且已占领状态持续5秒以上，将进度设置为满
        if (progress < 100) {
            long currentTime = System.currentTimeMillis();
            long duration = currentTime - capturedStateStartTime;
            if (duration >= 5000) { // 5秒
                progress = 100;
                ModLogger.info("据点 " + name + " 已占领状态持续5秒，进度自动恢复到100%");
            }
        }
    }
    
    /**
     * 更新显示状态
     */
    private void updateDisplayState(List<ServerPlayer> playersInPoint) {
        switch (state) {
            case NEUTRAL:
                displayState = DisplayState.NEUTRAL;
                // 确保中立状态下显示状态正确
                captorName = "";
                break;
            case CAPTURING_FLAG:
                if (!playersInPoint.isEmpty()) {
                    displayState = DisplayState.CAPTURING_FLAG_SINGLE;
                    // 不再在这里修改captorName，captorName由核心状态管理
                }
                break;
            case CONTESTED:
                displayState = DisplayState.CONTESTED_MULTI;
                break;
            case CAPTURING_CONTESTED:
                displayState = DisplayState.CAPTURING_CONTESTED_MULTI;
                break;
            case CAPTURING_DOWN:
                displayState = DisplayState.CAPTURING_DOWN;
                break;
            case CAPTURED:
                displayState = DisplayState.CAPTURED;
                break;
        }
    }
    
    /**
     * 根据队伍分组玩家
     * @param playersInPoint 玩家列表
     * @return 按照队伍分组的玩家映射
     */
    private Map<String, List<ServerPlayer>> getTeamGroups(List<ServerPlayer> playersInPoint) {
        Map<String, List<ServerPlayer>> teamGroups = new HashMap<>();
        
        for (ServerPlayer player : playersInPoint) {
            String teamName;
            // 获取玩家队伍，如果没有队伍则使用玩家名称
            Team team = player.getTeam();
            if (team != null && com.example.hcrpoints.config.ModConfig.enableTeams.get()) {
                teamName = team.getName();
            } else {
                teamName = player.getName().getString();
            }
            
            // 将玩家添加到对应队伍组
            teamGroups.computeIfAbsent(teamName, k -> new ArrayList<>()).add(player);
        }
        
        return teamGroups;
    }
    
    /**
     * 获取占领者名称
     * @param teamGroups 队伍分组
     * @return 占领者名称（队伍名称或玩家名称）
     */
    private String getCaptorName(Map<String, List<ServerPlayer>> teamGroups) {
        if (teamGroups.isEmpty()) {
            return "";
        }
        
        // 获取第一个队伍
        String firstTeam = teamGroups.keySet().iterator().next();
        List<ServerPlayer> firstTeamPlayers = teamGroups.get(firstTeam);
        
        // 如果启用队伍机制，使用队伍名称，否则使用第一个玩家名称
        if (com.example.hcrpoints.config.ModConfig.enableTeams.get()) {
            return firstTeam;
        } else {
            return firstTeamPlayers.isEmpty() ? "" : firstTeamPlayers.get(0).getName().getString();
        }
    }
    
    // Getter和Setter方法
    public String getName() { return name; }
    public BlockPos getPos1() { return pos1; }
    public BlockPos getPos2() { return pos2; }
    public CaptureState getState() { return state; }
    public void setState(CaptureState state) { this.state = state; }
    public DisplayState getDisplayState() { return displayState; }
    public void setDisplayState(DisplayState displayState) { this.displayState = displayState; }
    public String getCaptorName() { return captorName; }
    public void setCaptorName(String captorName) { this.captorName = captorName; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    
    /**
     * 获取据点信息字符串
     * @return 据点信息字符串
     */
    public String getInfoString() {
        return name + " (批次 " + batch + ") - " + state + " - " + progress + "% - 占领者: " + captorName;
    }
    
    /**
     * 获取据点所属批次
     * @return 据点所属批次
     */
    public int getBatch() {
        return batch;
    }
    
    /**
     * 可序列化的据点数据类
     * 用于在网络间传输据点数据
     */
    public static class SerializableCapturePoint {
        public final String name;
        public final BlockPos pos1;
        public final BlockPos pos2;
        public final int batch;
        public final CaptureState state;
        public final DisplayState displayState;
        public final String captorName;
        public final int progress;
        
        public SerializableCapturePoint(String name, BlockPos pos1, BlockPos pos2, int batch,
                                      CaptureState state, DisplayState displayState, 
                                      String captorName, int progress) {
            this.name = name;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.batch = batch;
            this.state = state;
            this.displayState = displayState;
            this.captorName = captorName;
            this.progress = progress;
        }
        
        /**
         * 从网络数据包读取据点信息
         * @param buf 网络数据包缓冲区
         * @return 可序列化的据点对象
         */
        public static SerializableCapturePoint fromNetwork(FriendlyByteBuf buf) {
            try {
                String name = buf.readUtf(32767);
                BlockPos pos1 = buf.readBlockPos();
                BlockPos pos2 = buf.readBlockPos();
                int batch = buf.readInt();
                CaptureState state = CaptureState.valueOf(buf.readUtf(32767));
                DisplayState displayState = DisplayState.valueOf(buf.readUtf(32767));
                String captorName = buf.readUtf(32767);
                int progress = buf.readInt();
                
                return new SerializableCapturePoint(name, pos1, pos2, batch, state, displayState, captorName, progress);
            } catch (Exception e) {
                ModLogger.error("从网络数据包读取据点信息时发生异常: " + e.getMessage());
                return null;
            }
        }
        
        /**
         * 将据点信息写入网络数据包
         * @param buf 网络数据包缓冲区
         */
        public void toNetwork(FriendlyByteBuf buf) {
            try {
                buf.writeUtf(name);
                buf.writeBlockPos(pos1);
                buf.writeBlockPos(pos2);
                buf.writeInt(batch);
                buf.writeUtf(state.toString());
                buf.writeUtf(displayState.toString());
                buf.writeUtf(captorName);
                buf.writeInt(progress);
            } catch (Exception e) {
                ModLogger.error("将据点信息写入网络数据包时发生异常: " + e.getMessage());
            }
        }
    }
    
    /**
     * 将据点转换为可序列化的对象
     * @return 可序列化的据点对象
     */
    public SerializableCapturePoint toSerializable() {
        return new SerializableCapturePoint(
            name, pos1, pos2, batch, state, displayState, captorName, progress
        );
    }
    
    /**
     * 从可序列化的对象恢复据点状态
     * @param serializable 可序列化的据点对象
     */
    public void restoreFromSerializable(SerializableCapturePoint serializable) {
        // 注意：name, pos1, pos2 是不可变的，不需要恢复
        this.state = serializable.state;
        this.displayState = serializable.displayState;
        this.captorName = serializable.captorName;
        this.progress = serializable.progress;
    }
}