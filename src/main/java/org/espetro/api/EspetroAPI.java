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
import net.minecraft.server.MinecraftServer;

/**
 * Espetro 对外提供的 API
 * 供其他模组查询玩家的阵营、小队、指挥官信息
 */
public class EspetroAPI {

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

    public static Optional<ActiveBattlefieldSnapshot> getActiveBattlefieldSnapshot() {
        return BattlefieldContext.get().map(EspetroAPI::toPublicSnapshot);
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
        return BastionManager.getInstance().getAllBastions().stream()
            .filter(BastionData::isActive)
            .map(EspetroAPI::toFobSnapshot)
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
        return TeamPackManager.getInstance().getRallySnapshots();
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
            map.esPoints.backgroundBytes()
        );
    }

    private static String getPlayerTeamById(UUID playerId) {
        var server = Espetro.getServer();
        if (server == null) return null;
        var player = server.getPlayerList().getPlayer(playerId);
        return player != null ? Espetro.getPlayerTeam(player) : null;
    }
}
