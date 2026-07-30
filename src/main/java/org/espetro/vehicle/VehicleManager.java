package org.espetro.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import org.espetro.Espetro;
import org.espetro.mapconfig.VehSpawnSnapshot;
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
    /** Espetro 部署载具的持久阵营标识，供安全区等跨模组规则使用。 */
    public static final String VEHICLE_TEAM_KEY = "espetro_vehicle_team";
    /** Map-pad supply stations pre-spawned for every VehSpawn pit (attack+defend). */
    private static final String PAD_SUPPLY_TAG = "espetro_vehicle_pad_supply";
    /** Shared tag ESPoints uses to list vehicle supply stations on the tactical map. */
    public static final String SUPPLY_STATION_TAG = "espetro_vehicle_supply_station";
    /** PersistentData / tag prefix for owning team (must match ESPoints CapturePointManager). */
    public static final String SUPPLY_STATION_TEAM_KEY = "espetro_vehicle_supply_station_team";
    public static final String SUPPLY_STATION_ID_KEY = "espetro_vehicle_supply_station_id";
    public static final String SUPPLY_STATION_X_KEY = "espetro_vehicle_supply_station_x";
    public static final String SUPPLY_STATION_Y_KEY = "espetro_vehicle_supply_station_y";
    public static final String SUPPLY_STATION_Z_KEY = "espetro_vehicle_supply_station_z";
    public static final String SUPPLY_STATION_DISPLAY_NAME = "载具补给站";
    private static final ResourceLocation SUPPLY_STATION_ID =
        new ResourceLocation("dragonrise_reforge", "ammo_supply_station");
    private static final ResourceLocation SUPPLY_STATION_ITEM_ID =
        new ResourceLocation("dragonrise_reforge", "ammo_supply_station");
    /** Lateral offset from pit pose, perpendicular to yaw (blocks). */
    private static final double SUPPLY_SIDE_OFFSET = 6.0;
    /** Bound first-wave chunk work so deployment never creates an I/O spike. */
    private static final int INITIAL_CHUNKS_STARTED_PER_TICK = 2;
    private static final int INITIAL_CHUNKS_MAX_IN_FLIGHT = 4;

    // factionId -> (vehicleType -> List<UUID>) 追踪活跃载具
    private final Map<String, Map<String, List<UUID>>> activeVehicles = new HashMap<>();
    private final Set<UUID> activeVehicleIds = new HashSet<>();
    private final Map<UUID, ActiveVehicleData> activeVehicleData = new HashMap<>();
    // ESPoints 使用的轻量索引；区块卸载时保留，实体被明确销毁或回合结束时移除。
    private final Map<UUID, SupplyStationSnapshot> mappedSupplyStations = new HashMap<>();

    // factionId -> (vehicleType -> spawnTimeMillis) 刷新冷却
    private final Map<String, Map<String, Long>> cooldowns = new HashMap<>();

    private final InitialVehicleDeploymentLedger initialDeploymentLedger =
        new InitialVehicleDeploymentLedger();
    private final Map<ChunkPos, List<PendingInitialVehicle>> initialVehiclesByChunk =
        new LinkedHashMap<>();
    private final ArrayDeque<ChunkPos> pendingInitialChunks = new ArrayDeque<>();
    private final Set<ChunkPos> readyInitialChunks = new LinkedHashSet<>();
    private final Set<ChunkPos> ticketedInitialChunks = new LinkedHashSet<>();
    @Nullable
    private ServerLevel initialDeploymentLevel;
    private int initialChunksInFlight;
    private long initialDeploymentGeneration;
    private boolean initialDeploymentActive;
    private boolean initialDeploymentCompletionLogged;
    private int initialVehiclesPlanned;
    private int initialVehiclesSpawned;
    private int initialVehiclesFailed;

    private record ActiveVehicleData(String factionId, String vehicleType, int slotIndex, String team, boolean initial,
                                     ResourceKey<Level> dimension, BlockPos lastKnownPosition) {
        ActiveVehicleData withLocation(Entity entity) {
            return new ActiveVehicleData(factionId, vehicleType, slotIndex, team, initial,
                entity.level().dimension(), entity.blockPosition().immutable());
        }
    }

    public record SupplyStationSnapshot(UUID id, String name, String team, String dimension,
                                        int x, int y, int z) {
    }

    private record PendingInitialVehicle(
        InitialVehicleDeploymentLedger.SlotKey key,
        VehicleConfig.VehicleTypeConfig config,
        @Nullable VehicleConfig.VehicleSlotConfig slot,
        VehicleConfig.DeploymentPointConfig deployment,
        BlockPos spawnPosition
    ) {
    }

    public record InitialDeploymentStatus(int planned, int spawned, int failed, int pending) {
        public boolean settled() {
            return pending == 0;
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
        if (!level.hasChunkAt(spawnPos)) {
            return "§c载具部署区块尚未完成预载，请稍后重试！";
        }

        // 创建载具实体
        Entity vehicleEntity = createVehicleEntity(level, vehicleType, spawnPos, factionId, cfg,
            slot, deployment.yaw);
        if (vehicleEntity == null) {
            return "§c创建载具实体失败！";
        }

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
     * 在编制揭示阶段准备本局首批载具。每个 {@code entity} 配置槽位安排一辆，
     * 同一回合重复调用不会重复安排。出生区块通过有界异步任务预热，主线程不做
     * 同步区块读取；实体直到 {@link #activateInitialVehicleDeployment()} 才会生成。
     *
     * @return 本次新安排的槽位数量
     */
    public int prepareInitialVehicles(String factionId, String team, ServerLevel level) {
        if (level == null || factionId == null || factionId.isBlank()) {
            return 0;
        }
        String normalizedTeam = team == null ? "" : team.trim().toUpperCase(Locale.ROOT);
        if (!"ATTACK".equals(normalizedTeam) && !"DEFEND".equals(normalizedTeam)) {
            Espetro.LOGGER.warn("拒绝准备未知阵营的初始载具: faction={}, team={}",
                factionId, team);
            return 0;
        }
        if (initialDeploymentLevel != null && initialDeploymentLevel != level) {
            Espetro.LOGGER.error("拒绝跨维度混合初始载具队列: existing={}, requested={}",
                initialDeploymentLevel.dimension().location(), level.dimension().location());
            return 0;
        }
        initialDeploymentLevel = level;

        Map<String, VehicleConfig.VehicleTypeConfig> configs = VehicleConfig.getFactionVehicles(factionId);
        if (configs.isEmpty()) {
            return 0;
        }

        int scheduled = 0;
        for (Map.Entry<String, VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String vehicleType = entry.getKey();
            VehicleConfig.VehicleTypeConfig cfg = entry.getValue();

            int slotCount = cfg.slots.isEmpty() ? 1 : cfg.slots.size();
            for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
                InitialVehicleDeploymentLedger.SlotKey key =
                    new InitialVehicleDeploymentLedger.SlotKey(
                        factionId, vehicleType, slotIndex, normalizedTeam);
                if (!initialDeploymentLedger.claim(key)) {
                    continue;
                }
                scheduled++;
                initialVehiclesPlanned++;
                initialDeploymentCompletionLogged = false;

                VehicleConfig.VehicleSlotConfig slot =
                    cfg.slots.isEmpty() ? null : cfg.slots.get(slotIndex);
                VehicleConfig.DeploymentPointConfig deployment =
                    slot != null ? slot.forTeam(normalizedTeam)
                        : resolveDeploymentPoint(cfg, normalizedTeam);
                BlockPos spawnPos = resolveSpawnPosition(deployment);
                if (spawnPos == null) {
                    Espetro.LOGGER.warn("初始载具预部署失败: {} / {} 槽位{}缺少 {} 坐标",
                        factionId, vehicleType, slotIndex, normalizedTeam);
                    initialVehiclesFailed++;
                    continue;
                }

                PendingInitialVehicle pending = new PendingInitialVehicle(
                    key, cfg, slot, deployment, spawnPos.immutable());
                ChunkPos chunk = new ChunkPos(spawnPos);
                List<PendingInitialVehicle> chunkVehicles = initialVehiclesByChunk.get(chunk);
                if (chunkVehicles == null) {
                    chunkVehicles = new ArrayList<>();
                    initialVehiclesByChunk.put(chunk, chunkVehicles);
                    pendingInitialChunks.add(chunk);
                }
                chunkVehicles.add(pending);
            }
        }

        processInitialVehicleDeployments();
        return scheduled;
    }

    /**
     * 布防阶段入口：生成所有已预热完毕的首批载具；仍在加载的槽位会在对应
     * 区块 future 完成后落地。该操作幂等。
     *
     * @return 本次调用中立即生成的载具数量
     */
    public int activateInitialVehicleDeployment() {
        int before = initialVehiclesSpawned;
        initialDeploymentActive = true;
        for (ChunkPos chunk : new ArrayList<>(readyInitialChunks)) {
            spawnInitialVehiclesInChunk(chunk);
        }
        processInitialVehicleDeployments();
        logInitialDeploymentCompletionIfReady();
        return initialVehiclesSpawned - before;
    }

    /**
     * 兼容旧调用：安排指定编制后立即允许首批载具生成。
     */
    public int deployInitialVehicles(String factionId, String team, ServerLevel level) {
        int scheduled = prepareInitialVehicles(factionId, team, level);
        activateInitialVehicleDeployment();
        return scheduled;
    }

    /**
     * 每 tick 只启动少量区块 future；无首批载具任务时为空操作。
     */
    public void processInitialVehicleDeployments() {
        ServerLevel level = initialDeploymentLevel;
        if (level == null) {
            return;
        }

        int started = 0;
        while (started < INITIAL_CHUNKS_STARTED_PER_TICK
            && initialChunksInFlight < INITIAL_CHUNKS_MAX_IN_FLIGHT
            && !pendingInitialChunks.isEmpty()) {
            ChunkPos chunk = pendingInitialChunks.poll();
            if (!initialVehiclesByChunk.containsKey(chunk)) {
                continue;
            }
            started++;
            initialChunksInFlight++;
            long generation = initialDeploymentGeneration;
            ticketedInitialChunks.add(chunk);
            level.getChunkSource().addRegionTicket(
                TicketType.PORTAL, chunk, 1, chunk.getWorldPosition());
            level.getChunkSource()
                .getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true)
                .whenComplete((loaded, error) -> level.getServer().execute(() -> {
                    if (generation != initialDeploymentGeneration) {
                        return;
                    }
                    initialChunksInFlight--;
                    boolean available = error == null
                        && loaded != null
                        && loaded.left().isPresent();
                    if (!available) {
                        List<PendingInitialVehicle> failed =
                            initialVehiclesByChunk.remove(chunk);
                        readyInitialChunks.remove(chunk);
                        int failedCount = failed == null ? 0 : failed.size();
                        initialVehiclesFailed += failedCount;
                        Espetro.LOGGER.error(
                            "初始载具出生区块加载失败: chunk={}, vehicles={}, reason={}",
                            chunk, failedCount,
                            error == null ? "区块 future 未返回 FULL 区块" : error.getMessage());
                        releaseInitialChunkTicket(level, chunk);
                    } else if (initialDeploymentActive) {
                        spawnInitialVehiclesInChunk(chunk);
                    } else {
                        // 编制揭示期只预热；保留临时 ticket 到布防阶段入口。
                        readyInitialChunks.add(chunk);
                    }
                    processInitialVehicleDeployments();
                    logInitialDeploymentCompletionIfReady();
                }));
        }
        logInitialDeploymentCompletionIfReady();
    }

    public InitialDeploymentStatus getInitialDeploymentStatus() {
        int pending = initialVehiclesByChunk.values().stream()
            .mapToInt(List::size)
            .sum();
        return new InitialDeploymentStatus(
            initialVehiclesPlanned, initialVehiclesSpawned, initialVehiclesFailed, pending);
    }

    public boolean isInitialVehicleDeploymentSettled() {
        return initialDeploymentActive
            && pendingInitialChunks.isEmpty()
            && initialChunksInFlight == 0
            && initialVehiclesByChunk.isEmpty();
    }

    private void spawnInitialVehiclesInChunk(ChunkPos chunk) {
        ServerLevel level = initialDeploymentLevel;
        if (level == null) {
            return;
        }
        List<PendingInitialVehicle> vehicles = initialVehiclesByChunk.remove(chunk);
        readyInitialChunks.remove(chunk);
        if (vehicles == null) {
            releaseInitialChunkTicket(level, chunk);
            return;
        }

        for (PendingInitialVehicle pending : vehicles) {
            InitialVehicleDeploymentLedger.SlotKey key = pending.key();
            Entity vehicleEntity = createVehicleEntity(
                level,
                key.vehicleType(),
                pending.spawnPosition(),
                key.factionId(),
                pending.config(),
                pending.slot(),
                pending.deployment().yaw);
            if (vehicleEntity == null) {
                initialVehiclesFailed++;
                Espetro.LOGGER.warn(
                    "初始载具预部署失败: 无法创建 {} / {} 槽位{}的实体",
                    key.factionId(), key.vehicleType(), key.slotIndex());
                continue;
            }
            if (!level.addFreshEntity(vehicleEntity)) {
                vehicleEntity.discard();
                initialVehiclesFailed++;
                Espetro.LOGGER.warn(
                    "初始载具预部署失败: {} / {} 槽位{}未能加入战场",
                    key.factionId(), key.vehicleType(), key.slotIndex());
                continue;
            }
            trackVehicle(
                vehicleEntity,
                key.factionId(),
                key.vehicleType(),
                key.slotIndex(),
                key.team(),
                true);
            initialVehiclesSpawned++;
        }
        releaseInitialChunkTicket(level, chunk);
    }

    private void releaseInitialChunkTicket(ServerLevel level, ChunkPos chunk) {
        if (ticketedInitialChunks.remove(chunk)) {
            level.getChunkSource().removeRegionTicket(
                TicketType.PORTAL, chunk, 1, chunk.getWorldPosition());
        }
    }

    private void logInitialDeploymentCompletionIfReady() {
        if (!initialDeploymentActive
            || initialDeploymentCompletionLogged
            || !isInitialVehicleDeploymentSettled()) {
            return;
        }
        initialDeploymentCompletionLogged = true;
        Espetro.LOGGER.info(
            "编制首批载具部署完成: planned={}, spawned={}, failed={}",
            initialVehiclesPlanned, initialVehiclesSpawned, initialVehiclesFailed);
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
        String normalizedTeam = team == null ? "" : team.trim().toUpperCase(Locale.ROOT);
        if (!normalizedTeam.isBlank()) {
            vehicle.addTag("espetro_team_" + normalizedTeam);
            vehicle.getPersistentData().putString(VEHICLE_TEAM_KEY, normalizedTeam);
        }
        getList(factionId, vehicleType).add(vehicleId);
        activeVehicleIds.add(vehicleId);
        activeVehicleData.put(vehicleId, new ActiveVehicleData(
            factionId, vehicleType, slotIndex, normalizedTeam, initial,
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
     * 返回 Espetro 追踪载具的所属阵营；不会加载区块或遍历实体。
     */
    @Nullable
    public String getTrackedVehicleTeam(UUID entityId) {
        ActiveVehicleData data = activeVehicleData.get(entityId);
        return data == null || data.team() == null || data.team().isBlank()
            ? null
            : data.team();
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

        if (server != null) {
            Set<UUID> trackedEntities = new HashSet<>(activeVehicleIds);
            trackedEntities.addAll(mappedSupplyStations.keySet());
            for (UUID id : trackedEntities) {
                Entity entity = findEntity(server, id);
                if (entity != null && !entity.isRemoved()) {
                    entity.discard();
                    removedCount++;
                }
            }
        }

        cancelInitialVehicleDeployment(true);
        clearRuntimeCollections();
        return removedCount;
    }

    /**
     * 只清空内存状态，不访问世界实体。服务器完全停止后调用。
     */
    public void clearRuntimeState() {
        cancelInitialVehicleDeployment(false);
        clearRuntimeCollections();
    }

    private void clearRuntimeCollections() {
        activeVehicles.clear();
        activeVehicleIds.clear();
        activeVehicleData.clear();
        cooldowns.clear();
        mappedSupplyStations.clear();
    }

    private void cancelInitialVehicleDeployment(boolean releaseTickets) {
        ServerLevel level = initialDeploymentLevel;
        initialDeploymentGeneration++;
        if (releaseTickets && level != null) {
            for (ChunkPos chunk : new ArrayList<>(ticketedInitialChunks)) {
                try {
                    releaseInitialChunkTicket(level, chunk);
                } catch (Exception e) {
                    Espetro.LOGGER.debug("取消初始载具区块 ticket 失败: {} ({})",
                        chunk, e.getMessage());
                }
            }
        }
        initialDeploymentLedger.clear();
        initialVehiclesByChunk.clear();
        pendingInitialChunks.clear();
        readyInitialChunks.clear();
        ticketedInitialChunks.clear();
        initialDeploymentLevel = null;
        initialChunksInFlight = 0;
        initialDeploymentActive = false;
        initialDeploymentCompletionLogged = false;
        initialVehiclesPlanned = 0;
        initialVehiclesSpawned = 0;
        initialVehiclesFailed = 0;
    }

    public void registerMappedSupplyStation(@Nullable Entity entity) {
        if (!isMappedSupplyStation(entity) || entity == null) {
            return;
        }
        CompoundTag data = entity.getPersistentData();
        String team = data.getString(SUPPLY_STATION_TEAM_KEY);
        if (team == null || team.isBlank()) {
            return;
        }
        team = team.trim().toUpperCase(Locale.ROOT);
        BlockPos pos = entity.blockPosition();
        String name = entity.getCustomName() == null
            ? SUPPLY_STATION_DISPLAY_NAME
            : entity.getCustomName().getString();
        mappedSupplyStations.put(entity.getUUID(), new SupplyStationSnapshot(
            entity.getUUID(),
            name,
            team,
            entity.level().dimension().location().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ()));
    }

    public void unregisterMappedSupplyStation(UUID entityId) {
        if (entityId != null) {
            mappedSupplyStations.remove(entityId);
        }
    }

    public List<SupplyStationSnapshot> getMappedSupplyStationSnapshots() {
        return List.copyOf(mappedSupplyStations.values());
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
        String spawnNbt = slot != null && slot.nbt != null && !slot.nbt.isBlank()
            ? slot.nbt
            : config.nbt;
        applyVehicleSpawnNbt(entity, spawnNbt);
        return entity;
    }

    /**
     * Apply formation spawn SNBT energy without calling {@link Entity#load(CompoundTag)}.
     * <p>
     * Superb Warfare's {@code VehicleEntity#readAdditionalSaveData} unconditionally
     * reads Turret/Wheel/Engine health floats (missing keys become 0) and will kill a
     * freshly created vehicle if only {@code Energy} is present. Fill FE via
     * {@link ForgeCapabilities#ENERGY} instead — no superbwarfare / dragonrise dependency.
     */
    private void applyVehicleSpawnNbt(Entity entity, @Nullable String snbt) {
        int energyAmount = Integer.MAX_VALUE;
        CompoundTag extras = null;
        if (snbt != null && !snbt.isBlank()) {
            try {
                extras = TagParser.parseTag(snbt);
            } catch (Exception e) {
                Espetro.LOGGER.warn("载具部署 NBT 无效，将仅尝试满电: {} ({})", snbt, e.getMessage());
            }
        }
        if (extras != null && extras.contains("Energy", Tag.TAG_INT)) {
            energyAmount = extras.getInt("Energy");
            extras.remove("Energy");
        }
        fillVehicleEnergy(entity, energyAmount);
        if (extras != null && !extras.isEmpty()) {
            extras.remove("UUID");
            if (!extras.isEmpty()) {
                // Partial Entity#load wipes SW component health — refuse non-Energy keys.
                Espetro.LOGGER.warn(
                    "忽略载具部署中的非 Energy NBT 键（避免 SW 残缺 load 清血） type={} keys={}",
                    entity.getType(), extras.getAllKeys());
            }
        }
    }

    private void fillVehicleEnergy(Entity entity, int amount) {
        if (amount <= 0) return;
        try {
            entity.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> {
                if (!storage.canReceive()) return;
                int guard = 0;
                while (storage.getEnergyStored() < storage.getMaxEnergyStored() && guard++ < 64) {
                    int room = storage.getMaxEnergyStored() - storage.getEnergyStored();
                    if (room <= 0) break;
                    int received = storage.receiveEnergy(Math.min(amount, room), false);
                    if (received <= 0) break;
                }
            });
        } catch (Exception e) {
            Espetro.LOGGER.warn("载具满电失败 (type={}): {}", entity.getType(), e.getMessage());
        }
    }

    /**
     * After a battlefield map is activated, spawn one Dragonrise ammo supply station
     * beside every VehSpawn pit for both ATTACK and DEFEND poses.
     * Idempotent: clears existing pad stations in this level first.
     */
    public int spawnPadSupplyStations(ServerLevel level, @Nullable VehSpawnSnapshot spawn) {
        if (level == null || spawn == null || !spawn.isValid()) {
            return 0;
        }
        clearPadSupplyStations(level);

        EntityType<?> stationType = BuiltInRegistries.ENTITY_TYPE.getOptional(SUPPLY_STATION_ID).orElse(null);
        if (stationType == null) {
            Espetro.LOGGER.warn("未注册实体 {}，跳过载具坑补给站预放", SUPPLY_STATION_ID);
            return 0;
        }

        int spawned = 0;
        for (Map.Entry<String, List<VehSpawnSnapshot.SpawnPoint>> entry : spawn.spawnPointsByType.entrySet()) {
            String type = entry.getKey();
            List<VehSpawnSnapshot.SpawnPoint> points = entry.getValue();
            if (points == null) continue;
            for (VehSpawnSnapshot.SpawnPoint point : points) {
                if (point == null) continue;
                if (spawnOnePadSupply(level, stationType, point.attack(), "ATTACK", type, point.id())) {
                    spawned++;
                }
                if (spawnOnePadSupply(level, stationType, point.defend(), "DEFEND", type, point.id())) {
                    spawned++;
                }
            }
        }
        Espetro.LOGGER.info("载具坑补给站预放完成: {} 个 (维度 {})", spawned, level.dimension().location());
        return spawned;
    }

    public int clearPadSupplyStations(@Nullable ServerLevel level) {
        if (level == null) return 0;
        String dimension = level.dimension().location().toString();
        int removed = 0;
        for (SupplyStationSnapshot snapshot
                : new ArrayList<>(mappedSupplyStations.values())) {
            if (!dimension.equals(snapshot.dimension())) {
                continue;
            }
            Entity entity = level.getEntity(snapshot.id());
            if (entity != null && !entity.isRemoved()
                && isPadSupplyStation(entity)) {
                entity.discard();
                removed++;
            }
            mappedSupplyStations.remove(snapshot.id());
        }
        return removed;
    }

    private boolean spawnOnePadSupply(ServerLevel level, EntityType<?> stationType,
                                      @Nullable VehSpawnSnapshot.Pose pose, String team,
                                      String vehicleType, String pitId) {
        if (pose == null) return false;
        BlockPos stationPos = getSupplyStationPosition(pose);
        if (!level.hasChunkAt(stationPos)) {
            Espetro.LOGGER.warn("载具坑补给站区块尚未预载，跳过 {} ({}/{})",
                stationPos, vehicleType, pitId);
            return false;
        }

        Entity entity = stationType.create(level);
        if (entity == null) {
            Espetro.LOGGER.warn("无法创建补给站实体 at {} ({}/{})", stationPos, vehicleType, pitId);
            return false;
        }

        double x = stationPos.getX() + 0.5;
        double y = stationPos.getY();
        double z = stationPos.getZ() + 0.5;
        entity.setPos(x, y, z);
        entity.setYRot(pose.yaw());
        entity.setYHeadRot(pose.yaw());
        entity.setCustomName(Component.literal(SUPPLY_STATION_DISPLAY_NAME));
        entity.setCustomNameVisible(false);
        entity.addTag(PAD_SUPPLY_TAG);
        entity.addTag("espetro_pad_type_" + vehicleType);
        entity.addTag("espetro_pad_id_" + pitId);
        applySupplyStationMapTags(entity, team, "pad_" + vehicleType + "_" + pitId + "_" + team);

        // Full FE if the station exposes energy — never Entity#load partial NBT.
        fillVehicleEnergy(entity, Integer.MAX_VALUE);

        if (!level.addFreshEntity(entity)) {
            entity.discard();
            Espetro.LOGGER.warn("补给站未能加入世界 at {} ({}/{})", stationPos, vehicleType, pitId);
            return false;
        }
        return true;
    }

    /**
     * Whether this entity is a Dragonrise ammo supply station (item-placeable vehicle pad station).
     */
    public static boolean isAmmoSupplyStationEntity(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return SUPPLY_STATION_ID.equals(id);
    }

    /**
     * Whether the stack is the Dragonrise ammo supply station deployer item.
     */
    public static boolean isSupplyStationDeployerItem(@Nullable net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return SUPPLY_STATION_ITEM_ID.equals(id);
    }

    /**
     * Already registered for ESPoints tactical map (pad pre-spawn or commander place).
     */
    public static boolean isMappedSupplyStation(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.getTags().contains(SUPPLY_STATION_TAG)
            || entity.getTags().contains(PAD_SUPPLY_TAG)) {
            return true;
        }
        CompoundTag data = entity.getPersistentData();
        return data.contains(SUPPLY_STATION_TEAM_KEY)
            || data.contains(SUPPLY_STATION_ID_KEY);
    }

    /**
     * Tag a player-placed commander supply station so ESPoints SyncBastions includes it
     * on the tactical map for the owning team.
     *
     * @param team ATTACK / DEFEND (or null to leave team inference to ESPoints)
     */
    public static void tagCommanderSupplyStation(@Nullable Entity entity, @Nullable String team) {
        if (entity == null || !isAmmoSupplyStationEntity(entity)) {
            return;
        }
        if (isMappedSupplyStation(entity) && entity.getTags().contains(SUPPLY_STATION_TAG)) {
            // Ensure display name for map label even if partially tagged
            if (entity.getCustomName() == null) {
                entity.setCustomName(Component.literal(SUPPLY_STATION_DISPLAY_NAME));
                entity.setCustomNameVisible(false);
            }
            return;
        }
        String stationId = "commander_" + entity.getUUID();
        applySupplyStationMapTags(entity, team, stationId);
        Espetro.LOGGER.info("指挥官载具补给站已标记: team={} id={} at {}",
            team, stationId, entity.blockPosition());
    }

    /**
     * Apply tags + persistent data that ESPoints CapturePointManager scans each tick.
     */
    public static void applySupplyStationMapTags(@Nullable Entity entity, @Nullable String team,
                                                 @Nullable String stationId) {
        if (entity == null) {
            return;
        }
        entity.setCustomName(Component.literal(SUPPLY_STATION_DISPLAY_NAME));
        entity.setCustomNameVisible(false);
        entity.addTag(SUPPLY_STATION_TAG);

        if (team != null && !team.isBlank()) {
            String normalized = team.trim().toUpperCase(Locale.ROOT);
            entity.addTag(SUPPLY_STATION_TEAM_KEY + "_" + normalized);
            entity.addTag("espetro_team_" + normalized);

            CompoundTag data = entity.getPersistentData();
            data.putString(SUPPLY_STATION_TEAM_KEY, normalized);
            if (stationId != null && !stationId.isBlank()) {
                data.putString(SUPPLY_STATION_ID_KEY, stationId);
                entity.addTag(SUPPLY_STATION_ID_KEY + "_" + stationId);
            }
            BlockPos pos = entity.blockPosition();
            data.putInt(SUPPLY_STATION_X_KEY, pos.getX());
            data.putInt(SUPPLY_STATION_Y_KEY, pos.getY());
            data.putInt(SUPPLY_STATION_Z_KEY, pos.getZ());
        } else if (stationId != null && !stationId.isBlank()) {
            entity.getPersistentData().putString(SUPPLY_STATION_ID_KEY, stationId);
        }
    }

    /**
     * Offset pit pose laterally (right of facing) so the station sits beside the vehicle pad.
     */
    public static BlockPos getSupplyStationPosition(VehSpawnSnapshot.Pose pose) {
        float yawRad = pose.yaw() * ((float) Math.PI / 180f);
        // Minecraft yaw: 0 = south (+Z), 90 = west (-X). Forward = (-sin, cos); right = (-cos, -sin)?
        // Forward: (-sin(yaw), cos(yaw)); right (perpendicular): (-cos(yaw), -sin(yaw)) for +90° CW.
        double rightX = -Mth.cos(yawRad);
        double rightZ = -Mth.sin(yawRad);
        int x = Mth.floor(pose.x() + rightX * SUPPLY_SIDE_OFFSET);
        int y = Mth.floor(pose.y());
        int z = Mth.floor(pose.z() + rightZ * SUPPLY_SIDE_OFFSET);
        return new BlockPos(x, y, z);
    }

    private static boolean isPadSupplyStation(Entity entity) {
        return entity != null && (entity.getTags().contains(PAD_SUPPLY_TAG)
            || entity.getTags().contains(SUPPLY_STATION_TAG));
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
