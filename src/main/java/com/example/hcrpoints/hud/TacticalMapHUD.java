package com.example.hcrpoints.hud;

import com.example.hcrpoints.capturepoint.CapturePoint;
import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.example.hcrpoints.capturepoint.DisplayState;
import com.example.hcrpoints.config.TacticalMapConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Team;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 战术地图HUD类，通过V键打开，显示玩家周围更大范围的据点信息
 */
public class TacticalMapHUD implements IGuiOverlay {
    private static final int MIN_MAP_ZOOM = 50;
    private static final int MAX_MAP_ZOOM = 1000;
    private static final int MAP_ZOOM_STEP = 50;
    private static final int[] ROUTE_COLORS = {
            0xFFFF5555,
            0xFF55FF55,
            0xFF5599FF,
            0xFFFFFF55,
            0xFFFF55FF,
            0xFF55FFFF,
            0xFFFFAA55
    };
    
    private boolean isMapVisible = false; // 地图是否可见
    private int mapZoom = MAX_MAP_ZOOM;
    private List<CapturePoint> allPoints = new ArrayList<>();
    
    // 存储从服务端同步的玩家位置
    private final Map<UUID, com.example.hcrpoints.network.SyncPlayerPositionsMessage.PlayerPosition> syncedPlayerPositions = new HashMap<>();
    
    // 摄影机晃动相关变量
    private float prevYaw = 0.0f;
    private float prevPitch = 0.0f;
    private float smoothShakeX = 0.0f;
    private float smoothShakeY = 0.0f;
    private static final float SMOOTH_FACTOR = 0.1f; // 平滑因子，数值越小晃动越平滑
    private static final int SHAKE_INTENSITY = 3; // 晃动强度
    
    public TacticalMapHUD() {
        // 注册事件监听器
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void syncVisibleCapturePointsFromServer(List<CapturePoint.SerializableCapturePoint> serializedPoints) {
        List<CapturePoint> syncedPoints = new ArrayList<>();
        for (CapturePoint.SerializableCapturePoint sp : serializedPoints) {
            CapturePoint point = new CapturePoint(sp.name, sp.pos1, sp.pos2, sp.batch);
            point.restoreFromSerializable(sp);
            syncedPoints.add(point);
        }
        syncedPoints.sort(Comparator.comparingInt(CapturePoint::getBatch).thenComparing(CapturePoint::getName));
        this.allPoints = syncedPoints;
    }
    
    /**
     * 切换地图显示/隐藏状态
     */
    public void toggleMapVisibility() {
        isMapVisible = !isMapVisible;
    }
    
    /**
     * 切换地图显示模式
     */
    public void cycleDisplayMode() {
        // 地图现在只有一种显示形态，保留方法避免旧按键调用报错。
    }
    
    /**
     * 获取当前显示模式
     */
    public MapDisplayMode getDisplayMode() {
        return MapDisplayMode.TOGGLE_KEY;
    }
    
    /**
     * 获取地图是否可见（按键唤出模式下）
     */
    public boolean isMapVisible() {
        return isMapVisible;
    }

    public void increaseRenderRange() {
        mapZoom = Math.min(MAX_MAP_ZOOM, mapZoom + MAP_ZOOM_STEP);
    }

    public void decreaseRenderRange() {
        mapZoom = Math.max(MIN_MAP_ZOOM, mapZoom - MAP_ZOOM_STEP);
    }
    
    /**
     * 渲染HUD界面
     * @param gui Forge GUI对象
     * @param guiGraphics GUI图形对象
     * @param partialTick 部分刻度
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     */
    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (!isMapVisible) {
            return;
        }
        
        int mapWidth = (int)(screenWidth * 0.42);
        int mapHeight = (int)(screenHeight * 0.75);
        int mapLeft = screenWidth - mapWidth;
        int mapTop = (screenHeight - mapHeight) / 2;
        boolean isMiniMap = false;
        
        // 渲染地图背景（半透明）
        int bgColor = 0xCC202020;
        guiGraphics.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, bgColor);
        
        // 渲染地图边框
        int borderColor = 0xFF000000;
        guiGraphics.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + 1, borderColor);
        guiGraphics.fill(mapLeft, mapTop, mapLeft + 1, mapTop + mapHeight, borderColor);
        guiGraphics.fill(mapLeft + mapWidth - 1, mapTop, mapLeft + mapWidth, mapTop + mapHeight, borderColor);
        guiGraphics.fill(mapLeft, mapTop + mapHeight - 1, mapLeft + mapWidth, mapTop + mapHeight, borderColor);
        
        // 渲染地图标题（仅在完整尺寸时显示）
        if (!isMiniMap) {
            guiGraphics.drawString(
                Minecraft.getInstance().font,
                Component.literal("战术地图 (V键关闭) 范围:" + mapZoom + " C+/B-"),
                mapLeft + 5,
                mapTop + 5,
                0xFFFFFF,
                false
            );
        }
        
        // 渲染鸟瞰图，包含玩家位置
        renderBirdsEyeView(guiGraphics, mapLeft, mapTop, mapWidth, mapHeight);
    }

    /**
     * 在其他 GUI 中嵌入绘制同一套战术地图，不受 V 键 HUD 显示开关影响。
     */
    public void renderEmbeddedMap(GuiGraphics guiGraphics, int mapLeft, int mapTop, int mapWidth, int mapHeight, float partialTick) {
        if (mapWidth < 40 || mapHeight < 40) {
            return;
        }

        guiGraphics.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, 0xCC202020);
        guiGraphics.renderOutline(mapLeft, mapTop, mapWidth, mapHeight, 0xFF000000);

        guiGraphics.drawString(
            Minecraft.getInstance().font,
            Component.literal("战术地图  范围:" + mapZoom + "  C+/B-"),
            mapLeft + 5,
            mapTop + 5,
            0xFFFFFF,
            false
        );

        guiGraphics.enableScissor(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight);
        try {
            renderBirdsEyeView(guiGraphics, mapLeft, mapTop, mapWidth, mapHeight);
        } finally {
            guiGraphics.disableScissor();
        }
    }
    
    /**
     * 渲染行动模式兵力显示
     * @param guiGraphics GUI图形对象
     * @param mapLeft 地图左上角X坐标
     * @param mapTop 地图左上角Y坐标
     * @param mapWidth 地图宽度
     * @param mapHeight 地图高度
     * @param isMiniMap 是否是迷你地图
     */
    private void renderReinforcementsDisplay(GuiGraphics guiGraphics, int mapLeft, int mapTop, int mapWidth, int mapHeight, boolean isMiniMap) {
        CapturePointManager manager = CapturePointManager.getInstance();
        
        // 获取攻防双方队伍名称
        String attackerTeam = manager.getAttackerTeam();
        String defenderTeam = manager.getDefenderTeam();
        
        if (attackerTeam == null || defenderTeam == null) {
            return; // 队伍未设置完整，不显示
        }
        
        // 获取双方兵力
        int attackerReinforcements = manager.getTeamReinforcements(attackerTeam);
        int defenderReinforcements = manager.getTeamReinforcements(defenderTeam);
        
        // 计算进度条位置和尺寸
        int barHeight = 10;
        int barTop = isMiniMap ? mapTop + 5 : mapTop + 20; // 迷你地图上显示在顶部，完整地图显示在标题下方
        int barLeft = mapLeft + 5;
        int barRight = mapLeft + mapWidth - 5;
        int barWidth = barRight - barLeft;
        int halfBarWidth = barWidth / 2;
        
        // 从配置中获取颜色代码
        String defenderHexColor = com.example.hcrpoints.config.TacticalMapConfig.defenderProgressBarColor.get();
        String attackerHexColor = com.example.hcrpoints.config.TacticalMapConfig.attackerProgressBarColor.get();
        
        // 将颜色代码转换为整数颜色值
        int defenderColor = com.example.hcrpoints.util.ModLogger.hexToColor(defenderHexColor, 0xFF0055FF); // 默认蓝色
        int attackerColor = com.example.hcrpoints.util.ModLogger.hexToColor(attackerHexColor, 0xFFFF5500); // 默认红色
        
        // 渲染守方进度条
        int defenderInitialReinforcements = manager.getTeamInitialReinforcements(defenderTeam);
        int defenderBarWidth = 0;
        if (defenderInitialReinforcements > 0) {
            defenderBarWidth = (int)((double)defenderReinforcements / defenderInitialReinforcements * halfBarWidth);
        }
        guiGraphics.fill(barLeft, barTop, barLeft + halfBarWidth, barTop + barHeight, 0x44000000); // 背景
        guiGraphics.fill(barLeft, barTop, barLeft + defenderBarWidth, barTop + barHeight, defenderColor); // 进度条
        
        // 渲染攻方进度条
        int attackerInitialReinforcements = manager.getTeamInitialReinforcements(attackerTeam);
        int attackerBarWidth = 0;
        if (attackerInitialReinforcements > 0) {
            attackerBarWidth = (int)((double)attackerReinforcements / attackerInitialReinforcements * halfBarWidth);
        }
        int attackerBarLeft = barLeft + halfBarWidth;
        guiGraphics.fill(attackerBarLeft, barTop, attackerBarLeft + halfBarWidth, barTop + barHeight, 0x44000000); // 背景
        guiGraphics.fill(attackerBarLeft + halfBarWidth - attackerBarWidth, barTop, attackerBarLeft + halfBarWidth, barTop + barHeight, attackerColor); // 进度条
        
        // 绘制队伍名称和兵力数量
        Minecraft minecraft = Minecraft.getInstance();
        
        // 计算文字位置：迷你地图上显示在进度条上方，完整地图上显示在下方
        int textY = isMiniMap ? barTop - 10 : barTop + barHeight + 2;
        
        // 守方信息（左对齐）
        String defenderText = defenderTeam + ": " + defenderReinforcements;
        guiGraphics.drawString(
            minecraft.font,
            defenderText,
            barLeft,
            textY,
            0xFFFFFF,
            false
        );
        
        // 攻方信息（右对齐）
        String attackerText = attackerTeam + ": " + attackerReinforcements;
        guiGraphics.drawString(
            minecraft.font,
            attackerText,
            barRight - minecraft.font.width(attackerText),
            textY,
            0xFFFFFF,
            false
        );
    }
    
    /**
     * 从服务端同步玩家位置
     * @param playerPositions 玩家位置映射
     */
    public void syncPlayerPositionsFromServer(Map<UUID, com.example.hcrpoints.network.SyncPlayerPositionsMessage.PlayerPosition> playerPositions) {
        this.syncedPlayerPositions.clear();
        this.syncedPlayerPositions.putAll(playerPositions);
    }
    
    /**
     * 在地图上渲染其他玩家位置
     * @param guiGraphics GUI图形对象
     * @param localPlayer 本地玩家
     * @param playerMapX 玩家在地图上的X坐标
     * @param playerMapY 玩家在地图上的Y坐标
     * @param mapLeft 地图左上角X坐标
     * @param mapTop 地图左上角Y坐标
     * @param mapWidth 地图宽度
     * @param mapHeight 地图高度
     */
    private void renderOtherPlayersOnMap(GuiGraphics guiGraphics, LocalPlayer localPlayer, 
                                       int playerMapX, int playerMapY, int mapLeft, int mapTop, 
                                       int mapWidth, int mapHeight) {
        // 使用配置类检查是否显示玩家位置
        boolean showPlayerLocations = com.example.hcrpoints.config.MapPlayerDisplayConfig.getInstance().isShowPlayerLocations();
        
        if (!showPlayerLocations) {
            // 配置不允许显示，不渲染其他玩家位置
            return;
        }
        
        // 获取玩家坐标
        double playerX = localPlayer.getX();
        double playerZ = localPlayer.getZ();
        
        // 计算缩放比例
        double scaleX = (double)mapWidth / (mapZoom * 2);
        double scaleY = (double)mapHeight / (mapZoom * 2);
        
        // 地图边界坐标
        int mapRight = mapLeft + mapWidth;
        int mapBottom = mapTop + mapHeight;
        
        // 计算网格有效范围（考虑顶部20像素的标题区域）
        int gridTop = mapTop + 20; // 网格顶部从标题区域下方开始
        int gridBottom = mapBottom - 1;
        int gridLeft = mapLeft;
        int gridRight = mapRight - 1;
        
        // 获取本地玩家的队伍
        Team localTeam = localPlayer.getTeam();
        
        // 遍历从服务端同步的玩家位置
        for (Map.Entry<UUID, com.example.hcrpoints.network.SyncPlayerPositionsMessage.PlayerPosition> entry : syncedPlayerPositions.entrySet()) {
            UUID playerUUID = entry.getKey();
            com.example.hcrpoints.network.SyncPlayerPositionsMessage.PlayerPosition pos = entry.getValue();
            
            // 跳过本地玩家
            if (playerUUID.equals(localPlayer.getUUID())) {
                continue;
            }
            
            // 获取其他玩家坐标
            double otherPlayerX = pos.getX();
            double otherPlayerZ = pos.getZ();
            
            // 计算其他玩家在地图上的相对位置
            int mapPosX = playerMapX + (int)((otherPlayerX - playerX) * scaleX);
            int mapPosY = playerMapY + (int)((otherPlayerZ - playerZ) * scaleY);
            
            // 将超出网格范围的玩家位置限制在网格边缘
            mapPosX = Math.max(gridLeft, Math.min(gridRight, mapPosX));
            mapPosY = Math.max(gridTop, Math.min(gridBottom, mapPosY));
            
            if (localTeam == null || !localTeam.getName().equals(pos.getTeamName())) {
                continue;
            }
            
            guiGraphics.fill(mapPosX - 2, mapPosY - 2, mapPosX + 3, mapPosY + 3, 0xFF00FF00);
        }
        
        // 如果没有同步到玩家位置，尝试直接获取本地玩家列表作为备选方案
        if (syncedPlayerPositions.isEmpty()) {
            // 使用配置类检查是否显示玩家位置
            showPlayerLocations = com.example.hcrpoints.config.MapPlayerDisplayConfig.getInstance().isShowPlayerLocations();
            if (!showPlayerLocations) {
                // 配置不允许显示，不渲染其他玩家位置
                return;
            }
            
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                for (net.minecraft.world.entity.player.Player otherPlayer : minecraft.level.players()) {
                    if (otherPlayer == localPlayer) {
                        continue;
                    }
                    
                    double otherPlayerX = otherPlayer.getX();
                    double otherPlayerZ = otherPlayer.getZ();
                    
                    int mapPosX = playerMapX + (int)((otherPlayerX - playerX) * scaleX);
                    int mapPosY = playerMapY + (int)((otherPlayerZ - playerZ) * scaleY);
                    
                    mapPosX = Math.max(gridLeft, Math.min(gridRight, mapPosX));
                    mapPosY = Math.max(gridTop, Math.min(gridBottom, mapPosY));
                    
                    Team otherTeam = otherPlayer.getTeam();
                    if (localTeam == null || otherTeam == null || localTeam != otherTeam) {
                        continue;
                    }
                    
                    guiGraphics.fill(mapPosX - 2, mapPosY - 2, mapPosX + 3, mapPosY + 3, 0xFF00FF00);
                }
            }
        }
    }
    
    /**
     * 渲染鸟瞰图
     * @param guiGraphics GUI图形对象
     * @param mapLeft 地图左上角X坐标
     * @param mapTop 地图左上角Y坐标
     * @param mapWidth 地图宽度
     * @param mapHeight 地图高度
     * @param alpha 透明度
     */
    private void renderBirdsEyeView(GuiGraphics guiGraphics, int mapLeft, int mapTop, int mapWidth, int mapHeight) {
        // 计算实际网格区域（顶部20像素留作标题区域）
        int gridTopOffset = 20;
        int gridStartY = mapTop + gridTopOffset;
        int gridHeight = mapHeight - gridTopOffset;
        
        // 渲染简单的网格背景
        int gridSize = 20;
        for (int x = 0; x <= mapWidth; x += gridSize) {
            int gridColor = 0x22FFFFFF;
            guiGraphics.fill(mapLeft + x, gridStartY, mapLeft + x + 1, mapTop + mapHeight, gridColor);
        }
        for (int y = gridTopOffset; y <= mapHeight; y += gridSize) {
            int gridColor = 0x22FFFFFF;
            guiGraphics.fill(mapLeft, mapTop + y, mapLeft + mapWidth, mapTop + y + 1, gridColor);
        }
        
        // 获取玩家位置
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        
        // 玩家在地图上的中心位置
        int playerMapX = mapLeft + mapWidth / 2;
        int playerMapY = mapTop + mapHeight / 2;
        
        // 渲染玩家位置（箭头表示朝向）
        renderPlayerArrow(guiGraphics, player, playerMapX, playerMapY);
        
        // 渲染玩家周围的据点
        renderCapturePointsOnMap(guiGraphics, playerMapX, playerMapY, mapLeft, mapTop, mapWidth, mapHeight, player);
        
        // 渲染其他玩家位置
        renderOtherPlayersOnMap(guiGraphics, player, playerMapX, playerMapY, mapLeft, mapTop, mapWidth, mapHeight);
    }
    
    /**
     * 在地图上渲染据点
     * @param guiGraphics GUI图形对象
     * @param playerMapX 玩家在地图上的X坐标
     * @param playerMapY 玩家在地图上的Y坐标
     * @param mapLeft 地图左上角X坐标
     * @param mapTop 地图左上角Y坐标
     * @param mapWidth 地图宽度
     * @param mapHeight 地图高度
     * @param alpha 透明度
     * @param player 本地玩家
     */
    private void renderCapturePointsOnMap(GuiGraphics guiGraphics, int playerMapX, int playerMapY, int mapLeft, int mapTop, 
                                        int mapWidth, int mapHeight, LocalPlayer player) {
        // 获取玩家坐标
        double playerX = player.getX();
        double playerZ = player.getZ();
        double playerY = player.getY();
        
        // 计算缩放比例
        double scaleX = (double)mapWidth / (mapZoom * 2);
        double scaleY = (double)mapHeight / (mapZoom * 2);
        
        // 地图边界坐标
        int mapRight = mapLeft + mapWidth;
        int mapBottom = mapTop + mapHeight;
        
        // 遍历所有据点
        Map<CapturePoint, int[]> pointPositions = new HashMap<>();
        for (CapturePoint point : allPoints) {
            // 计算据点中心坐标
            double pointCenterX = (point.getPos1().getX() + point.getPos2().getX()) / 2.0;
            double pointCenterZ = (point.getPos1().getZ() + point.getPos2().getZ()) / 2.0;
            double pointCenterY = (point.getPos1().getY() + point.getPos2().getY()) / 2.0;
            
            // 计算据点在地图上的相对位置
            int mapPosX = playerMapX + (int)((pointCenterX - playerX) * scaleX);
            int mapPosY = playerMapY + (int)((pointCenterZ - playerZ) * scaleY);
            
            // 计算网格有效范围（考虑顶部20像素的标题区域）
            int gridTop = mapTop + 20; // 网格顶部从标题区域下方开始
            int gridBottom = mapBottom - 1;
            int gridLeft = mapLeft;
            int gridRight = mapRight - 1;
            
            // 将超出网格范围的据点限制在网格边缘
            mapPosX = Math.max(gridLeft, Math.min(gridRight, mapPosX));
            mapPosY = Math.max(gridTop, Math.min(gridBottom, mapPosY));
            pointPositions.put(point, new int[] {mapPosX, mapPosY});
            
            // 根据据点状态获取颜色
            int pointColor = getStatusColor(point);
            
            // 渲染据点中心方块
            guiGraphics.fill(mapPosX - 3, mapPosY - 3, mapPosX + 3, mapPosY + 3, pointColor);
            
            // 渲染据点名称
            guiGraphics.drawString(
                Minecraft.getInstance().font,
                point.getName(),
                mapPosX + 5,
                mapPosY - 6,
                0xFFFFFF,
                false
            );
            
            // 计算并渲染玩家与据点的相对高度
            int relativeHeight = (int)Math.round(pointCenterY - playerY);
            String heightText = relativeHeight > 0 ? "+" + relativeHeight : String.valueOf(relativeHeight);
            guiGraphics.drawString(
                Minecraft.getInstance().font,
                heightText,
                mapPosX + 5,
                mapPosY + 6,
                0xAAAAAA,
                false
            );
            
            // 渲染据点坐标
            String coordText = (int)pointCenterX + ", " + (int)pointCenterZ;
            guiGraphics.drawString(
                Minecraft.getInstance().font,
                coordText,
                mapPosX + 5,
                mapPosY + 18,
                0x888888,
                false
            );
            
            // 渲染据点边界，传入网格边界参数，确保据点范围限制在网格内
            renderPointBoundary(guiGraphics, point, playerX, playerZ, playerMapX, playerMapY, scaleX, scaleY, gridLeft, gridTop, gridRight, gridBottom);
        }

        renderBatchRoutes(guiGraphics, pointPositions);
    }

    private void renderBatchRoutes(GuiGraphics guiGraphics, Map<CapturePoint, int[]> pointPositions) {
        Map<Integer, List<CapturePoint>> pointsByBatch = new TreeMap<>();
        for (CapturePoint point : allPoints) {
            pointsByBatch.computeIfAbsent(point.getBatch(), key -> new ArrayList<>()).add(point);
        }

        CapturePoint previousBatchLastPoint = null;
        for (List<CapturePoint> batchPoints : pointsByBatch.values()) {
            batchPoints.sort(Comparator.comparing(CapturePoint::getName));

            if (previousBatchLastPoint != null && !batchPoints.isEmpty()) {
                drawRouteLine(guiGraphics, pointPositions, previousBatchLastPoint, batchPoints.get(0), ROUTE_COLORS[0]);
            }

            for (int i = 0; i < batchPoints.size() - 1; i++) {
                int color = ROUTE_COLORS[i % ROUTE_COLORS.length];
                drawRouteLine(guiGraphics, pointPositions, batchPoints.get(i), batchPoints.get(i + 1), color);
            }

            if (!batchPoints.isEmpty()) {
                previousBatchLastPoint = batchPoints.get(batchPoints.size() - 1);
            }
        }
    }

    private void drawRouteLine(GuiGraphics guiGraphics, Map<CapturePoint, int[]> pointPositions,
                               CapturePoint from, CapturePoint to, int color) {
        int[] fromPos = pointPositions.get(from);
        int[] toPos = pointPositions.get(to);
        if (fromPos == null || toPos == null) {
            return;
        }
        drawLine(guiGraphics, fromPos[0], fromPos[1], toPos[0], toPos[1], color);
    }
    
    /**
     * 渲染据点边界
     * @param guiGraphics GUI图形对象
     * @param point 据点对象
     * @param playerX 玩家X坐标
     * @param playerZ 玩家Z坐标
     * @param playerMapX 玩家在地图上的X坐标
     * @param playerMapY 玩家在地图上的Y坐标
     * @param scaleX X轴缩放比例
     * @param scaleY Y轴缩放比例
     * @param mapLeft 地图左上角X坐标
     * @param mapTop 地图左上角Y坐标
     * @param mapRight 地图右下角X坐标
     * @param mapBottom 地图右下角Y坐标
     * @param alpha 透明度
     */
    private void renderPointBoundary(GuiGraphics guiGraphics, CapturePoint point, double playerX, double playerZ, 
                                   int playerMapX, int playerMapY, double scaleX, double scaleY, 
                                   int mapLeft, int mapTop, int mapRight, int mapBottom) {
        // 计算据点边界坐标
        int minX = Math.min(point.getPos1().getX(), point.getPos2().getX());
        int maxX = Math.max(point.getPos1().getX(), point.getPos2().getX());
        int minZ = Math.min(point.getPos1().getZ(), point.getPos2().getZ());
        int maxZ = Math.max(point.getPos1().getZ(), point.getPos2().getZ());
        
        // 转换为地图坐标
        int mapMinX = playerMapX + (int)((minX - playerX) * scaleX);
        int mapMaxX = playerMapX + (int)((maxX - playerX) * scaleX);
        int mapMinZ = playerMapY + (int)((minZ - playerZ) * scaleY);
        int mapMaxZ = playerMapY + (int)((maxZ - playerZ) * scaleY);
        
        // 确保地图边界坐标正确
        int adjustedMapRight = mapRight - 1;
        int adjustedMapBottom = mapBottom - 1;
        
        // 计算四条边界线的起点和终点，严格限制在地图范围内
        // 上边界：(startX, topY) 到 (endX, topY)
        int topY = mapMinZ;
        // 确保Y坐标在地图范围内
        if (topY < mapTop) topY = mapTop;
        if (topY > adjustedMapBottom) topY = adjustedMapBottom;
        
        // 下边界：(startX, bottomY) 到 (endX, bottomY)
        int bottomY = mapMaxZ;
        // 确保Y坐标在地图范围内
        if (bottomY < mapTop) bottomY = mapTop;
        if (bottomY > adjustedMapBottom) bottomY = adjustedMapBottom;
        
        // 左边界：(leftX, startY) 到 (leftX, endY)
        int leftX = mapMinX;
        // 确保X坐标在地图范围内
        if (leftX < mapLeft) leftX = mapLeft;
        if (leftX > adjustedMapRight) leftX = adjustedMapRight;
        
        // 右边界：(rightX, startY) 到 (rightX, endY)
        int rightX = mapMaxX;
        // 确保X坐标在地图范围内
        if (rightX < mapLeft) rightX = mapLeft;
        if (rightX > adjustedMapRight) rightX = adjustedMapRight;
        
        // 根据据点状态获取颜色
        int boundaryColor = (getStatusColor(point) & 0x80FFFFFF); // 半透明
        
        // 计算每条线的渲染范围，确保完全在地图内
        // 上边界
        if (topY >= mapTop && topY <= adjustedMapBottom) {
            int startX = Math.max(mapLeft, mapMinX);
            int endX = Math.min(adjustedMapRight, mapMaxX);
            if (startX < endX) { // 只在有实际长度时绘制
                guiGraphics.fill(startX, topY, endX, topY + 1, boundaryColor);
            }
        }
        
        // 下边界
        if (bottomY >= mapTop && bottomY <= adjustedMapBottom) {
            int startX = Math.max(mapLeft, mapMinX);
            int endX = Math.min(adjustedMapRight, mapMaxX);
            if (startX < endX) { // 只在有实际长度时绘制
                guiGraphics.fill(startX, bottomY, endX, bottomY + 1, boundaryColor);
            }
        }
        
        // 左边界
        if (leftX >= mapLeft && leftX <= adjustedMapRight) {
            int startY = Math.max(mapTop, mapMinZ);
            int endY = Math.min(adjustedMapBottom, mapMaxZ);
            if (startY < endY) { // 只在有实际长度时绘制
                guiGraphics.fill(leftX, startY, leftX + 1, endY, boundaryColor);
            }
        }
        
        // 右边界
        if (rightX >= mapLeft && rightX <= adjustedMapRight) {
            int startY = Math.max(mapTop, mapMinZ);
            int endY = Math.min(adjustedMapBottom, mapMaxZ);
            if (startY < endY) { // 只在有实际长度时绘制
                guiGraphics.fill(rightX, startY, rightX + 1, endY, boundaryColor);
            }
        }
    }
    
    /**
     * 根据显示状态获取对应颜色
     * @param point 据点对象
     * @param alpha 透明度
     * @return 对应的颜色值（ARGB格式）
     */
    private int getStatusColor(CapturePoint point) {
        DisplayState displayState = point.getDisplayState();
        int baseColor = 0;
        
        switch (displayState) {
            case NEUTRAL:
                baseColor = 0xFFAAAAAA; // 灰色 - 中立状态
                break;
                
            case CAPTURING_FLAG_SINGLE:
                baseColor = 0xFFFFFF00; // 黄色 - 单人升旗状态
                break;
                
            case CAPTURING_CONTESTED_MULTI:
                baseColor = 0xFFFF8000; // 橙色 - 争夺中升旗状态
                break;
                
            case CONTESTED_MULTI:
                baseColor = 0xFFFF8000; // 橙色 - 争夺状态
                break;
                
            case CAPTURING_DOWN:
                baseColor = 0xFFFF0000; // 红色 - 降旗状态
                break;
                
            case CAPTURED:
                // 检查当前玩家是否属于占领者
                if (isFriendlyCapture(point)) {
                    baseColor = 0xFF55FF55; // 绿色 - 友方占领
                } else {
                    baseColor = 0xFFFF5555; // 红色 - 敌方占领
                }
                break;
                
            default:
                baseColor = 0xFFAAAAAA; // 默认灰色
        }
        
        return baseColor;
    }
    
    /**
     * 渲染队伍玩家列表
     * @param guiGraphics GUI图形对象
     * @param mapLeft 地图左上角X坐标
     * @param mapTop 地图左上角Y坐标
     * @param mapWidth 地图宽度
     * @param mapHeight 地图高度
     * @param displayMode 地图显示模式
     */
    private void renderTeamPlayers(GuiGraphics guiGraphics, int mapLeft, int mapTop, int mapWidth, int mapHeight, MapDisplayMode displayMode) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null) {
            return;
        }
        
        // 获取本地玩家的队伍
        Team localTeam = localPlayer.getTeam();
        if (localTeam == null) {
            return;
        }
        
        // 获取所有在线玩家
        List<net.minecraft.client.player.AbstractClientPlayer> allPlayers = minecraft.level.players();
        if (allPlayers.isEmpty()) {
            return;
        }
        
        // 创建玩家距离信息列表
        List<PlayerDistanceInfo> playerDistanceInfos = new ArrayList<>();
        
        // 计算每个玩家与本地玩家的距离
        for (net.minecraft.client.player.AbstractClientPlayer player : allPlayers) {
            if (player.getTeam() != localTeam) {
                continue; // 只显示同队伍玩家
            }
            
            double distance = localPlayer.distanceTo(player);
            playerDistanceInfos.add(new PlayerDistanceInfo(player, distance));
        }
        
        // 排序玩家，优先显示自己，然后按距离排序
        playerDistanceInfos.sort((a, b) -> {
            // 优先显示本地玩家
            if (a.player == localPlayer) {
                return -1;
            }
            if (b.player == localPlayer) {
                return 1;
            }
            // 其他玩家按距离排序
            return Double.compare(a.distance, b.distance);
        });
        
        // 选择最多4个玩家（包含本地玩家）
        List<PlayerDistanceInfo> selectedPlayers = new ArrayList<>();
        for (int i = 0; i < playerDistanceInfos.size() && selectedPlayers.size() < 4; i++) {
            selectedPlayers.add(playerDistanceInfos.get(i));
        }
        
        // 计算显示位置
        int textX, textY;
        boolean isLeftSide = displayMode == MapDisplayMode.ALWAYS_VISIBLE_BOTTOM_LEFT || displayMode == MapDisplayMode.ALWAYS_VISIBLE_TOP_LEFT;
        
        if (isLeftSide) {
            // 迷你地图在左侧，玩家列表显示在右侧
            textX = mapLeft + mapWidth + 5;
        } else {
            // 迷你地图在右侧，玩家列表显示在左侧
            int maxNameWidth = 0;
            for (PlayerDistanceInfo info : selectedPlayers) {
                int nameWidth = minecraft.font.width(info.player.getName().getString());
                if (nameWidth > maxNameWidth) {
                    maxNameWidth = nameWidth;
                }
            }
            textX = mapLeft - maxNameWidth - 25;
        }
        
        // 垂直居中显示
        int totalHeight = selectedPlayers.size() * 12;
        textY = mapTop + (mapHeight - totalHeight) / 2;
        
        // 获取攻防队伍和颜色
        CapturePointManager manager = CapturePointManager.getInstance();
        String attackerTeam = manager.getAttackerTeam();
        String defenderTeam = manager.getDefenderTeam();
        
        // 获取颜色配置
        String defenderHexColor = TacticalMapConfig.defenderProgressBarColor.get();
        String attackerHexColor = TacticalMapConfig.attackerProgressBarColor.get();
        int defenderColor = com.example.hcrpoints.util.ModLogger.hexToColor(defenderHexColor, 0xFF0055FF);
        int attackerColor = com.example.hcrpoints.util.ModLogger.hexToColor(attackerHexColor, 0xFFFF5500);
        
        // 渲染玩家列表
        for (PlayerDistanceInfo info : selectedPlayers) {
            // 根据玩家血量确定小方块颜色
            float health = info.player.getHealth();
            float maxHealth = info.player.getMaxHealth();
            float healthPercentage = health / maxHealth;
            int playerColor;
            
            if (healthPercentage >= 0.6f) {
                // 100%~60%: 绿色
                playerColor = 0xFF00FF00;
            } else if (healthPercentage >= 0.25f) {
                // 59%~25%: 黄色
                playerColor = 0xFFFFFF00;
            } else {
                // 低于25%: 红色
                playerColor = 0xFFFF0000;
            }
            
            // 渲染血量状态方块
            guiGraphics.fill(textX, textY + 2, textX + 8, textY + 10, playerColor);
            
            // 渲染玩家名称
            guiGraphics.drawString(
                minecraft.font,
                info.player.getName().getString(),
                textX + 12,
                textY,
                0xFFFFFFFF,
                false
            );
            
            // 下移到下一个玩家位置
            textY += 12;
        }
    }
    
    /**
     * 玩家距离信息类
     */
    private static class PlayerDistanceInfo {
        private final net.minecraft.client.player.AbstractClientPlayer player;
        private final double distance;
        
        public PlayerDistanceInfo(net.minecraft.client.player.AbstractClientPlayer player, double distance) {
            this.player = player;
            this.distance = distance;
        }
    }
    
    /**
     * 渲染玩家箭头（表示朝向）
     * @param guiGraphics GUI图形对象
     * @param player 玩家对象
     * @param centerX 箭头中心X坐标
     * @param centerY 箭头中心Y坐标
     */
    private void renderPlayerArrow(GuiGraphics guiGraphics, LocalPlayer player, int centerX, int centerY) {
        int playerColor = 0xFFFF0000;
        
        // 获取玩家朝向（yRot）
        float playerYaw = player.getYRot();
        
        // Minecraft坐标系统到地图坐标系统的转换：
        // - yRot=0° 玩家面向北（正Z轴），地图上向上
        // - yRot=90° 玩家面向东（正X轴），地图上向右
        // - yRot=180° 玩家面向南（负Z轴），地图上向下
        // - yRot=270° 玩家面向西（负X轴），地图上向左
        
        // 箭头尺寸
        int arrowLength = 8;
        int arrowWidth = 3;
        
        // 计算箭头三个点的坐标
        int tipX, tipY, leftX, leftY, rightX, rightY;
        
        // 根据玩家朝向直接计算箭头方向，将四个方向反转
        tipX = centerX - (int)(Math.sin(Math.toRadians(playerYaw)) * arrowLength);
        tipY = centerY + (int)(Math.cos(Math.toRadians(playerYaw)) * arrowLength);
        
        // 计算箭头尾部的位置
        float tailAngle = playerYaw + 180;
        leftX = centerX - (int)(Math.sin(Math.toRadians(tailAngle + 30)) * arrowWidth);
        leftY = centerY + (int)(Math.cos(Math.toRadians(tailAngle + 30)) * arrowWidth);
        
        rightX = centerX - (int)(Math.sin(Math.toRadians(tailAngle - 30)) * arrowWidth);
        rightY = centerY + (int)(Math.cos(Math.toRadians(tailAngle - 30)) * arrowWidth);
        
        // 渲染箭头三角形
        drawTriangle(guiGraphics, tipX, tipY, leftX, leftY, rightX, rightY, playerColor);
    }
    
    /**
     * 绘制三角形
     * @param guiGraphics GUI图形对象
     * @param x1 点1 X坐标
     * @param y1 点1 Y坐标
     * @param x2 点2 X坐标
     * @param y2 点2 Y坐标
     * @param x3 点3 X坐标
     * @param y3 点3 Y坐标
     * @param color 颜色
     */
    private void drawTriangle(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        // 使用Bresenham算法绘制三个边
        drawLine(guiGraphics, x1, y1, x2, y2, color);
        drawLine(guiGraphics, x2, y2, x3, y3, color);
        drawLine(guiGraphics, x3, y3, x1, y1, color);
        
        // 填充三角形内部
        // 找到y轴范围
        int minY = Math.min(y1, Math.min(y2, y3));
        int maxY = Math.max(y1, Math.max(y2, y3));
        
        // 遍历每一行
        for (int y = minY; y <= maxY; y++) {
            // 找到该行与三角形的左右交点
            int left = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            
            // 检查边1-2
            if ((y1 <= y && y < y2) || (y2 <= y && y < y1)) {
                int x = interpolate(x1, y1, x2, y2, y);
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
            
            // 检查边2-3
            if ((y2 <= y && y < y3) || (y3 <= y && y < y2)) {
                int x = interpolate(x2, y2, x3, y3, y);
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
            
            // 检查边3-1
            if ((y3 <= y && y < y1) || (y1 <= y && y < y3)) {
                int x = interpolate(x3, y3, x1, y1, y);
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
            
            // 填充该行
            if (left <= right) {
                guiGraphics.fill(left, y, right + 1, y + 1, color);
            }
        }
    }
    
    /**
     * 线性插值计算x坐标
     */
    private int interpolate(int x1, int y1, int x2, int y2, int y) {
        if (y1 == y2) return x1;
        return x1 + (x2 - x1) * (y - y1) / (y2 - y1);
    }
    
    /**
     * 渲染一条线
     * @param guiGraphics GUI图形对象
     * @param x1 起始X坐标
     * @param y1 起始Y坐标
     * @param x2 结束X坐标
     * @param y2 结束Y坐标
     * @param color 颜色
     */
    private void drawLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
        // 使用Bresenham算法绘制线
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        
        int err = dx - dy;
        int e2;
        
        int x = x1;
        int y = y1;
        
        while (true) {
            // 绘制当前点
            guiGraphics.fill(x, y, x + 1, y + 1, color);
            
            // 检查是否到达终点
            if (x == x2 && y == y2) break;
            
            e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }
    
    /**
     * 检查占领是否为友方
     * @param point 据点对象
     * @return 如果是友方占领返回true，否则返回false
     */
    private boolean isFriendlyCapture(CapturePoint point) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        
        String captorName = point.getCaptorName();
        if (captorName.isEmpty()) {
            return false;
        }
        
        // 检查当前玩家的队伍
        net.minecraft.world.scores.Team playerTeam = mc.player.getTeam();
        
        // 如果占领者是玩家名称
        if (captorName.equals(mc.player.getName().getString())) {
            return true;
        }
        
        // 如果占领者是队伍名称，且当前玩家在该队伍中
        if (playerTeam != null && captorName.equals(playerTeam.getName())) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 计算两个角度之间的差值（考虑角度环绕）
     * @param previous 上一帧的角度
     * @param current 当前帧的角度
     * @return 角度差值，范围在[-180, 180]之间
     */
    private float calculateAngleDelta(float previous, float current) {
        float delta = current - previous;
        // 确保差值在[-180, 180]范围内
        if (delta > 180.0f) {
            delta -= 360.0f;
        } else if (delta < -180.0f) {
            delta += 360.0f;
        }
        return delta;
    }
    
    /**
     * 获取战术地图HUD实例
     * @return 战术地图HUD实例
     */
    public static TacticalMapHUD getInstance() {
        return InstanceHolder.INSTANCE;
    }
    
    /**
     * 单例持有者
     */
    private static class InstanceHolder {
        private static final TacticalMapHUD INSTANCE = new TacticalMapHUD();
    }
}
