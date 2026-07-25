package org.espetro.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.TroopCountManager;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 载具管理器
 * 管理载具部署、追踪、冷却和复活
 */
public class VehicleManager {

    private static VehicleManager INSTANCE;
    private static final String VEHICLE_TAG = "espetro_vehicle";

    // factionId -> (vehicleType -> List<UUID>) 追踪活跃载具
    private final Map<String, Map<String, List<UUID>>> activeVehicles = new HashMap<>();
    private final Set<UUID> activeVehicleIds = new HashSet<>();
    private final Map<UUID, ActiveVehicleData> activeVehicleData = new HashMap<>();

    // factionId -> (vehicleType -> spawnTimeMillis) 刷新冷却
    private final Map<String, Map<String, Long>> cooldowns = new HashMap<>();

    private record ActiveVehicleData(String factionId, String vehicleType, int slotIndex, String team, boolean initial,
                                     ResourceKey<Level> dimension, BlockPos lastKnownPosition) {
        ActiveVehicleData withLocation(Entity entity) {
            return new ActiveVehicleData(factionId, vehicleType, slotIndex, team, initial,
                entity.level().dimension(), entity.blockPosition().immutable());
        }
    }

    private VehicleManager() {
        INSTANCE = this;
    }

    public static VehicleManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new VehicleManager();
        }
        return INSTANCE;
    }

    /**
     * 获取指定编制指定类型的活跃载具数量
     */
    public int getActiveCount(String factionId, String vehicleType) {
        List<UUID> vehicles = findList(factionId, vehicleType);
        return vehicles == null ? 0 : vehicles.size();
    }

    /**
     * 获取指定编制某类型载具列表
     */
    private List<UUID> getList(String factionId, String vehicleType) {
        return activeVehicles
            .computeIfAbsent(factionId, k -> new HashMap<>())
            .computeIfAbsent(vehicleType, k -> new ArrayList<>());
    }

    @Nullable
    private List<UUID> findList(String factionId, String vehicleType) {
        Map<String, List<UUID>> typeMap = activeVehicles.get(factionId);
        return typeMap != null ? typeMap.get(vehicleType) : null;
    }

    /**
     * 获取冷却剩余毫秒数，0表示无冷却
     */
    public long getCooldownRemaining(String factionId, String vehicleType) {
        Map<String, Long> factionCooldowns = cooldowns.get(factionId);
        Long lastSpawn = factionCooldowns != null ? factionCooldowns.get(vehicleType) : null;
        if (lastSpawn == null) return 0;

        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (cfg == null) return 0;

        long elapsed = System.currentTimeMillis() - lastSpawn;
        long remaining = cfg.respawnMillis() - elapsed;
        return Math.max(0, remaining);
    }

    /**
     * 指挥官部署载具
     * @return null 表示成功，否则返回错误消息
     */
    @Nullable
    public String deployVehicle(ServerPlayer commander, String vehicleType) {
        if (commander == null) {
            return "§c无效的部署者！";
        }
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE) {
            return "§c只能在部署或战斗阶段部署载具！";
        }
        if (!org.espetro.mapconfig.BattlefieldContext.isActiveBattlefield(commander.serverLevel())) {
            return "§c你不在当前战场维度！";
        }
        if (!org.espetro.team.VoteManager.getInstance().isCommander(commander.getUUID())) {
            return "§c只有当前指挥官可以部署载具！";
        }
        // 获取指挥官编制
        String factionId = ClassCountManager.getInstance().getPlayerFaction(commander.getUUID());
        if (factionId == null) {
            return "§c你没有选择编制！";
        }

        String team = Espetro.getPlayerTeam(commander);
        if (team == null) {
            return "§c无法确定你所在的攻守阵营！";
        }

        // 检查编制是否配置了该载具
        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (cfg == null) {
            return "§c当前编制不支持部署此载具类型！";
        }

        // 检查部署上限
        int current = getActiveCount(factionId, vehicleType);
        if (current >= cfg.max) {
            return "§c" + getDisplayName(factionId, vehicleType) + " 已达到部署上限！(" + current + "/" + cfg.max + ")";
        }

        // 检查冷却
        long cooldownRemaining = getCooldownRemaining(factionId, vehicleType);
        if (cooldownRemaining > 0) {
            long seconds = cooldownRemaining / 1000;
            return "§c" + getDisplayName(factionId, vehicleType) + " 刷新冷却中！剩余 " + seconds + " 秒。";
        }

        int slotIndex = findAvailableSlot(factionId, vehicleType, cfg);
        if (slotIndex < 0) {
            return "§c" + getDisplayName(factionId, vehicleType) + " 的每个载具槽位均已达到上限！";
        }
        ServerLevel level = resolveDeployLevel(commander);
        VehicleConfig.VehicleSlotConfig slot = cfg.slots.isEmpty() ? null : cfg.slots.get(slotIndex);
        VehicleConfig.DeploymentPointConfig deployment =
            slot != null ? slot.forTeam(team) : resolveDeploymentPoint(cfg, team);
        BlockPos spawnPos = resolveSpawnPosition(deployment);
        if (spawnPos == null) {
            return "§c该载具未在编制 JSON 中配置当前阵营的 deployment." + team + ".position 坐标！";
        }

        // 创建载具实体
        Entity vehicleEntity = createVehicleEntity(level, vehicleType, spawnPos, factionId, cfg,
            slot, deployment.yaw);
        if (vehicleEntity == null) {
            return "§c创建载具实体失败！";
        }

        level.getChunk(spawnPos);
        if (!level.addFreshEntity(vehicleEntity)) {
            vehicleEntity.discard();
            return "§c载具实体未能加入战场！";
        }

        // 记录
        trackVehicle(vehicleEntity, factionId, vehicleType, slotIndex, team, false);

        // 设置冷却
        cooldowns.computeIfAbsent(factionId, k -> new HashMap<>()).put(vehicleType, System.currentTimeMillis());

        commander.sendSystemMessage(Component.literal(
            "§a已部署 " + getDisplayName(factionId, vehicleType) + " §a！(" + (current + 1) + "/" + cfg.max + ") §7位置: " +
            spawnPos.getX() + " " + spawnPos.getY() + " " + spawnPos.getZ()));

        Espetro.LOGGER.info("指挥官 {} 部署载具: {} (队伍: {}, 编制: {}, 位置: {})",
            commander.getName().getString(), vehicleType, team, factionId, spawnPos);

        return null;
    }

    /**
     * 部署阶段开始时，为本局选中的编制预先部署每种已配置载具各一辆。
     * 该入口不向聊天栏发送消息；生成的载具会写入 activeVehicles，从而占用部署上限。
     *
     * @return 成功预部署的载具数量
     */
    public int deployInitialVehicles(String factionId, String team, ServerLevel level) {
        Map<String, VehicleConfig.VehicleTypeConfig> configs = VehicleConfig.getFactionVehicles(factionId);
        if (configs.isEmpty()) {
            return 0;
        }

        int deployed = 0;
        for (Map.Entry<String, VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String vehicleType = entry.getKey();
            VehicleConfig.VehicleTypeConfig cfg = entry.getValue();

            int slotCount = cfg.slots.isEmpty() ? 1 : cfg.slots.size();
            for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
                VehicleConfig.VehicleSlotConfig slot =
                    cfg.slots.isEmpty() ? null : cfg.slots.get(slotIndex);
                VehicleConfig.DeploymentPointConfig deployment =
                    slot != null ? slot.forTeam(team) : resolveDeploymentPoint(cfg, team);
                BlockPos spawnPos = resolveSpawnPosition(deployment);
                if (spawnPos == null) {
                    Espetro.LOGGER.warn("初始载具预部署失败: {} / {} 槽位{}缺少 {} 坐标",
                        factionId, vehicleType, slotIndex, team);
                    continue;
                }
                Entity vehicleEntity = createVehicleEntity(level, vehicleType, spawnPos, factionId,
                    cfg, slot, deployment.yaw);
                if (vehicleEntity == null) {
                    Espetro.LOGGER.warn("初始载具预部署失败: 无法创建 {} / {} 槽位{}的实体",
                        factionId, vehicleType, slotIndex);
                    continue;
                }
                level.getChunk(spawnPos);
                if (!level.addFreshEntity(vehicleEntity)) {
                    vehicleEntity.discard();
                    Espetro.LOGGER.warn("初始载具预部署失败: {} / {} 槽位{}未能加入战场",
                        factionId, vehicleType, slotIndex);
                    continue;
                }
                trackVehicle(vehicleEntity, factionId, vehicleType, slotIndex, team, true);
                deployed++;
            }
        }

        return deployed;
    }

    /**
     * 移除已死亡的载具追踪
     */
    public void onVehicleDeath(UUID entityId) {
        ActiveVehicleData data = removeTrackedVehicle(entityId);
        if (data != null) {
            applyVehicleTroopPenalty(data);
        }
    }

    /**
     * 移除未按死亡处理的载具追踪，例如卸载或重置。
     */
    public void onVehicleRemoved(UUID entityId) {
        removeTrackedVehicle(entityId);
    }

    @Nullable
    private ActiveVehicleData removeTrackedVehicle(UUID entityId) {
        activeVehicleIds.remove(entityId);
        ActiveVehicleData data = activeVehicleData.remove(entityId);

        if (data != null) {
            List<UUID> vehicles = findList(data.factionId(), data.vehicleType());
            if (vehicles != null) {
                vehicles.remove(entityId);
            }
            Espetro.LOGGER.debug("载具 {} 已移除追踪", entityId);
            return data;
        }

        for (Map.Entry<String, Map<String, List<UUID>>> factionEntry : activeVehicles.entrySet()) {
            for (Map.Entry<String, List<UUID>> typeEntry : factionEntry.getValue().entrySet()) {
                if (typeEntry.getValue().remove(entityId)) {
                    Espetro.LOGGER.debug("载具 {} 已移除追踪", entityId);
                    return null;
                }
            }
        }
        return null;
    }

    private void trackVehicle(Entity vehicle, String factionId, String vehicleType, int slotIndex,
                              String team, boolean initial) {
        UUID vehicleId = vehicle.getUUID();
        getList(factionId, vehicleType).add(vehicleId);
        activeVehicleIds.add(vehicleId);
        activeVehicleData.put(vehicleId, new ActiveVehicleData(
            factionId, vehicleType, slotIndex, team, initial,
            vehicle.level().dimension(), vehicle.blockPosition().immutable()));
    }

    /** 在实体换维度或卸载进区块前保存最后坐标，停服时据此重新加载并清理。 */
    public void updateVehicleLocation(Entity entity) {
        if (entity == null) return;
        ActiveVehicleData data = activeVehicleData.get(entity.getUUID());
        if (data != null) {
            activeVehicleData.put(entity.getUUID(), data.withLocation(entity));
        }
    }

    private void applyVehicleTroopPenalty(ActiveVehicleData data) {
        if (GameStateManager.getInstance().getCurrentPhase() != GamePhase.BATTLE) {
            return;
        }

        if (data.initial()) {
            Espetro.LOGGER.debug("初始载具 {} 被摧毁，跳过兵力惩罚（已在开战时扣除）", data.vehicleType());
            return;
        }

        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(data.factionId(), data.vehicleType());
        int penalty = cfg != null ? Math.max(0, cfg.troopValue) : 0;
        if (penalty <= 0) {
            return;
        }

        TroopCountManager troopManager = TroopCountManager.getInstance();
        String displayName = getDisplayName(data.factionId(), data.vehicleType());
        if ("ATTACK".equals(data.team())) {
            troopManager.modifyAttackTroops(-penalty);
            Espetro.broadcastToAll("§c☠ 攻方载具 [" + displayName + "] 被摧毁！- " + penalty + " 兵力");
            Espetro.LOGGER.info("攻方载具 {} 被摧毁，扣除 {} 兵力，剩余: {}", displayName, penalty, troopManager.getAttackTroops());
        } else if ("DEFEND".equals(data.team())) {
            troopManager.modifyDefendTroops(-penalty);
            Espetro.broadcastToAll("§9☠ 守方载具 [" + displayName + "] 被摧毁！- " + penalty + " 兵力");
            Espetro.LOGGER.info("守方载具 {} 被摧毁，扣除 {} 兵力，剩余: {}", displayName, penalty, troopManager.getDefendTroops());
        }
        troopManager.checkVictoryCondition();
    }

    /**
     * 检查某个实体是否是我们追踪的载具
     */
    public boolean isTrackedVehicle(UUID entityId) {
        return activeVehicleIds.contains(entityId);
    }

    /**
     * 计算指定阵营所有初始载具的 troopValue 总和
     */
    public int getInitialTroopValueForTeam(String team) {
        int total = 0;
        for (ActiveVehicleData data : activeVehicleData.values()) {
            if (data.initial() && team.equals(data.team())) {
                VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(data.factionId(), data.vehicleType());
                if (cfg != null) {
                    total += cfg.troopValue;
                }
            }
        }
        return total;
    }

    /**
     * 重置所有载具
     */
    public void reset() {
        removeAllDeployedVehicles(Espetro.getServer());
    }

    /**
     * 删除所有已加载的 Espetro 部署载具，并清空载具运行时状态。
     *
     * @return 实际删除的实体数量
     */
    public int removeAllDeployedVehicles(@Nullable MinecraftServer server) {
        int removedCount = 0;
        Set<UUID> removedIds = new HashSet<>();

        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                List<Entity> entitiesToRemove = new ArrayList<>();
                for (Entity entity : level.getAllEntities()) {
                    if (isDeployedVehicleEntity(entity)) {
                        entitiesToRemove.add(entity);
                    }
                }

                for (Entity entity : entitiesToRemove) {
                    if (!entity.isRemoved()) {
                        entity.discard();
                        removedIds.add(entity.getUUID());
                        removedCount++;
                    }
                }
            }

            for (UUID id : new ArrayList<>(activeVehicleIds)) {
                if (removedIds.contains(id)) {
                    continue;
                }

                Entity entity = findEntity(server, id);
                if (entity == null) {
                    ActiveVehicleData data = activeVehicleData.get(id);
                    if (data != null) {
                        ServerLevel level = server.getLevel(data.dimension());
                        if (level != null) {
                            level.getChunk(data.lastKnownPosition());
                            entity = level.getEntity(id);
                        }
                    }
                }
                if (entity != null && !entity.isRemoved()) {
                    entity.discard();
                    removedCount++;
                }
            }
        }

        clearRuntimeState();
        return removedCount;
    }

    /**
     * 只清空内存状态，不访问世界实体。服务器完全停止后调用。
     */
    public void clearRuntimeState() {
        activeVehicles.clear();
        activeVehicleIds.clear();
        activeVehicleData.clear();
        cooldowns.clear();
    }

    /**
     * 清理不再存在的载具记录，避免实体被外部命令移除后仍占用上限。
     */
    public void removeInvalidVehicles() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        for (UUID id : new ArrayList<>(activeVehicleIds)) {
            ActiveVehicleData data = activeVehicleData.get(id);
            if (data == null) {
                removeTrackedVehicle(id);
                continue;
            }

            ServerLevel level = server.getLevel(data.dimension());
            if (level == null || !level.hasChunkAt(data.lastKnownPosition())) {
                // 未加载区块中的实体仍然有效，保留追踪直到区块重载或停服清理。
                continue;
            }

            Entity entity = level.getEntity(id);
            if (entity == null) {
                // 区块/实体管理器切换期间也可能暂时查不到实体；离开世界事件负责真正移除追踪。
                continue;
            }
            if (entity.isRemoved()) {
                removeTrackedVehicle(id);
            } else {
                updateVehicleLocation(entity);
            }
        }
    }

    /**
     * 获取某编制的载具状态摘要
     */
    public List<String> getFactionVehicleStatus(String factionId) {
        List<String> result = new ArrayList<>();
        Map<String, VehicleConfig.VehicleTypeConfig> configs = VehicleConfig.getFactionVehicles(factionId);

        for (Map.Entry<String, VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String type = entry.getKey();
            VehicleConfig.VehicleTypeConfig cfg = entry.getValue();
            int count = getActiveCount(factionId, type);
            long cooldown = getCooldownRemaining(factionId, type);
            String cooldownStr = cooldown > 0 ? " §7(冷却 " + (cooldown / 1000) + "s)" : " §a✓";
            result.add(getDisplayName(factionId, type) + ": " + count + "/" + cfg.max + cooldownStr);
        }

        return result;
    }

    // ========== 辅助方法 ==========

    private ServerLevel resolveDeployLevel(ServerPlayer commander) {
        return org.espetro.mapconfig.BattlefieldContext.requireBattlefield(commander.server);
    }

    private int findAvailableSlot(String factionId, String vehicleType,
                                  VehicleConfig.VehicleTypeConfig cfg) {
        int slotCount = cfg.slots.isEmpty() ? 1 : cfg.slots.size();
        for (int i = 0; i < slotCount; i++) {
            int count = 0;
            for (ActiveVehicleData data : activeVehicleData.values()) {
                if (factionId.equals(data.factionId())
                    && vehicleType.equals(data.vehicleType())
                    && data.slotIndex() == i) {
                    count++;
                }
            }
            if (count < cfg.perMaxCount) return i;
        }
        return -1;
    }

    @Nullable
    private VehicleConfig.DeploymentPointConfig resolveDeploymentPoint(VehicleConfig.VehicleTypeConfig cfg, String team) {
        return cfg.deployment.forTeam(team);
    }

    @Nullable
    private BlockPos resolveSpawnPosition(@Nullable VehicleConfig.DeploymentPointConfig deployment) {
        if (deployment == null) {
            return null;
        }

        int[] position = deployment.position;
        if (position == null || position.length < 3) {
            return null;
        }
        return new BlockPos(position[0], position[1], position[2]);
    }

    /**
     * 创建载具实体（从配置读取 entity_type）
     */
    @Nullable
    private Entity createVehicleEntity(ServerLevel level, String vehicleType, BlockPos pos,
                                       String factionId, VehicleConfig.VehicleTypeConfig config,
                                       @Nullable VehicleConfig.VehicleSlotConfig slot, float yaw) {
        EntityType<?> entityTypeObj = slot != null ? slot.getEntityType() : config.getEntityType();
        if (entityTypeObj == null) {
            Espetro.LOGGER.warn("载具 {} 未配置 entity_type 或注册名无效", vehicleType);
            return null;
        }

        Entity entity = entityTypeObj.create(level);
        if (entity == null) return null;

        // 基础位置和名称
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;

        String name = config.displayName != null ? config.displayName : vehicleType;
        entity.setCustomName(Component.literal(name));
        entity.setCustomNameVisible(false);

        entity.setPos(x, y, z);
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.addTag(VEHICLE_TAG);
        entity.addTag("espetro_" + vehicleType);
        entity.addTag("espetro_vehicle_type_" + vehicleType);
        for (String tag : config.entityTags) {
            entity.addTag(tag);
        }
        return entity;
    }

    @Nullable
    private Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private boolean isDeployedVehicleEntity(Entity entity) {
        return entity.getTags().contains(VEHICLE_TAG) || activeVehicleIds.contains(entity.getUUID());
    }

    /**
     * 获取载具类型的显示名（含颜色），优先从配置读取
     */
    public static String getDisplayName(String factionId, String vehicleType) {
        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (cfg != null && cfg.displayName != null) {
            return cfg.displayName;
        }
        return vehicleType;
    }

    /**
     * 向指挥官发送可点击的载具部署信息
     */
    public static void sendDeployChatMessages(ServerPlayer player, String factionId) {
        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("════ ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00)).withBold(true)))
            .append(Component.literal("载具部署面板").withStyle(Style.EMPTY.withColor(0xFFAA00).withBold(true)))
            .append(Component.literal(" ════").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00)).withBold(true))));
        player.sendSystemMessage(Component.literal("编制: ").withStyle(Style.EMPTY.withColor(0xAAAAAA))
            .append(Component.literal(getFactionDisplayName(factionId)).withStyle(Style.EMPTY.withColor(0x00FFAA))));
        player.sendSystemMessage(Component.literal("载具将按 JSON 配置的部署位置生成。").withStyle(Style.EMPTY.withColor(0x888888)));
        player.sendSystemMessage(Component.literal(""));

        Map<String, VehicleConfig.VehicleTypeConfig> configs = VehicleConfig.getFactionVehicles(factionId);
        if (configs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c当前编制无载具配置。"));
            return;
        }

        for (Map.Entry<String, VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String type = entry.getKey();
            VehicleConfig.VehicleTypeConfig cfg = entry.getValue();
            String displayName = getDisplayName(factionId, type);
            int current = getInstance().getActiveCount(factionId, type);
            long cooldown = getInstance().getCooldownRemaining(factionId, type);
            String status = cooldown > 0 ? "§c冷却 " + (cooldown / 1000) + "s" : (current >= cfg.max ? "§6已满" : "§a就绪");

            player.sendSystemMessage(Component.empty()
                .append(Component.literal("▸ " + displayName + "  ").withStyle(Style.EMPTY.withColor(0xFFFFFF)))
                .append(Component.literal(status + " (" + current + "/" + cfg.max + " | " + cfg.respawnMinutes + "分钟刷新)").withStyle(Style.EMPTY.withColor(0xAAAAAA))));
            player.sendSystemMessage(Component.empty()
                .append(Component.literal("  [")
                    .withStyle(Style.EMPTY.withColor(0x555555)))
                .append(Component.literal("点击部署")
                    .withStyle(Style.EMPTY
                        .withColor(TextColor.fromRgb(0x55FF55))
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/vehicle spawn " + quoteCommandString(type)))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("§a点击部署 " + displayName))))
                )
                .append(Component.literal("]").withStyle(Style.EMPTY.withColor(0x555555)))
            );
        }

        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.literal("输入 /vehicle list 查看实时状态").withStyle(Style.EMPTY.withColor(0x888888)));
    }

    private static String getFactionDisplayName(String factionId) {
        return switch (factionId) {
            case "pla_medium_brigade" -> "PLA中型合成旅";
            case "pla_heavy_brigade" -> "PLA重型合成旅";
            case "russia_army" -> "俄罗斯陆上部队";
            case "russia_logistics" -> "俄罗斯支援部队";
            case "us_cavalry" -> "美国第一骑兵旅";
            case "us_airborne" -> "美国141空降部队";
            case "middle_east_militia" -> "中东联合武装";
            case "ukraine_irregular" -> "乌萨克非正规武装";
            default -> factionId;
        };
    }

    private static String quoteCommandString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
