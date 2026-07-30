package org.espetro.api;

import net.minecraft.server.level.ServerPlayer;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.Espetro;
import org.espetro.team.CommanderSkillManager;
import org.espetro.team.ClassCountManager;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;
import org.espetro.team.TeamPackManager;
import org.espetro.logistics.LogisticsConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.espetro.mapconfig.ActiveMapConfig;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.stats.PlayerMatchStatsManager;
import org.espetro.team.GameStateManager;
import org.espetro.vehicle.VehicleManager;
import org.espetro.team.SpawnPointConfig;
import net.minecraft.server.MinecraftServer;

/**
 * Espetro 对外提供的 API
 * 供其他模组查询玩家的阵营、小队、指挥官信息
 */
public class EspetroAPI {
    private static ActiveMapConfig cachedActiveMap;
    private static ActiveBattlefieldSnapshot cachedBattlefieldSnapshot;
    private static long tacticalRevision;
    private static long tacticalSnapshotSession = Long.MIN_VALUE;
    private static TacticalContent lastTacticalContent;
    private static TacticalMapStateSnapshot lastTacticalSnapshot;

    public static Optional<String> getActiveMapId() {
        return BattlefieldContext.get().map(map -> map.mapFolder);
    }

    public static Optional<String> getActiveMapName() {
        return BattlefieldContext.get().map(map -> map.displayName);
    }

    public static Optional<ResourceKey<Level>> getActiveBattlefieldDimension() {
        return BattlefieldContext.getActiveDimensionKey();
    }

    public static boolean isActiveBattlefield(ServerLevel level) {
        return BattlefieldContext.isActiveBattlefield(level);
    }

    public static Optional<ServerLevel> getActiveBattlefieldLevel(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        return BattlefieldContext.getActiveDimensionKey()
            .map(server::getLevel);
    }

    public static synchronized Optional<ActiveBattlefieldSnapshot> getActiveBattlefieldSnapshot() {
        ActiveMapConfig active = BattlefieldContext.getOrNull();
        if (active == null) {
            cachedActiveMap = null;
            cachedBattlefieldSnapshot = null;
            return Optional.empty();
        }
        if (active != cachedActiveMap || cachedBattlefieldSnapshot == null) {
            cachedActiveMap = active;
            cachedBattlefieldSnapshot = toPublicSnapshot(active);
        }
        return Optional.of(cachedBattlefieldSnapshot);
    }

    public static boolean endRound(String winner) {
        return GameStateManager.getInstance().endRound(winner);
    }

    public static Optional<PlayerMatchStatsManager.PlayerMatchStats> getPlayerMatchStats(UUID playerId) {
        return PlayerMatchStatsManager.getInstance().get(playerId);
    }

    public static Optional<String> getSquadCategory(UUID playerId) {
        return Optional.ofNullable(SquadManager.getInstance().getPlayerCategoryId(playerId));
    }

    /**
     * 获取玩家所在阵营（ATTACK 或 DEFEND）
     */
    public static String getPlayerTeam(ServerPlayer player) {
        return Espetro.getPlayerTeam(player);
    }

    /**
     * 玩家是否已经实际部署到战场，可显示在 ESPoints 战术地图上。
     *
     * <p>未选阵营、中途加入仍在选边、死亡后等待选择部署点，以及其他仍处于
     * Espetro 统一等待态的旁观玩家都不会出现在地图上。</p>
     */
    public static boolean isPlayerVisibleOnTacticalMap(ServerPlayer player) {
        if (player == null
            || !player.isAlive()
            || player.isSpectator()
            || !BattlefieldContext.isActiveBattlefield(player.serverLevel())
            || BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            return false;
        }

        String team = ClassCountManager.getInstance().getPlayerTeam(player.getUUID());
        if (team == null) {
            team = Espetro.getPlayerTeam(player);
        }
        return "ATTACK".equals(team) || "DEFEND".equals(team);
    }

    public static String getPlayerFaction(UUID playerId) {
        return ClassCountManager.getInstance().getPlayerFaction(playerId);
    }

    public static String getPlayerClass(UUID playerId) {
        return ClassCountManager.getInstance().getPlayerClass(playerId);
    }

    public static String getPlayerClassVariant(UUID playerId) {
        return ClassCountManager.getInstance().getPlayerVariant(playerId);
    }

    public static int getClassSwitchCooldownRemaining(UUID playerId) {
        return ClassCountManager.getInstance().getClassSwitchCooldownRemaining(playerId);
    }

    public static Map<String, Integer> getClassCounts(String team, String factionId) {
        return ClassCountManager.getInstance().getCountsForFaction(team, factionId);
    }

    public static Map<String, Map<String, Integer>> getClassVariantCounts(String team, String factionId) {
        return ClassCountManager.getInstance().getVariantCountsForFaction(team, factionId);
    }

    public static boolean selectPlayerClass(ServerPlayer player, String classId, String variantId) {
        return player != null && ClassCountManager.getInstance().selectClass(player, classId, variantId);
    }

    /**
     * 获取玩家所在小队ID
     */
    public static int getPlayerSquadId(UUID playerId) {
        return SquadManager.getInstance().getPlayerSquadId(playerId);
    }

    /**
     * 检查玩家是否是小队队长
     */
    public static boolean isSquadLeader(UUID playerId) {
        String team = getPlayerTeamById(playerId);
        if (team == null) return false;

        int squadId = getPlayerSquadId(playerId);
        if (squadId == -1) return false;

        for (SquadManager.SquadSnapshot squad : SquadManager.getInstance().getSquadSnapshots(team)) {
            if (squad.id == squadId) {
                return squad.members.stream().anyMatch(m -> m.uuid.equals(playerId) && m.leader);
            }
        }
        return false;
    }

    /** 是否为所在火力组组长。 */
    public static boolean isFireteamLeader(UUID playerId) {
        return playerId != null && SquadManager.getInstance().isFireteamLeader(playerId);
    }

    /** 玩家所在火力组；无小队返回 null。 */
    public static org.espetro.team.Fireteam getPlayerFireteam(UUID playerId) {
        return playerId == null ? null : SquadManager.getInstance().getPlayerFireteam(playerId);
    }

    /**
     * 是否可发起战术/Ping 标点：指挥官、小队长、火力组组长，或合法载具座位。
     */
    public static boolean canPlacePing(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (isCommander(playerId) || isSquadLeader(playerId) || isFireteamLeader(playerId)) {
            return true;
        }
        return org.espetro.ping.VehicleSeatPingCache.canPingFromVehicle(playerId);
    }

    /**
     * 服务端权威标点权限。与 UUID 兼容入口不同，本方法会实时复核载具类型与座位，
     * ESPoints 等服务端调用方应优先使用该重载。
     */
    public static boolean canPlacePing(ServerPlayer player) {
        if (player == null || getPlayerTeam(player) == null) {
            return false;
        }
        UUID playerId = player.getUUID();
        return isCommander(playerId)
            || isSquadLeader(playerId)
            || isFireteamLeader(playerId)
            || org.espetro.ping.VehicleSeatPingCache.canPingFromVehicle(player);
    }

    /**
     * 检查玩家是否是指挥官
     */
    public static boolean isCommander(UUID playerId) {
        return VoteManager.getInstance().isCommander(playerId);
    }

    /**
     * ESPoints 在服务端将战术地图选点转换为世界坐标后调用。
     * Espetro 会重新校验指挥官权限、游戏阶段和技能冷却。
     */
    public static boolean submitArtillerySupportTarget(ServerPlayer commander, double x, double z) {
        return CommanderSkillManager.getInstance().submitArtillerySupportTarget(commander, x, z);
    }

    /**
     * ESPoints 在服务端将战术地图选点转换为世界坐标后调用。
     * 这是通用指挥官技能选点入口；旧的 submitArtillerySupportTarget 保留为兼容别名。
     */
    public static boolean submitCommanderSkillTarget(ServerPlayer commander, double x, double z) {
        return CommanderSkillManager.getInstance().submitArtillerySupportTarget(commander, x, z);
    }

    /**
     * 获取最近一次155火炮支援请求；不会从队列中移除。
     */
    public static CommanderSkillManager.ArtillerySupportRequest getLatestArtillerySupportRequest() {
        return CommanderSkillManager.getInstance().getLatestArtillerySupportRequest();
    }

    /**
     * 获取最近一次指挥官技能地图选点请求；不会从队列中移除。
     */
    public static CommanderSkillManager.ArtillerySupportRequest getLatestCommanderSkillTargetRequest() {
        return CommanderSkillManager.getInstance().getLatestCommanderSkillTargetRequest();
    }

    /**
     * 获取当前缓存的155火炮支援请求快照；不会从队列中移除。
     */
    public static List<CommanderSkillManager.ArtillerySupportRequest> getArtillerySupportRequests() {
        return CommanderSkillManager.getInstance().getArtillerySupportRequestsSnapshot();
    }

    /**
     * 获取当前缓存的指挥官技能地图选点请求快照；不会从队列中移除。
     */
    public static List<CommanderSkillManager.ArtillerySupportRequest> getCommanderSkillTargetRequests() {
        return CommanderSkillManager.getInstance().getCommanderSkillTargetRequestsSnapshot();
    }

    /**
     * 获取并清空155火炮支援请求兼容队列，可供 KubeJS 或其他服务端逻辑联动。
     */
    public static List<CommanderSkillManager.ArtillerySupportRequest> drainArtillerySupportRequests() {
        return CommanderSkillManager.getInstance().drainArtillerySupportRequests();
    }

    /**
     * 获取并清空指挥官技能地图选点请求兼容队列。
     */
    public static List<CommanderSkillManager.ArtillerySupportRequest> drainCommanderSkillTargetRequests() {
        return CommanderSkillManager.getInstance().drainCommanderSkillTargetRequests();
    }

    public static int getCommanderSkillCooldown(ServerPlayer commander, String skillId) {
        return commander == null ? 0
            : CommanderSkillManager.getInstance().getRemainingCooldownSeconds(commander.getUUID(), skillId);
    }

    public static Map<String, Integer> getCommanderSkillCooldowns(ServerPlayer commander) {
        return commander == null ? Map.of()
            : CommanderSkillManager.getInstance().getCooldownData(commander.getUUID());
    }

    public static boolean isCommanderSkillOnCooldown(ServerPlayer commander, String skillId) {
        return commander != null && CommanderSkillManager.getInstance().isOnCooldown(commander.getUUID(), skillId);
    }

    public static CommanderSkillManager.SkillStatus getCommanderSkillStatus(ServerPlayer commander, String skillId) {
        return CommanderSkillManager.getInstance().getSkillStatus(commander, skillId);
    }

    public static boolean canUseCommanderSkill(ServerPlayer commander, String skillId) {
        return getCommanderSkillStatus(commander, skillId).canUse();
    }

    public static List<FobSnapshot> getFobs() {
        return getTacticalMapStateSnapshot().structures();
    }

    private static List<FobSnapshot> collectFobs() {
        return BastionManager.getInstance().getAllBastions().stream()
            .filter(BastionData::isActive)
            .map(EspetroAPI::toFobSnapshot)
            .sorted(java.util.Comparator.comparing(snapshot -> snapshot.id().toString()))
            .toList();
    }

    private static FobSnapshot toFobSnapshot(BastionData data) {
        boolean radio = data.isRadio();
        double buildRadius = radio ? LogisticsConfig.get().radioBuildRadius : 0.0;
        double exclusionRadius = radio ? LogisticsConfig.get().radioExclusionRadius : 0.0;
        int construction = radio ? data.getConstructionSupplies() : 0;
        int ammunition = radio ? data.getAmmunitionSupplies() : 0;
        boolean habOperational = BastionManager.getInstance().isHabOperational(data);
        boolean radioCovered = !data.isHab()
            || BastionManager.getInstance().isCoveredByFriendlyRadio(data);
        return new FobSnapshot(
            data.getBastionId(),
            data.getTeam(),
            data.getName(),
            data.getLevel().dimension().location().toString(),
            data.getPosition().getX(),
            data.getPosition().getY(),
            data.getPosition().getZ(),
            construction,
            ammunition,
            data.isHabBuilt() || data.isHab(),
            data.isAmmoCrateBuilt(),
            habOperational,
            buildRadius,
            exclusionRadius,
            radioCovered,
            data.getKind().networkType()
        );
    }

    public static List<TeamPackManager.RallySnapshot> getRallies() {
        return getTacticalMapStateSnapshot().rallies().stream()
            .map(rally -> new TeamPackManager.RallySnapshot(
                rally.id(), rally.team(), rally.squadId(), rally.dimension(),
                rally.x(), rally.y(), rally.z(), rally.nextWaveAtMillis()))
            .toList();
    }

    /**
     * 返回战术地图使用的载具补给站快照，不遍历世界实体，也不会加载区块。
     */
    public static List<VehicleManager.SupplyStationSnapshot> getVehicleSupplyStations() {
        return getTacticalMapStateSnapshot().vehicleSupplyStations().stream()
            .map(station -> new VehicleManager.SupplyStationSnapshot(
                station.id(), station.name(), station.team(), station.dimension(),
                station.x(), station.y(), station.z()))
            .toList();
    }

    /**
     * Returns a reusable tactical snapshot. Repeated reads with unchanged
     * structures return the same immutable instance and revision.
     */
    public static synchronized TacticalMapStateSnapshot getTacticalMapStateSnapshot() {
        long session = BattlefieldContext.getSessionId();
        String dimension = getActiveBattlefieldDimension()
            .map(key -> key.location().toString())
            .orElse("");

        List<FobSnapshot> structures = collectFobs();
        List<TacticalMapStateSnapshot.RallySnapshot> rallies =
            TeamPackManager.getInstance().getRallySnapshots().stream()
                .map(rally -> new TacticalMapStateSnapshot.RallySnapshot(
                    rally.id(), rally.team(), rally.squadId(), rally.dimension(),
                    rally.x(), rally.y(), rally.z(), rally.nextWaveAtMillis()))
                .sorted(java.util.Comparator.comparing(snapshot -> snapshot.id().toString()))
                .toList();
        List<TacticalMapStateSnapshot.TeamBaseSnapshot> teamBases =
            SpawnPointConfig.getAllSpawnPoints().entrySet().stream()
                .map(entry -> new TacticalMapStateSnapshot.TeamBaseSnapshot(
                    entry.getKey(),
                    entry.getKey() + " Main Base",
                    dimension,
                    (int) Math.floor(entry.getValue().x),
                    (int) Math.floor(entry.getValue().y),
                    (int) Math.floor(entry.getValue().z),
                    entry.getValue().yaw))
                .sorted(java.util.Comparator.comparing(TacticalMapStateSnapshot.TeamBaseSnapshot::team))
                .toList();
        List<TacticalMapStateSnapshot.PlayerDeployPointSnapshot> deployPoints =
            BastionManager.getInstance().getPlayerDeployPointSnapshots().stream()
                .map(point -> new TacticalMapStateSnapshot.PlayerDeployPointSnapshot(
                    point.playerId(), point.dimension(), point.x(), point.y(), point.z()))
                .toList();
        List<TacticalMapStateSnapshot.VehicleSupplyStationSnapshot> stations =
            VehicleManager.getInstance().getMappedSupplyStationSnapshots().stream()
                .map(station -> new TacticalMapStateSnapshot.VehicleSupplyStationSnapshot(
                    station.id(), station.name(), station.team(), station.dimension(),
                    station.x(), station.y(), station.z()))
                .sorted(java.util.Comparator.comparing(snapshot -> snapshot.id().toString()))
                .toList();

        TacticalContent content = new TacticalContent(
            structures, rallies, teamBases, deployPoints, stations);
        if (lastTacticalSnapshot == null
            || tacticalSnapshotSession != session
            || !content.equals(lastTacticalContent)) {
            tacticalSnapshotSession = session;
            lastTacticalContent = content;
            tacticalRevision++;
            lastTacticalSnapshot = new TacticalMapStateSnapshot(
                tacticalRevision, session, structures, rallies, teamBases, deployPoints, stations);
        }
        return lastTacticalSnapshot;
    }

    private record TacticalContent(
        List<FobSnapshot> structures,
        List<TacticalMapStateSnapshot.RallySnapshot> rallies,
        List<TacticalMapStateSnapshot.TeamBaseSnapshot> teamBases,
        List<TacticalMapStateSnapshot.PlayerDeployPointSnapshot> playerDeployPoints,
        List<TacticalMapStateSnapshot.VehicleSupplyStationSnapshot> vehicleSupplyStations
    ) {
    }

    public record FobSnapshot(UUID id, String team, String name, String dimension,
                              int x, int y, int z, int construction, int ammunition,
                              boolean habBuilt, boolean ammoCrateBuilt, boolean habOperational,
                              double buildRadius, double exclusionRadius,
                              boolean radioCovered,
                              String kind) {
        /** 兼容新增覆盖字段之前、已带 kind 的直接构造调用。 */
        public FobSnapshot(UUID id, String team, String name, String dimension,
                           int x, int y, int z, int construction, int ammunition,
                           boolean habBuilt, boolean ammoCrateBuilt, boolean habOperational,
                           double buildRadius, double exclusionRadius, String kind) {
            this(id, team, name, dimension, x, y, z, construction, ammunition,
                habBuilt, ammoCrateBuilt, habOperational, buildRadius, exclusionRadius,
                true, kind);
        }

        /** 兼容旧反射调用。 */
        public FobSnapshot(UUID id, String team, String name, String dimension,
                           int x, int y, int z, int construction, int ammunition,
                           boolean habBuilt, boolean ammoCrateBuilt, boolean habOperational,
                           double buildRadius, double exclusionRadius) {
            this(id, team, name, dimension, x, y, z, construction, ammunition,
                habBuilt, ammoCrateBuilt, habOperational, buildRadius, exclusionRadius,
                true, habBuilt ? "HAB" : "RADIO");
        }

        public String type() {
            return kind == null || kind.isBlank() ? "RADIO" : kind;
        }
    }

    private static ActiveBattlefieldSnapshot toPublicSnapshot(ActiveMapConfig map) {
        return new ActiveBattlefieldSnapshot(
            map.mapFolder,
            map.displayName,
            map.dimensionKey,
            map.esPoints.tacticalMapJson,
            map.esPoints.capturePointsJson,
            map.esPoints.backgroundImage,
            map.esPoints.backgroundBytes(),
            map.esPoints.backgroundSha256,
            map.esPoints.backgroundWidth,
            map.esPoints.backgroundHeight
        );
    }

    private static String getPlayerTeamById(UUID playerId) {
        var server = Espetro.getServer();
        if (server == null) return null;
        var player = server.getPlayerList().getPlayer(playerId);
        return player != null ? Espetro.getPlayerTeam(player) : null;
    }
}
