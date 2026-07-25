package org.espetro.kubejs;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.espetro.Espetro;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.config.GameConfig;
import org.espetro.team.ClassCountManager;
import org.espetro.team.ClassEquipment;
import org.espetro.team.ClassSelectManager;
import org.espetro.team.CommanderSkillManager;
import org.espetro.team.CommanderSkillType;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.OutpostManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.SquadManager;
import org.espetro.team.TeamPackManager;
import org.espetro.team.TroopCountManager;
import org.espetro.team.VoteManager;
import org.espetro.vehicle.VehicleConfig;
import org.espetro.vehicle.VehicleManager;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.stats.PlayerMatchStatsManager;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EspetroKubeJSBindings {
    private EspetroKubeJSBindings() {
    }

    public static String modId() {
        return Espetro.MOD_ID;
    }

    @Nullable
    public static String getActiveMapId() {
        return BattlefieldContext.get().map(map -> map.mapFolder).orElse(null);
    }

    @Nullable
    public static String getActiveMapName() {
        return BattlefieldContext.get().map(map -> map.displayName).orElse(null);
    }

    @Nullable
    public static String getActiveBattlefieldDimension() {
        return BattlefieldContext.getActiveDimensionKey()
            .map(key -> key.location().toString()).orElse(null);
    }

    public static boolean endRound(String winner) {
        return GameStateManager.getInstance().endRound(winner);
    }

    @Nullable
    public static PlayerMatchStatsManager.PlayerMatchStats getPlayerMatchStats(Object playerRef) {
        UUID id = uuidOrNull(playerRef);
        return id == null ? null : PlayerMatchStatsManager.getInstance().get(id).orElse(null);
    }

    @Nullable
    public static String getSquadCategory(Object playerRef) {
        UUID id = uuidOrNull(playerRef);
        return id == null ? null : SquadManager.getInstance().getPlayerCategoryId(id);
    }

    @Nullable
    public static MinecraftServer server() {
        return Espetro.getServer();
    }

    public static Class<GameConfig> gameConfig() {
        return GameConfig.class;
    }

    public static ClassCountManager classes() {
        return ClassCountManager.getInstance();
    }

    public static FactionDataLoader factions() {
        return FactionDataProvider.getOrCreateLoader();
    }

    public static GameStateManager game() {
        return GameStateManager.getInstance();
    }

    public static ClassSelectManager factionSelection() {
        return ClassSelectManager.getInstance();
    }

    public static SquadManager squads() {
        return SquadManager.getInstance();
    }

    public static VoteManager votes() {
        return VoteManager.getInstance();
    }

    public static TroopCountManager troops() {
        return TroopCountManager.getInstance();
    }

    public static BastionManager bastions() {
        return BastionManager.getInstance();
    }

    public static TeamPackManager teamPacks() {
        return TeamPackManager.getInstance();
    }

    public static OutpostManager outposts() {
        return OutpostManager.getInstance();
    }

    public static VehicleManager vehicles() {
        return VehicleManager.getInstance();
    }

    public static CommanderSkillManager commanderSkills() {
        return CommanderSkillManager.getInstance();
    }

    public static void reloadAllConfigs() {
        Espetro.reloadAllConfigs();
    }

    public static void broadcast(String message) {
        Espetro.broadcastToAll(message);
    }

    public static void broadcastToAll(String message) {
        Espetro.broadcastToAll(message);
    }

    public static void broadcastToTeam(String team, String message) {
        Espetro.broadcastToTeam(team, message);
    }

    public static void sendToPlayer(ServerPlayer player, String message) {
        Espetro.sendToPlayer(player, message);
    }

    @Nullable
    public static ServerPlayer getPlayer(Object playerRef) {
        if (playerRef instanceof ServerPlayer player) {
            return player;
        }

        MinecraftServer server = Espetro.getServer();
        if (server == null || playerRef == null) {
            return null;
        }

        if (playerRef instanceof CharSequence nameOrUuid) {
            String value = nameOrUuid.toString();
            UUID parsedUuid = parseUuid(value);
            if (parsedUuid != null) {
                return server.getPlayerList().getPlayer(parsedUuid);
            }
            return server.getPlayerList().getPlayerByName(value);
        }

        if (playerRef instanceof Player player) {
            return server.getPlayerList().getPlayer(player.getUUID());
        }

        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    @Nullable
    public static UUID uuid(Object playerRef) {
        return uuidOrNull(playerRef);
    }

    @Nullable
    public static String getPlayerName(Object playerRef) {
        ServerPlayer player = getPlayer(playerRef);
        return player == null ? null : player.getName().getString();
    }

    @Nullable
    public static String getPlayerTeam(Object playerRef) {
        ServerPlayer player = getPlayer(playerRef);
        if (player != null) {
            return Espetro.getPlayerTeam(player);
        }

        UUID uuid = uuidOrNull(playerRef);
        if (uuid == null) {
            return null;
        }
        String team = classes().getPlayerTeam(uuid);
        return team != null ? team : classes().getEffectivePlayerTeam(uuid);
    }

    @Nullable
    public static String getPlayerFaction(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? null : classes().getPlayerFaction(uuid);
    }

    @Nullable
    public static String getPlayerClass(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? null : classes().getPlayerClass(uuid);
    }

    @Nullable
    public static String getPlayerClassVariant(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? null : classes().getPlayerVariant(uuid);
    }

    public static boolean selectPlayerClass(ServerPlayer player, String classId) {
        return classes().selectClass(player, classId);
    }

    public static boolean selectPlayerClass(ServerPlayer player, String classId, String variantId) {
        return classes().selectClass(player, classId, variantId);
    }

    public static void equipPlayer(ServerPlayer player, String classId) {
        ClassEquipment.equipPlayer(player, classId);
    }

    public static void equipPlayer(ServerPlayer player, String classId, String variantId) {
        if (player == null) return;
        String factionId = classes().getPlayerFaction(player.getUUID());
        ClassEquipment.equipPlayer(player, factionId, classId, variantId);
    }

    public static void clearEquipment(ServerPlayer player) {
        ClassEquipment.clearEquipment(player);
    }

    public static int getPlayerSquadId(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? SquadManager.NO_SQUAD : squads().getPlayerSquadId(uuid);
    }

    public static boolean isSquadLeader(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid != null && squads().isSquadLeader(uuid);
    }

    public static boolean isCommander(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid != null && votes().isCommander(uuid);
    }

    public static List<SquadManager.SquadSnapshot> getSquads(String team) {
        return squads().getSquadSnapshots(team);
    }

    public static Map<String, Integer> getClassCounts(String team, String factionId) {
        return classes().getCountsForFaction(team, factionId);
    }

    public static Map<String, Map<String, Integer>> getClassVariantCounts(String team, String factionId) {
        return classes().getVariantCountsForFaction(team, factionId);
    }

    public static FactionDataLoader.FactionData getFaction(String factionId) {
        return factions().getFaction(factionId);
    }

    public static FactionDataLoader.FactionData[] getFactions() {
        return factions().getFactionArray();
    }

    public static FactionDataLoader.ClassKitData getClassKit(String classId) {
        return factions().getClassKit(classId);
    }

    public static FactionDataLoader.ClassKitData[] getClassesForFaction(String factionId) {
        return factions().getClassesForFaction(factionId);
    }

    public static String[] getClassIdsForFaction(String factionId) {
        return factions().getClassIdsForFaction(factionId);
    }

    public static Map<String, FactionDataLoader.VehicleData> getFactionVehicles(String factionId) {
        return factions().getFactionVehicles(factionId);
    }

    public static Map<String, VehicleConfig.VehicleTypeConfig> getRuntimeVehicleConfig(String factionId) {
        return VehicleConfig.getFactionVehicles(factionId);
    }

    public static GamePhase phase() {
        return game().getCurrentPhase();
    }

    public static String phaseId() {
        return phase().name();
    }

    public static String phaseDisplayName() {
        return phase().getDisplayName();
    }

    public static void setPhase(GamePhase phase) {
        game().setPhase(phase);
    }

    public static boolean setPhase(String phaseId) {
        GamePhase parsed = parsePhase(phaseId);
        if (parsed == null) {
            return false;
        }
        game().setPhase(parsed);
        return true;
    }

    public static int getDeployTimeRemainingSeconds() {
        return game().getDeployTimeRemainingSeconds();
    }

    public static void forceStartCommanderVote() {
        game().forceStartCommanderVote();
    }

    public static void forceStartGame() {
        game().forceStartGame();
    }

    public static void resetGame() {
        game().resetGame();
    }

    @Nullable
    public static String getTeamFromFaction(String factionId) {
        return GameStateManager.getTeamFromFactionStatic(factionId);
    }

    public static int getAttackTroops() {
        return troops().getAttackTroops();
    }

    public static int getDefendTroops() {
        return troops().getDefendTroops();
    }

    public static void setAttackTroops(int value) {
        troops().setAttackTroops(value);
    }

    public static void setDefendTroops(int value) {
        troops().setDefendTroops(value);
    }

    public static void modifyAttackTroops(int delta) {
        troops().modifyAttackTroops(delta);
    }

    public static void modifyDefendTroops(int delta) {
        troops().modifyDefendTroops(delta);
    }

    public static List<BastionData> getBastions(String team) {
        return bastions().getTeamBastions(team);
    }

    public static List<BastionData> getAllBastions() {
        return bastions().getAllBastions();
    }

    public static int getBastionCooldownRemaining(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? 0 : bastions().getBastionCooldownRemaining(uuid);
    }

    public static int getResupplyCooldownRemaining(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? 0 : bastions().getResupplyCooldownRemaining(uuid);
    }

    @Nullable
    public static String tryResupply(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? "Invalid player" : bastions().tryResupply(uuid);
    }

    @Nullable
    public static String tryStartOutpostRedeploy(ServerPlayer player) {
        return outposts().tryStartRedeploy(player);
    }

    @Nullable
    public static String tryDeployOutpost(ServerPlayer player, int outpostIndex) {
        return outposts().tryDeploy(player, outpostIndex);
    }

    @Nullable
    public static String deployVehicle(ServerPlayer commander, String vehicleType) {
        return vehicles().deployVehicle(commander, vehicleType);
    }

    public static long getVehicleCooldownRemaining(String factionId, String vehicleType) {
        return vehicles().getCooldownRemaining(factionId, vehicleType);
    }

    public static int getVehicleActiveCount(String factionId, String vehicleType) {
        return vehicles().getActiveCount(factionId, vehicleType);
    }

    public static boolean activateCommanderSkill(ServerPlayer commander, String skillId) {
        return commanderSkills().activateSkill(commander, skillId);
    }

    public static boolean executeCommanderSkill(ServerPlayer commander, String skillId) {
        return activateCommanderSkill(commander, skillId);
    }

    public static boolean openCommanderTargetMap(ServerPlayer commander, String skillId) {
        return commanderSkills().beginArtilleryTargetSelection(commander, skillId);
    }

    public static boolean openArtillerySupportMap(ServerPlayer commander) {
        return commanderSkills().beginArtilleryTargetSelection(commander);
    }

    public static boolean submitArtillerySupportTarget(ServerPlayer commander, double x, double z) {
        return commanderSkills().submitArtillerySupportTarget(commander, x, z);
    }

    public static boolean submitCommanderSkillTarget(ServerPlayer commander, double x, double z) {
        return commanderSkills().submitArtillerySupportTarget(commander, x, z);
    }

    @Nullable
    public static CommanderSkillManager.ArtillerySupportRequest getLatestArtillerySupportRequest() {
        return commanderSkills().getLatestArtillerySupportRequest();
    }

    @Nullable
    public static CommanderSkillManager.ArtillerySupportRequest getLatestCommanderSkillTargetRequest() {
        return commanderSkills().getLatestCommanderSkillTargetRequest();
    }

    public static List<CommanderSkillManager.ArtillerySupportRequest> getArtillerySupportRequests() {
        return commanderSkills().getArtillerySupportRequestsSnapshot();
    }

    public static List<CommanderSkillManager.ArtillerySupportRequest> getCommanderSkillTargetRequests() {
        return commanderSkills().getCommanderSkillTargetRequestsSnapshot();
    }

    public static List<CommanderSkillManager.ArtillerySupportRequest> drainArtillerySupportRequests() {
        return commanderSkills().drainArtillerySupportRequests();
    }

    public static List<CommanderSkillManager.ArtillerySupportRequest> drainCommanderSkillTargetRequests() {
        return commanderSkills().drainCommanderSkillTargetRequests();
    }

    public static int getCommanderSkillCooldown(Object playerRef, String skillId) {
        UUID uuid = uuidOrNull(playerRef);
        if (uuid == null || skillId == null || skillId.isBlank()) {
            return 0;
        }
        return commanderSkills().getRemainingCooldownSeconds(uuid, skillId);
    }

    public static boolean isCommanderSkillOnCooldown(Object playerRef, String skillId) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid != null && skillId != null && !skillId.isBlank()
            && commanderSkills().isOnCooldown(uuid, skillId);
    }

    public static Map<String, Integer> getCommanderSkillCooldowns(Object playerRef) {
        UUID uuid = uuidOrNull(playerRef);
        return uuid == null ? Map.of() : commanderSkills().getCooldownData(uuid);
    }

    public static CommanderSkillManager.SkillStatus getCommanderSkillStatus(Object playerRef, String skillId) {
        return commanderSkills().getSkillStatus(getPlayer(playerRef), skillId);
    }

    public static boolean canUseCommanderSkill(Object playerRef, String skillId) {
        return commanderSkills().getSkillStatus(getPlayer(playerRef), skillId).canUse();
    }

    public static SpawnPointConfig.SpawnPoint getSpawnPoint(String team) {
        return SpawnPointConfig.getSpawnPoint(team);
    }

    public static Map<String, SpawnPointConfig.SpawnPoint> getAllSpawnPoints() {
        return SpawnPointConfig.getAllSpawnPoints();
    }

    @Nullable
    private static UUID uuidOrNull(Object playerRef) {
        if (playerRef == null) {
            return null;
        }
        if (playerRef instanceof UUID uuid) {
            return uuid;
        }
        if (playerRef instanceof Player player) {
            return player.getUUID();
        }
        if (playerRef instanceof CharSequence value) {
            UUID uuid = parseUuid(value.toString());
            if (uuid != null) {
                return uuid;
            }

            MinecraftServer server = Espetro.getServer();
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayerByName(value.toString());
                if (player != null) {
                    return player.getUUID();
                }
            }
        }
        return null;
    }

    @Nullable
    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private static GamePhase parsePhase(String phaseId) {
        if (phaseId == null || phaseId.isBlank()) {
            return null;
        }
        try {
            return GamePhase.valueOf(phaseId.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
