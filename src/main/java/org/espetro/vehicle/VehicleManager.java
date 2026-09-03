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
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import org.espetro.Espetro;
import org.espetro.api.EspetroAPI;
import org.espetro.mapconfig.VehSpawnSnapshot;
import org.espetro.team.ClassCountManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.TroopCountManager;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 载具管理器
 * 管理载具首发、自动刷新、追踪、冷却和状态查询
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
    /** 主重生点补给站相对重生点的侧向偏移（格）。 */
    private static final double MAIN_BASE_SUPPLY_SIDE_OFFSET = 3.0;
    /** 主重生点补给站识别 tag。 */
    private static final String MAIN_BASE_SUPPLY_TAG = "espetro_main_base_supply_station";
    /** Bound first-wave chunk work so deployment never creates an I/O spike. */
    private static final int INITIAL_CHUNKS_STARTED_PER_TICK = 2;
    private static final int INITIAL_CHUNKS_MAX_IN_FLIGHT = 4;
    /** 开局（部署阶段开始）后首辆初始载具的生成延迟（tick）。 */
    private static final int INITIAL_SPAWN_FIRST_DELAY_TICKS = 20 * 5;
    /** 相邻两辆初始载具的生成间隔（tick）：每秒一辆。 */
    private static final int INITIAL_SPAWN_INTERVAL_TICKS = 20;
    /** 等待已选队玩家进入战场的超时兜底（tick）：超时后强制启动刷新队列。 */
    private static final int INITIAL_SPAWN_ARM_TIMEOUT_TICKS = 20 * 30;
    /** 方案 B：实体生成后向在线玩家重发 spawn 包的延迟点（tick）：1s / 3s / 10s。 */
    private static final long[] SPAWN_RESEND_DELAYS_TICKS = {20L, 60L, 200L};

    // factionId -> (vehicleType -> List<UUID>) 追踪活跃载具
    private final Map<String, Map<String, List<UUID>>> activeVehicles = new HashMap<>();
    private final Set<UUID> activeVehicleIds = new HashSet<>();
    private final Map<UUID, ActiveVehicleData> activeVehicleData = new HashMap<>();
    /** 载具补给库存：entity UUID → supply state */
    private final Map<UUID, VehicleSupplyState> vehicleSupplies = new HashMap<>();
    // ESPoints 使用的轻量索引；区块卸载时保留，实体被明确销毁或回合结束时移除。
    private final Map<UUID, SupplyStationSnapshot> mappedSupplyStations = new HashMap<>();

    // factionId -> (vehicleType -> cooldownUntilMillis) 冷却截止时刻
    private final Map<String, Map<String, Long>> cooldowns = new HashMap<>();
    /** 被摧毁载具的自动刷新队列：key → 就绪时间戳（升序）。 */
    private final Map<RespawnKey, PriorityQueue<Long>> autoRespawnQueue = new HashMap<>();

    private record RespawnKey(String team, String factionId, String vehicleType) {
    }

    private final InitialVehicleDeploymentLedger initialDeploymentLedger =
        new InitialVehicleDeploymentLedger();
    /** 首发载具按到期时间排序；到期前不申请区块 ticket，也不加载出生区块。 */
    private final PriorityQueue<PendingInitialVehicle> delayedInitialVehicles =
        new PriorityQueue<>(Comparator.comparingLong(PendingInitialVehicle::readyAtEpochMs));
    private final Map<ChunkPos, List<PendingInitialVehicle>> initialVehiclesByChunk =
        new LinkedHashMap<>();
    private final ArrayDeque<ChunkPos> pendingInitialChunks = new ArrayDeque<>();
    private final Set<ChunkPos> readyInitialChunks = new LinkedHashSet<>();
    private final Set<ChunkPos> ticketedInitialChunks = new LinkedHashSet<>();
    /** 已就绪（出生区块 FULL 加载完成）的初始载具刷新队列：随机顺序，每秒生成一辆。 */
    private final ArrayDeque<PendingInitialVehicle> initialSpawnQueue = new ArrayDeque<>();
    /** 下一辆初始载具允许生成的服务器 tick；-1 表示尚未启动节流。 */
    private long nextInitialSpawnTick = -1;
    /** 是否正在等待已选队玩家全部进入战场（进入后才开始 5 秒倒计时）。 */
    private boolean initialSpawnArming;
    /** 进入等待状态的 tick，用于超时兜底。 */
    private long initialSpawnArmStartedTick = -1;
    /** 战场激活时置位：等玩家进入战场后再放置部署点弹药箱与主重生点补给站。 */
    private boolean deferredSupplyPlacementPending;
    /** 方案 B：待重发 spawn 包的实体：entityId -> 重发 tick 队列（升序）。 */
    private final Map<Integer, ArrayDeque<Long>> spawnResendPlans = new HashMap<>();
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

    /** 单辆载具的弹药/建材库存 */
    public static final class VehicleSupplyState {
        private int ammo;
        private int construction;
        private final int maxCapacity;
        private final boolean canCarryConstruction;

        public VehicleSupplyState(int maxCapacity, boolean canCarryConstruction) {
            this.maxCapacity = maxCapacity;
            this.canCarryConstruction = canCarryConstruction;
        }

        public int getAmmo() { return ammo; }
        public int getConstruction() { return construction; }
        public int getMaxCapacity() { return maxCapacity; }
        public boolean canCarryConstruction() { return canCarryConstruction; }
        public int getTotalUsed() { return ammo + construction; }
        public int getFreeSpace() { return Math.max(0, maxCapacity - ammo - construction); }

        public int addAmmo(int amount) {
            int space = getFreeSpace();
            int added = Math.min(amount, space);
            ammo += added;
            return added;
        }

        public int removeAmmo(int amount) {
            int removed = Math.min(amount, ammo);
            ammo -= removed;
            return removed;
        }

        public int addConstruction(int amount) {
            if (!canCarryConstruction) return 0;
            int space = getFreeSpace();
            int added = Math.min(amount, space);
            construction += added;
            return added;
        }

        public int removeConstruction(int amount) {
            int removed = Math.min(amount, construction);
            construction -= removed;
            return removed;
        }

        public boolean canAffordAmmo(int amount) { return ammo >= Math.max(0, amount); }

        /** 战斗/普通载具：弹药装满，建材清零。 */
        public void fillAmmo() {
            ammo = maxCapacity;
            construction = 0;
        }

        /** 补给载具：弹药与建材各占一半容量。 */
        public void fillHalf() {
            ammo = maxCapacity / 2;
            if (canCarryConstruction) {
                construction = maxCapacity / 2;
            } else {
                construction = 0;
            }
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
        BlockPos spawnPosition,
        long readyAtEpochMs
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

    /** Team-scoped count; both sides may select the same formation. */
    public int getActiveCount(String team, String factionId, String vehicleType) {
        int count = 0;
        for (ActiveVehicleData data : activeVehicleData.values()) {
            if (factionId.equals(data.factionId())
                && vehicleType.equals(data.vehicleType())
                && team.equalsIgnoreCase(data.team())) {
                count++;
            }
        }
        return count;
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
        return getCooldownRemaining(
            GameStateManager.getTeamFromFactionStatic(factionId), factionId, vehicleType);
    }

    public long getCooldownRemaining(@Nullable String team, String factionId, String vehicleType) {
        Map<String, Long> factionCooldowns = cooldowns.get(cooldownOwner(team, factionId));
        Long until = factionCooldowns != null ? factionCooldowns.get(vehicleType) : null;
        if (until == null) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    /**
     * 部署阶段开始时为双方编制各车型写入首次部署冷却。
     * 不生成实体；指挥官随后通过部署面板按冷却召唤。
     */
    public void armInitialDeployCooldowns(@Nullable String attackFaction, String attackTeam,
                                          @Nullable String defendFaction, String defendTeam) {
        armFactionInitialCooldowns(attackFaction, attackTeam);
        armFactionInitialCooldowns(defendFaction, defendTeam);
    }

    private void armFactionInitialCooldowns(@Nullable String factionId, @Nullable String team) {
        if (factionId == null || factionId.isBlank() || team == null) return;
        Map<String, VehicleConfig.VehicleTypeConfig> types = VehicleConfig.getFactionVehicles(factionId);
        if (types == null || types.isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, Long> map = cooldowns.computeIfAbsent(
            cooldownOwner(team, factionId), k -> new HashMap<>());
        for (Map.Entry<String, VehicleConfig.VehicleTypeConfig> e : types.entrySet()) {
            int delaySec = e.getValue().initialDeployDelaySeconds(team);
            if (delaySec > 0) {
                map.put(e.getKey(), now + delaySec * 1000L);
            } else {
                map.remove(e.getKey());
            }
        }
        Espetro.LOGGER.info("编制 {} 作为 {} 的首次载具冷却已写入 ({} 种)", factionId, team, types.size());
    }

    /** 部署成功后启动 respawn 冷却。 */
    private void startRespawnCooldown(String team, String factionId, String vehicleType) {
        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        long ms = cfg != null ? cfg.respawnMillis() : 0L;
        if (ms <= 0) {
            Map<String, Long> map = cooldowns.get(cooldownOwner(team, factionId));
            if (map != null) map.remove(vehicleType);
            return;
        }
        cooldowns.computeIfAbsent(cooldownOwner(team, factionId), k -> new HashMap<>())
            .put(vehicleType, System.currentTimeMillis() + ms);
    }

    /** 服务器 tick：处理被摧毁载具的自动刷新。 */
    public void onServerTick() {
        processAutoRespawns();
        processSpawnResends();
    }

    /**
     * 方案 B：登记一个实体，稍后向所有在线玩家重发 spawn 包（1s/3s/10s 各一次）。
     * 客户端对「已存在实体」会替换、对「未注册实体」会补加，因此重发是安全的；
     * 与方案 A（客户端补加）互补：即使 spawn 包在区块未加载时被丢弃，也能补救。
     */
    public void scheduleSpawnResend(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        ServerLevel level = entity.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        if (level == null) {
            return;
        }
        ArrayDeque<Long> plan = new ArrayDeque<>();
        long now = level.getGameTime();
        for (long delay : SPAWN_RESEND_DELAYS_TICKS) {
            plan.addLast(now + delay);
        }
        spawnResendPlans.put(entity.getId(), plan);
    }

    private void processSpawnResends() {
        if (spawnResendPlans.isEmpty()) {
            return;
        }
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return;
        }
        ServerLevel level = org.espetro.mapconfig.BattlefieldContext.requireBattlefield(server);
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        java.util.Iterator<Map.Entry<Integer, ArrayDeque<Long>>> iterator =
            spawnResendPlans.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ArrayDeque<Long>> entry = iterator.next();
            ArrayDeque<Long> plan = entry.getValue();
            while (!plan.isEmpty() && plan.peekFirst() <= now) {
                plan.pollFirst();
                Entity entity = level.getEntity(entry.getKey());
                if (entity == null || entity.isRemoved()) {
                    break; // 实体已消失，放弃后续重发
                }
                // 向所有与实体同维度的在线玩家重发 spawn 包
                // （原版客户端同 ID 已存在时替换，不存在时补加）
                var packet = new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(entity);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.connection != null
                        && player.serverLevel() == level) {
                        player.connection.send(packet);
                    }
                }
            }
            if (plan.isEmpty()) {
                iterator.remove();
            }
        }
    }

    /** 载具被摧毁后开始刷新计时。 */
    private void scheduleAutoRespawn(String team, String factionId, String vehicleType) {
        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        long ms = cfg != null ? cfg.respawnMillis() : 0L;
        long readyAt = System.currentTimeMillis() + Math.max(0L, ms);
        autoRespawnQueue.computeIfAbsent(
            new RespawnKey(team, factionId, vehicleType), ignored -> new PriorityQueue<>())
            .add(readyAt);
        refreshAutoRespawnCooldown(team, factionId, vehicleType);
        broadcastVehicleInfoToTeam(team);
        Espetro.LOGGER.info("载具已摧毁，自动刷新已排队: {} / {} / {}, delay={}s",
            team, factionId, vehicleType, Math.max(0L, ms) / 1000L);
    }

    private void processAutoRespawns() {
        if (autoRespawnQueue.isEmpty()) return;
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE) return;
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        ServerLevel level = org.espetro.mapconfig.BattlefieldContext.requireBattlefield(server);
        if (level == null || !org.espetro.mapconfig.BattlefieldContext.isActiveBattlefield(level)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<RespawnKey, PriorityQueue<Long>>> iterator =
                 autoRespawnQueue.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<RespawnKey, PriorityQueue<Long>> entry = iterator.next();
            RespawnKey key = entry.getKey();
            PriorityQueue<Long> queue = entry.getValue();
            while (!queue.isEmpty() && queue.peek() <= now) {
                if (!tryAutoRespawn(level, key)) {
                    break;
                }
                queue.poll();
                refreshAutoRespawnCooldown(key.team(), key.factionId(), key.vehicleType());
            }
            if (queue.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private boolean tryAutoRespawn(ServerLevel level, RespawnKey key) {
        String team = key.team();
        String factionId = key.factionId();
        String vehicleType = key.vehicleType();
        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (cfg == null) return false;
        if (getActiveCount(team, factionId, vehicleType) >= cfg.max) return false;

        int slotIndex = findAvailableSlot(team, factionId, vehicleType, cfg);
        if (slotIndex < 0) return false;
        VehicleConfig.VehicleSlotConfig slot = cfg.slots.isEmpty() ? null : cfg.slots.get(slotIndex);
        VehicleConfig.DeploymentPointConfig deployment =
            slot != null ? slot.forTeam(team) : resolveDeploymentPoint(cfg, team);
        BlockPos spawnPos = resolveSpawnPosition(deployment);
        if (spawnPos == null) return false;
        if (!level.hasChunkAt(spawnPos)) {
            // Respawn points are commonly unattended by the time a cooldown expires. Force-load
            // this single spawn chunk on demand; otherwise the ready queue can remain stuck forever.
            try {
                level.getChunkAt(spawnPos);
            } catch (RuntimeException e) {
                Espetro.LOGGER.warn("载具自动刷新无法加载出生区块: {} / {} / {} at {}",
                    team, factionId, vehicleType, spawnPos, e);
                return false;
            }
        }

        Entity vehicle = createVehicleEntity(level, vehicleType, spawnPos, factionId, cfg,
            slot, deployment != null ? deployment.yaw : 0f);
        if (vehicle == null) return false;
        if (!level.addFreshEntity(vehicle)) {
            vehicle.discard();
            return false;
        }
        trackVehicle(vehicle, factionId, vehicleType, slotIndex, team, false);
        broadcastVehicleInfoToTeam(team);
        // 方案 B：稍后重发 spawn 包，兜底客户端区块未就绪导致的丢失
        scheduleSpawnResend(vehicle);
        Espetro.LOGGER.info("载具自动刷新: {} / {} / {} at {}", team, factionId, vehicleType, spawnPos);
        return true;
    }

    private void refreshAutoRespawnCooldown(String team, String factionId, String vehicleType) {
        RespawnKey key = new RespawnKey(team, factionId, vehicleType);
        PriorityQueue<Long> queue = autoRespawnQueue.get(key);
        Map<String, Long> map = cooldowns.computeIfAbsent(
            cooldownOwner(team, factionId), ignored -> new HashMap<>());
        if (queue == null || queue.isEmpty()) {
            map.remove(vehicleType);
        } else {
            map.put(vehicleType, queue.peek());
        }
    }

    /** 向本队所有在线玩家推送载具信息快照（用于信息界面实时刷新）。 */
    private void broadcastVehicleInfoToTeam(String team) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || team == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!team.equals(Espetro.getPlayerTeam(player))) continue;
            String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
            if (factionId != null) {
                org.espetro.network.NetworkManager.syncVehicleDeployScreen(player, factionId);
            }
        }
    }

    private static String cooldownOwner(@Nullable String team, String factionId) {
        String normalized = team == null ? "UNKNOWN" : team.trim().toUpperCase(Locale.ROOT);
        return normalized + "|" + factionId;
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

        // 首发延迟到期后仍可能正在异步加载出生区块。此时禁止手动插队，避免
        // 自动首发与指挥官部署在同一个槽位重复生成。
        if (hasPendingInitialVehicle(team, factionId, vehicleType)) {
            return "§e该类型首批载具正在自动部署，请稍候！";
        }

        // 检查部署上限
        int current = getActiveCount(team, factionId, vehicleType);
        if (current >= cfg.max) {
            return "§c" + getDisplayName(factionId, vehicleType) + " 已达到部署上限！(" + current + "/" + cfg.max + ")";
        }

        // 检查冷却
        long cooldownRemaining = getCooldownRemaining(team, factionId, vehicleType);
        if (cooldownRemaining > 0) {
            long seconds = cooldownRemaining / 1000;
            return "§c" + getDisplayName(factionId, vehicleType) + " 刷新冷却中！剩余 " + seconds + " 秒。";
        }

        int slotIndex = findAvailableSlot(team, factionId, vehicleType, cfg);
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

        // 部署成功后启动刷新冷却
        startRespawnCooldown(team, factionId, vehicleType);

        commander.sendSystemMessage(Component.literal(
            "§a已部署 " + getDisplayName(factionId, vehicleType) + " §a！(" + (current + 1) + "/" + cfg.max + ") §7位置: " +
            spawnPos.getX() + " " + spawnPos.getY() + " " + spawnPos.getZ()));

        Espetro.LOGGER.info("指挥官 {} 部署载具: {} (队伍: {}, 编制: {}, 位置: {})",
            commander.getName().getString(), vehicleType, team, factionId, spawnPos);

        // 事件驱动刷新载具面板（冷却/在场数），不强制客户端关屏
        org.espetro.network.NetworkManager.syncVehicleDeployScreen(commander, factionId);
        return null;
    }

    /**
     * 为本局安排首批载具。每个 {@code entity} 配置槽位安排一辆，同一回合重复
     * 调用不会重复安排。载具达到类型配置的首次部署时间后，才会进入有界异步
     * 区块加载队列，避免为较长延迟长期强加载出生区块。
     *
     * @return 本次新安排的槽位数量
     */
    public int prepareInitialVehicles(String factionId, String team, ServerLevel level) {
        return prepareInitialVehicles(factionId, team, level, System.currentTimeMillis());
    }

    public int prepareInitialVehicles(String factionId, String team, ServerLevel level,
                                      long deploymentStartedAtEpochMs) {
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
            long readyAtEpochMs = computeInitialReadyAt(
                deploymentStartedAtEpochMs, cfg.initialDeployDelaySeconds(normalizedTeam));

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
                    key, cfg, slot, deployment, spawnPos.immutable(), readyAtEpochMs);
                delayedInitialVehicles.add(pending);
            }
        }

        processInitialVehicleDeployments();
        return scheduled;
    }

    /**
     * 布防阶段入口：启用自动首发计时。零延迟载具立即进入异步区块加载队列，
     * 其余载具在各自到期后再加载并生成。该操作幂等。
     *
     * @return 本次调用中立即生成的载具数量
     */
    public int activateInitialVehicleDeployment() {
        int before = initialVehiclesSpawned;
        initialDeploymentActive = true;
        // 方案 C：开局计时不立即开始，而是等所有已选队玩家进入战场后才开始
        // 5 秒倒计时，给客户端留出战场区块加载窗口（见 drainInitialSpawnQueue）。
        initialSpawnArming = true;
        if (initialDeploymentLevel != null) {
            initialSpawnArmStartedTick = initialDeploymentLevel.getGameTime();
        }
        nextInitialSpawnTick = -1;
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

        if (initialDeploymentActive) {
            long now = System.currentTimeMillis();
            while (!delayedInitialVehicles.isEmpty()
                && delayedInitialVehicles.peek().readyAtEpochMs() <= now) {
                stageDueInitialVehicle(delayedInitialVehicles.poll());
            }
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
        // 等玩家进入战场后再放置部署点弹药箱/主重生点补给站（与载具同条件）。
        processDeferredSupplyPlacement(level);
        // 每秒从刷新队列弹出一辆生成（开局 5 秒后开始）。
        drainInitialSpawnQueue(level);
        logInitialDeploymentCompletionIfReady();
    }

    public InitialDeploymentStatus getInitialDeploymentStatus() {
        int pending = delayedInitialVehicles.size()
            + initialVehiclesByChunk.values().stream().mapToInt(List::size).sum()
            + initialSpawnQueue.size();
        return new InitialDeploymentStatus(
            initialVehiclesPlanned, initialVehiclesSpawned, initialVehiclesFailed, pending);
    }

    public boolean isInitialVehicleDeploymentSettled() {
        return initialDeploymentActive
            && delayedInitialVehicles.isEmpty()
            && pendingInitialChunks.isEmpty()
            && initialChunksInFlight == 0
            && initialVehiclesByChunk.isEmpty()
            && initialSpawnQueue.isEmpty();
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

        // 同一出生区块的多辆载具随机打乱后排入刷新队列，由调度器每秒生成一辆，
        // 避免开局瞬间批量生成导致客户端区块未加载而丢失实体。
        Collections.shuffle(vehicles);
        initialSpawnQueue.addAll(vehicles);
        Espetro.LOGGER.info(
            "初始载具出生区块已就绪: chunk={}, 入队{}辆, 队列{}辆",
            chunk, vehicles.size(), initialSpawnQueue.size());
        releaseInitialChunkTicket(level, chunk);
    }

    /**
     * 战场地图就绪后调用：部署点弹药箱与主重生点补给站延迟到玩家进入战场后再放置，
     * 与初始载具刷新队列同条件（避免生成时客户端区块未加载导致实体丢失）。
     */
    public void scheduleDeferredSupplyPlacement() {
        deferredSupplyPlacementPending = true;
    }

    /**
     * 每 tick 由 {@link #processInitialVehicleDeployments()} 调用：
     * 满足「所有已选队玩家进入战场（或超时兜底）」后执行一次延迟补给站放置。
     */
    private void processDeferredSupplyPlacement(ServerLevel level) {
        if (!deferredSupplyPlacementPending || !initialDeploymentActive) {
            return;
        }
        long tick = level.getGameTime();
        if (initialSpawnArming && !allAssignedPlayersInBattlefield(level)) {
            long waited = tick - initialSpawnArmStartedTick;
            if (waited < INITIAL_SPAWN_ARM_TIMEOUT_TICKS) {
                return; // 还有已选队玩家未进入战场，继续等待
            }
            Espetro.LOGGER.warn(
                "等待玩家进入战场超时({}s)，强制放置部署点弹药箱/补给站",
                waited / 20L);
        }
        deferredSupplyPlacementPending = false;
        try {
            int deployStations = org.espetro.logistics.DeploySupplyStationPlacer
                .placeAtSpawnPoints(level);
            Espetro.LOGGER.info("原部署点无限弹药箱: {} 个", deployStations);
        } catch (Exception e) {
            Espetro.LOGGER.error("预放原部署点无限弹药箱失败", e);
        }
        try {
            int mainBaseStations = spawnMainBaseSupplyStations(level);
            Espetro.LOGGER.info("主重生点弹药补给站: {} 个", mainBaseStations);
        } catch (Exception e) {
            Espetro.LOGGER.error("生成主重生点弹药补给站失败", e);
        }
    }

    /**
     * 玩家部署完成（选点落地/复活）后调用：向该玩家重发所有活跃载具与补给站的
     * spawn 包 + 绝对位置 + 全量数据，确保客户端与服务端同步。
     * 客户端对「已存在实体」会替换、对「未注册实体」会补加，因此幂等安全。
     */
    public void resyncVehiclesForPlayer(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        ResourceKey<Level> dimension = level.dimension();
        int sent = 0;
        // 活跃载具
        for (UUID id : new ArrayList<>(activeVehicleIds)) {
            Entity entity = level.getEntity(id);
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            ActiveVehicleData data = activeVehicleData.get(id);
            if (data != null && !data.dimension().equals(dimension)) {
                continue; // 只同步本维度实体
            }
            sendFullEntityResync(player, entity);
            sent++;
        }
        // 补给站实体（载具坑/主重生点预放的 Dragonrise 弹药补给站）
        for (UUID id : new ArrayList<>(mappedSupplyStations.keySet())) {
            Entity entity = level.getEntity(id);
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            sendFullEntityResync(player, entity);
            sent++;
        }
        Espetro.LOGGER.info("部署完成载具双端同步: {} 向 {} 重发 {} 个实体",
            dimension.location(), player.getName().getString(), sent);
    }

    /** 向单个玩家重发实体 spawn 包 + teleport + 实体数据（全量权威状态）。 */
    private static void sendFullEntityResync(ServerPlayer player, Entity entity) {
        if (player == null || player.connection == null || entity == null) {
            return;
        }
        player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(entity));
        player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(entity));
        java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> data =
            entity.getEntityData().getNonDefaultValues();
        player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(
                entity.getId(), data == null ? java.util.List.of() : data));
    }

    /**
     * 每 tick 由 {@link #processInitialVehicleDeployments()} 调用：
     * 等所有已选队玩家进入战场后，5 秒开始，每秒从刷新队列弹出一辆生成。
     */
    private void drainInitialSpawnQueue(ServerLevel level) {
        if (!initialDeploymentActive || initialSpawnQueue.isEmpty()) {
            return;
        }
        long tick = level.getGameTime();
        if (nextInitialSpawnTick < 0) {
            // 方案 C：开局计时从「所有已选队玩家进入战场」之后才开始。
            // 玩家进入战场（服务端视角的维度切换）通常早于其客户端区块加载完成，
            // 以此为起点再等 5 秒，可显著降低生成时客户端区块未就绪的概率。
            if (initialSpawnArming && !allAssignedPlayersInBattlefield(level)) {
                long waited = tick - initialSpawnArmStartedTick;
                if (waited < INITIAL_SPAWN_ARM_TIMEOUT_TICKS) {
                    return; // 还有已选队玩家未进入战场，继续等待
                }
                // 超时兜底：个别玩家长期不进入战场时强制启动，避免队列卡死。
                Espetro.LOGGER.warn(
                    "等待玩家进入战场超时({}s)，强制启动初始载具刷新队列",
                    waited / 20L);
            }
            initialSpawnArming = false;
            nextInitialSpawnTick = tick + INITIAL_SPAWN_FIRST_DELAY_TICKS;
            Espetro.LOGGER.info(
                "初始载具刷新队列启动: 5秒后开始每秒一辆 (tick={}, 队列{}辆)",
                tick, initialSpawnQueue.size());
        }
        if (tick < nextInitialSpawnTick) {
            return;
        }
        PendingInitialVehicle pending = initialSpawnQueue.pollFirst();
        if (pending == null) {
            return;
        }
        spawnSingleInitialVehicle(level, pending);
        // 成功生成后按每秒一辆推进；失败不推进，下 tick 继续尝试下一辆。
        nextInitialSpawnTick = tick + INITIAL_SPAWN_INTERVAL_TICKS;
    }

    /**
     * 检查所有已选队（ATTACK/DEFEND）的在线玩家是否都已进入战场维度。
     * 未选边的旁观/等待玩家不阻塞队列。
     */
    private boolean allAssignedPlayersInBattlefield(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return true;
        }
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> battlefieldDim =
            level.dimension();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String team = ClassCountManager.getInstance().getPlayerTeam(player.getUUID());
            if (team == null) {
                team = Espetro.getPlayerTeam(player);
            }
            if (!"ATTACK".equals(team) && !"DEFEND".equals(team)) {
                continue; // 未选边玩家不阻塞
            }
            if (player.serverLevel() == null
                || player.serverLevel().dimension() != battlefieldDim) {
                return false;
            }
        }
        return true;
    }

    private void spawnSingleInitialVehicle(ServerLevel level, PendingInitialVehicle pending) {
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
            return;
        }
        if (!level.addFreshEntity(vehicleEntity)) {
            vehicleEntity.discard();
            initialVehiclesFailed++;
            Espetro.LOGGER.warn(
                "初始载具预部署失败: {} / {} 槽位{}未能加入战场",
                key.factionId(), key.vehicleType(), key.slotIndex());
            return;
        }
        trackVehicle(
            vehicleEntity,
            key.factionId(),
            key.vehicleType(),
            key.slotIndex(),
            key.team(),
            true);
        // 首发不进入刷新冷却；载具被摧毁后才开始自动刷新计时。
        Map<String, Long> initialCooldown = cooldowns.get(
            cooldownOwner(key.team(), key.factionId()));
        if (initialCooldown != null) {
            initialCooldown.remove(key.vehicleType());
        }
        ActiveVehicleData tracked = activeVehicleData.get(vehicleEntity.getUUID());
        if (tracked != null) {
            syncCommanderVehiclePanel(tracked);
        }
        initialVehiclesSpawned++;
        broadcastVehicleInfoToTeam(key.team());
        // 方案 B：稍后重发 spawn 包，兜底客户端区块未就绪导致的丢失
        scheduleSpawnResend(vehicleEntity);
        Espetro.LOGGER.info(
            "初始载具已刷新: {} / {} 槽位{} (队列剩余{})",
            key.factionId(), key.vehicleType(), key.slotIndex(), initialSpawnQueue.size());
    }

    private boolean hasPendingInitialVehicle(String team, String factionId, String vehicleType) {
        String normalizedTeam = team == null ? "" : team.trim().toUpperCase(Locale.ROOT);
        for (PendingInitialVehicle pending : delayedInitialVehicles) {
            if (matchesInitialType(pending, normalizedTeam, factionId, vehicleType)) return true;
        }
        for (List<PendingInitialVehicle> pendingInChunk : initialVehiclesByChunk.values()) {
            for (PendingInitialVehicle pending : pendingInChunk) {
                if (matchesInitialType(pending, normalizedTeam, factionId, vehicleType)) return true;
            }
        }
        for (PendingInitialVehicle pending : initialSpawnQueue) {
            if (matchesInitialType(pending, normalizedTeam, factionId, vehicleType)) return true;
        }
        return false;
    }

    private static boolean matchesInitialType(PendingInitialVehicle pending, String team,
                                              String factionId, String vehicleType) {
        InitialVehicleDeploymentLedger.SlotKey key = pending.key();
        return key.team().equals(team)
            && key.factionId().equals(factionId)
            && key.vehicleType().equals(vehicleType);
    }

    private void stageDueInitialVehicle(PendingInitialVehicle pending) {
        ChunkPos chunk = new ChunkPos(pending.spawnPosition());
        List<PendingInitialVehicle> chunkVehicles = initialVehiclesByChunk.get(chunk);
        if (chunkVehicles == null) {
            chunkVehicles = new ArrayList<>();
            initialVehiclesByChunk.put(chunk, chunkVehicles);
            pendingInitialChunks.add(chunk);
        }
        chunkVehicles.add(pending);
    }

    static long computeInitialReadyAt(long deploymentStartedAtEpochMs, int delaySeconds) {
        long safeStart = Math.max(0L, deploymentStartedAtEpochMs);
        long delayMillis = Math.max(0L, (long) delaySeconds) * 1000L;
        return delayMillis > Long.MAX_VALUE - safeStart
            ? Long.MAX_VALUE : safeStart + delayMillis;
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
            scheduleAutoRespawn(data.team(), data.factionId(), data.vehicleType());
            broadcastVehicleInfoToTeam(data.team());
        }
    }

    /**
     * 移除未按死亡处理的载具追踪，例如卸载或重置。
     */
    public void onVehicleRemoved(UUID entityId) {
        ActiveVehicleData data = removeTrackedVehicle(entityId);
        if (data != null) broadcastVehicleInfoToTeam(data.team());
    }

    private static void syncCommanderVehiclePanel(ActiveVehicleData data) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || data == null) return;
        UUID commanderId = "ATTACK".equals(data.team())
            ? org.espetro.team.VoteManager.getInstance().getAttackCommander()
            : org.espetro.team.VoteManager.getInstance().getDefendCommander();
        ServerPlayer commander = commanderId == null ? null
            : server.getPlayerList().getPlayer(commanderId);
        if (commander != null) {
            org.espetro.network.NetworkManager
                .syncVehicleDeployScreen(commander, data.factionId());
        }
    }

    @Nullable
    private ActiveVehicleData removeTrackedVehicle(UUID entityId) {
        activeVehicleIds.remove(entityId);
        vehicleSupplies.remove(entityId);
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
        VehicleConfig.VehicleTypeConfig vcfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        // 客户端 F 轮盘在 sync 前据此显示建材槽
        if (vcfg != null && vcfg.supplyVeh) {
            vehicle.addTag("espetro_supply_veh");
        }
        if (vcfg != null && vcfg.fightVeh) {
            vehicle.addTag("espetro_fight_veh");
        }
        getList(factionId, vehicleType).add(vehicleId);
        activeVehicleIds.add(vehicleId);
        activeVehicleData.put(vehicleId, new ActiveVehicleData(
            factionId, vehicleType, slotIndex, normalizedTeam, initial,
            vehicle.level().dimension(), vehicle.blockPosition().immutable()));
        // 初始化载具补给库存；所有新生成的载具都按类型自动装填
        if (vcfg != null && vcfg.supplyCapacity > 0) {
            VehicleSupplyState supply = new VehicleSupplyState(vcfg.supplyCapacity, vcfg.canCarryConstruction());
            if (vcfg.supplyVeh) {
                supply.fillHalf();
            } else {
                supply.fillAmmo();
            }
            vehicleSupplies.put(vehicleId, supply);
        }
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

        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(data.factionId(), data.vehicleType());
        int penalty = cfg != null ? Math.max(0, cfg.troopValue) : 0;
        if (penalty <= 0) {
            return;
        }

        TroopCountManager troopManager = TroopCountManager.getInstance();
        String displayName = getDisplayName(data.factionId(), data.vehicleType());
        if ("ATTACK".equals(data.team())) {
            troopManager.modifyAttackTroops(-penalty);
            Espetro.broadcastToTeam(data.team(), "§c☠ 攻方载具 [" + displayName + "] 被摧毁！- " + penalty + " 兵力");
            Espetro.LOGGER.info("攻方载具 {} 被摧毁，扣除 {} 兵力，剩余: {}", displayName, penalty, troopManager.getAttackTroops());
        } else if ("DEFEND".equals(data.team())) {
            troopManager.modifyDefendTroops(-penalty);
            Espetro.broadcastToTeam(data.team(), "§9☠ 守方载具 [" + displayName + "] 被摧毁！- " + penalty + " 兵力");
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

    // --- Vehicle Supply Accessors ---

    /** 获取载具的补给状态（可能为 null，表示该载具无补给系统） */
    @Nullable
    public VehicleSupplyState getVehicleSupply(UUID entityId) {
        return vehicleSupplies.get(entityId);
    }

    /** 获取载具的补给状态，若不存在则创建（用于首次访问时） */
    @Nullable
    public VehicleSupplyState getOrCreateVehicleSupply(UUID entityId, String factionId, String vehicleType) {
        VehicleSupplyState existing = vehicleSupplies.get(entityId);
        if (existing != null) return existing;
        VehicleConfig.VehicleTypeConfig vcfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (vcfg == null || vcfg.supplyCapacity <= 0) return null;
        VehicleSupplyState supply = new VehicleSupplyState(vcfg.supplyCapacity, vcfg.canCarryConstruction());
        if (vcfg.supplyVeh) {
            supply.fillHalf();
        } else {
            supply.fillAmmo();
        }
        vehicleSupplies.put(entityId, supply);
        return supply;
    }

    /** 在FOB/基地向载具装载弹药，返回实际装载量 */
    public int loadAmmoToVehicle(UUID entityId, int amount) {
        VehicleSupplyState supply = vehicleSupplies.get(entityId);
        return supply != null ? supply.addAmmo(amount) : 0;
    }

    /** 从载具卸载弹药，返回实际卸载量 */
    public int unloadAmmoFromVehicle(UUID entityId, int amount) {
        VehicleSupplyState supply = vehicleSupplies.get(entityId);
        return supply != null ? supply.removeAmmo(amount) : 0;
    }

    /** 向载具装载建材，返回实际装载量 */
    public int loadConstructionToVehicle(UUID entityId, int amount) {
        VehicleSupplyState supply = vehicleSupplies.get(entityId);
        return supply != null ? supply.addConstruction(amount) : 0;
    }

    /** 从载具卸载建材，返回实际卸载量 */
    public int unloadConstructionFromVehicle(UUID entityId, int amount) {
        VehicleSupplyState supply = vehicleSupplies.get(entityId);
        return supply != null ? supply.removeConstruction(amount) : 0;
    }

    /** 载具弹药是否足够支付指定量 */
    public boolean canVehicleAffordAmmo(UUID entityId, int amount) {
        VehicleSupplyState supply = vehicleSupplies.get(entityId);
        return supply != null && supply.canAffordAmmo(amount);
    }

    /** 从载具扣除弹药，返回是否成功 */
    public boolean consumeVehicleAmmo(UUID entityId, int amount) {
        VehicleSupplyState supply = vehicleSupplies.get(entityId);
        if (supply == null || !supply.canAffordAmmo(amount)) return false;
        supply.removeAmmo(amount);
        return true;
    }

    /** 载具是否为补给或战斗载具（有补给系统） */
    public boolean isVehicleSupplyCapable(UUID entityId) {
        return vehicleSupplies.containsKey(entityId);
    }

    /** 获取载具所属 factionId（从 ActiveVehicleData 中查找） */
    @Nullable
    public String getVehicleFactionId(UUID entityId) {
        ActiveVehicleData data = activeVehicleData.get(entityId);
        return data != null ? data.factionId() : null;
    }

    /** 获取载具 vehicleType */
    @Nullable
    public String getVehicleType(UUID entityId) {
        ActiveVehicleData data = activeVehicleData.get(entityId);
        return data != null ? data.vehicleType() : null;
    }

    /** 获取载具所属队伍 */
    @Nullable
    public String getVehicleTeam(UUID entityId) {
        ActiveVehicleData data = activeVehicleData.get(entityId);
        return data != null ? data.team() : null;
    }

    /** 获取载具最后已知位置 */
    @Nullable
    public BlockPos getVehicleLastPosition(UUID entityId) {
        ActiveVehicleData data = activeVehicleData.get(entityId);
        return data != null ? data.lastKnownPosition() : null;
    }

    /** 载具补给交互距离（格）。 */
    public static final double SUPPLY_INTERACT_RANGE = 5.0;

    /**
     * 玩家必须正对指定的、当前已加载的己方载具；绝不使用旧坐标回退。
     */
    public boolean canPlayerInteractWithVehicle(ServerPlayer player, UUID vehicleId) {
        if (player == null || vehicleId == null) {
            return false;
        }
        ActiveVehicleData data = activeVehicleData.get(vehicleId);
        if (data == null) {
            return false;
        }
        String playerTeam = Espetro.getPlayerTeam(player);
        if (playerTeam == null || data.team() == null
            || !playerTeam.equalsIgnoreCase(data.team())) {
            return false;
        }
        Entity target = player.serverLevel().getEntity(vehicleId);
        if (target == null || target.isRemoved()) return false;
        double range = SUPPLY_INTERACT_RANGE;
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
            player, eye, end,
            player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0D),
            candidate -> candidate.isPickable()
                && (vehicleId.equals(candidate.getUUID())
                    || vehicleId.equals(candidate.getRootVehicle().getUUID())),
            range * range);
        return hit != null && (vehicleId.equals(hit.getEntity().getUUID())
            || vehicleId.equals(hit.getEntity().getRootVehicle().getUUID()));
    }

    /**
     * 最终提交换装时使用的权威校验。除阵营、加载状态、五格视线外，目标还必须
     * 仍是当前编制声明的战斗/补给载具，防止客户端伪造或使用过期轮盘状态。
     */
    public boolean canPlayerChangeClassAtVehicle(ServerPlayer player, UUID vehicleId) {
        if (!canPlayerInteractWithVehicle(player, vehicleId)) return false;
        ActiveVehicleData data = activeVehicleData.get(vehicleId);
        if (data == null) return false;
        VehicleConfig.VehicleTypeConfig config =
            VehicleConfig.getVehicleConfig(data.factionId(), data.vehicleType());
        return config != null && config.canChangeClass();
    }

    @Nullable
    public Entity getLoadedVehicle(ServerPlayer player, UUID vehicleId) {
        if (player == null || vehicleId == null) return null;
        Entity entity = player.serverLevel().getEntity(vehicleId);
        return entity != null && !entity.isRemoved() ? entity : null;
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
        vehicleSupplies.clear();
        cooldowns.clear();
        autoRespawnQueue.clear();
        spawnResendPlans.clear();
        if (!mappedSupplyStations.isEmpty()) {
            mappedSupplyStations.clear();
            EspetroAPI.markTacticalMapStateDirty();
        }
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
        delayedInitialVehicles.clear();
        initialVehiclesByChunk.clear();
        pendingInitialChunks.clear();
        readyInitialChunks.clear();
        ticketedInitialChunks.clear();
        initialSpawnQueue.clear();
        nextInitialSpawnTick = -1;
        initialSpawnArming = false;
        initialSpawnArmStartedTick = -1;
        deferredSupplyPlacementPending = false;
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
        SupplyStationSnapshot snapshot = new SupplyStationSnapshot(
            entity.getUUID(),
            name,
            team,
            entity.level().dimension().location().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ());
        if (!snapshot.equals(mappedSupplyStations.get(entity.getUUID()))) {
            mappedSupplyStations.put(entity.getUUID(), snapshot);
            EspetroAPI.markTacticalMapStateDirty();
        }
    }

    /** Register a block-backed or entity-backed station without scanning the world. */
    public void registerMappedSupplyStation(UUID id, String name, String team,
                                            String dimension, BlockPos pos) {
        if (id == null || team == null || team.isBlank() || dimension == null || pos == null) {
            return;
        }
        SupplyStationSnapshot snapshot = new SupplyStationSnapshot(
            id,
            name == null || name.isBlank() ? SUPPLY_STATION_DISPLAY_NAME : name,
            team.trim().toUpperCase(Locale.ROOT), dimension,
            pos.getX(), pos.getY(), pos.getZ());
        if (!snapshot.equals(mappedSupplyStations.get(id))) {
            mappedSupplyStations.put(id, snapshot);
            EspetroAPI.markTacticalMapStateDirty();
        }
    }

    public void unregisterMappedSupplyStation(UUID entityId) {
        if (entityId != null && mappedSupplyStations.remove(entityId) != null) {
            EspetroAPI.markTacticalMapStateDirty();
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

    private int findAvailableSlot(String team, String factionId, String vehicleType,
                                  VehicleConfig.VehicleTypeConfig cfg) {
        int slotCount = cfg.slots.isEmpty() ? 1 : cfg.slots.size();
        for (int i = 0; i < slotCount; i++) {
            int count = 0;
            for (ActiveVehicleData data : activeVehicleData.values()) {
                if (factionId.equals(data.factionId())
                    && vehicleType.equals(data.vehicleType())
                    && team.equalsIgnoreCase(data.team())
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
        boolean mappedStationsChanged = false;
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
            mappedStationsChanged |= mappedSupplyStations.remove(snapshot.id()) != null;
        }
        if (mappedStationsChanged) {
            EspetroAPI.markTacticalMapStateDirty();
        }
        return removed;
    }

    /**
     * 开局时在双方主重生点旁各生成一个 Dragonrise 弹药补给站实体。
     * 幂等：先生成前清除本维度旧的主重生点补给站。
     */
    public int spawnMainBaseSupplyStations(ServerLevel level) {
        if (level == null) return 0;
        clearMainBaseSupplyStations(level);

        EntityType<?> stationType = BuiltInRegistries.ENTITY_TYPE.getOptional(SUPPLY_STATION_ID).orElse(null);
        if (stationType == null) {
            Espetro.LOGGER.warn("未注册实体 {}，跳过主重生点补给站生成", SUPPLY_STATION_ID);
            return 0;
        }

        int spawned = 0;
        for (String team : new String[]{"ATTACK", "DEFEND"}) {
            SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
            if (spawn == null) continue;
            if (spawnOneMainBaseSupply(level, stationType, spawn, team)) {
                spawned++;
            }
        }
        Espetro.LOGGER.info("主重生点弹药补给站生成完成: {} 个 (维度 {})",
            spawned, level.dimension().location());
        return spawned;
    }

    /**
     * 移除本维度由本类生成的主重生点补给站实体。
     */
    public int clearMainBaseSupplyStations(@Nullable ServerLevel level) {
        if (level == null) return 0;
        String dimension = level.dimension().location().toString();
        int removed = 0;
        boolean mappedStationsChanged = false;
        for (SupplyStationSnapshot snapshot
                : new ArrayList<>(mappedSupplyStations.values())) {
            if (!dimension.equals(snapshot.dimension())) {
                continue;
            }
            Entity entity = level.getEntity(snapshot.id());
            if (entity != null && !entity.isRemoved()
                && entity.getTags().contains(MAIN_BASE_SUPPLY_TAG)) {
                entity.discard();
                removed++;
                mappedStationsChanged |= mappedSupplyStations.remove(snapshot.id()) != null;
            }
        }
        if (mappedStationsChanged) {
            EspetroAPI.markTacticalMapStateDirty();
        }
        return removed;
    }

    private boolean spawnOneMainBaseSupply(ServerLevel level, EntityType<?> stationType,
                                           SpawnPointConfig.SpawnPoint spawn, String team) {
        BlockPos stationPos = getMainBaseSupplyPosition(spawn);
        if (!level.hasChunkAt(stationPos)) {
            Espetro.LOGGER.warn("主重生点补给站区块尚未预载，跳过 {} ({})",
                stationPos, team);
            return false;
        }

        Entity entity = stationType.create(level);
        if (entity == null) {
            Espetro.LOGGER.warn("无法创建主重生点补给站实体 at {} ({})", stationPos, team);
            return false;
        }

        double x = stationPos.getX() + 0.5;
        double y = stationPos.getY();
        double z = stationPos.getZ() + 0.5;
        entity.setPos(x, y, z);
        entity.setYRot(spawn.yaw);
        entity.setYHeadRot(spawn.yaw);
        entity.setCustomName(Component.literal(SUPPLY_STATION_DISPLAY_NAME));
        entity.setCustomNameVisible(false);
        entity.addTag(MAIN_BASE_SUPPLY_TAG);
        entity.addTag(MAIN_BASE_SUPPLY_TAG + "_team_" + team);
        applySupplyStationMapTags(entity, team, "main_base_" + team);

        // Full FE if the station exposes energy — never Entity#load partial NBT.
        fillVehicleEnergy(entity, Integer.MAX_VALUE);

        if (!level.addFreshEntity(entity)) {
            entity.discard();
            Espetro.LOGGER.warn("主重生点补给站未能加入世界 at {} ({})", stationPos, team);
            return false;
        }
        // 方案 B：稍后重发 spawn 包，兜底客户端区块未就绪导致的丢失
        scheduleSpawnResend(entity);
        return true;
    }

    /**
     * 主重生点朝向右侧 3 格，Y 与重生点同高。
     */
    public static BlockPos getMainBaseSupplyPosition(SpawnPointConfig.SpawnPoint spawn) {
        float yawRad = spawn.yaw * ((float) Math.PI / 180f);
        // Minecraft yaw: 0 = +Z，90 = -X。右侧 = (-cos, -sin)
        double rightX = -Mth.cos(yawRad);
        double rightZ = -Mth.sin(yawRad);
        int x = Mth.floor(spawn.x + rightX * MAIN_BASE_SUPPLY_SIDE_OFFSET);
        int y = Mth.floor(spawn.y);
        int z = Mth.floor(spawn.z + rightZ * MAIN_BASE_SUPPLY_SIDE_OFFSET);
        return new BlockPos(x, y, z);
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
        // 方案 B：稍后重发 spawn 包，兜底客户端区块未就绪导致的丢失
        scheduleSpawnResend(entity);
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
