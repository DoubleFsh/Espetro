package org.espetro.team;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.espetro.Espetro;
import org.espetro.bastion.BastionManager;
import org.espetro.network.UnifiedDeployScreenPacket;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 队包（队伍集结点）管理器。
 * 使用原版信标方块作为队包载体，仅允许小队队长部署。
 */
public class TeamPackManager {

    private static final String TEAM_PACK_ITEM_TAG = "EspetroTeamPack";
    private static final Gson GSON = new Gson();
    private static TeamPackManager INSTANCE;

    private final Map<UUID, TeamPackData> teamPacks = new HashMap<>();
    private final Map<SquadKey, UUID> squadTeamPacks = new HashMap<>();
    private final Map<BlockPos, UUID> teamPackPositions = new HashMap<>();
    private final Map<SquadKey, Long> squadCooldowns = new HashMap<>();
    private final Map<UUID, Long> inheritedLeaderCooldowns = new HashMap<>();
    private final Set<UUID> pendingItemSyncs = new HashSet<>();
    private final Map<UUID, PendingRallyRespawn> pendingRespawns = new HashMap<>();
    private final Map<UUID, Long> playerDeathTimes = new HashMap<>();
    /** 玩家个人 Rally 就绪时刻（epoch ms）；冷却中不因死亡重置。 */
    private final Map<UUID, Long> personalRallyReadyAt = new HashMap<>();
    private int cooldownSeconds = 120;
    private int durability = 1;
    private float breakSpeedMultiplier = 8.0f;
    private int teammateCount = 1;
    private double teammateRadius = 8.0;
    private double enemyPlacementRadius = 50.0;
    private double enemyBurnRadius = 30.0;
    private int waveSeconds = 60;
    private int minimumRespawnSeconds = 20;
    private long tickCounter;

    private TeamPackManager() {
        INSTANCE = this;
        loadConfig();
    }

    public static TeamPackManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new TeamPackManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new TeamPackManager();
    }

    /** @deprecated 不从 datapack 加载；战场激活时 applyExternalJson。 */
    private void loadConfig() {
        // 默认字段已在声明/构造中初始化
    }

    public void reloadConfig() {
        // no-op
    }

    /** Apply the frozen team_pack.json belonging to the active map. */
    public void applyExternalJson(String rawJson) {
        cooldownSeconds = 120;
        durability = 1;
        breakSpeedMultiplier = 8.0f;
        teammateCount = 1;
        teammateRadius = 8.0;
        enemyPlacementRadius = 50.0;
        enemyBurnRadius = 30.0;
        waveSeconds = 60;
        minimumRespawnSeconds = 20;
        JsonObject json = GSON.fromJson(rawJson, JsonObject.class);
        if (json == null || !json.has("team_pack")) return;
        JsonObject teamPack = json.getAsJsonObject("team_pack");
        if (teamPack.has("cooldown_seconds")) cooldownSeconds = Math.max(0, teamPack.get("cooldown_seconds").getAsInt());
        if (teamPack.has("durability")) durability = Math.max(1, teamPack.get("durability").getAsInt());
        if (teamPack.has("break_speed_multiplier")) breakSpeedMultiplier = Math.max(1.0f, teamPack.get("break_speed_multiplier").getAsFloat());
        if (teamPack.has("teammate_count")) teammateCount = Math.max(0, teamPack.get("teammate_count").getAsInt());
        if (teamPack.has("teammate_radius")) teammateRadius = Math.max(0.0, teamPack.get("teammate_radius").getAsDouble());
        if (teamPack.has("enemy_placement_radius")) enemyPlacementRadius = Math.max(0.0, teamPack.get("enemy_placement_radius").getAsDouble());
        if (teamPack.has("enemy_burn_radius")) enemyBurnRadius = Math.max(0.0, teamPack.get("enemy_burn_radius").getAsDouble());
        if (teamPack.has("wave_seconds")) waveSeconds = Math.max(1, teamPack.get("wave_seconds").getAsInt());
        if (teamPack.has("minimum_respawn_seconds")) minimumRespawnSeconds = Math.max(0, teamPack.get("minimum_respawn_seconds").getAsInt());
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public int getDurability() {
        return durability;
    }

    public float getBreakSpeedMultiplier() {
        return breakSpeedMultiplier;
    }

    public void onServerTick() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            pendingItemSyncs.clear();
            pendingRespawns.clear();
            return;
        }

        if (!pendingItemSyncs.isEmpty()) {
            List<UUID> pendingPlayers = new ArrayList<>(pendingItemSyncs);
            pendingItemSyncs.clear();
            for (UUID playerId : pendingPlayers) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    syncTeamPackItem(player);
                }
            }
        }

        processPendingRespawns(server);
        if (tickCounter++ % 20L == 0L) {
            burnEnemyProxiedRallies();
        }
    }

    public void onPlayerDeath(UUID playerId) {
        // 仅记录死亡时刻供最短等待；不重置个人 Rally 冷却（冷却中/已就绪均保持）。
        playerDeathTimes.put(playerId, System.currentTimeMillis());
        pendingRespawns.remove(playerId);
    }


    private long computeAlignedReadyAt(UUID playerId, TeamPackData teamPack, long now) {
        // 首次进入个人冷却：死亡最短等待 + 固定 wave_seconds（不再对齐共享时钟余量）。
        long deathAt = playerDeathTimes.getOrDefault(playerId, now);
        long earliest = deathAt + minimumRespawnSeconds * 1000L;
        long ready = now + waveSeconds * 1000L;
        return Math.max(ready, earliest);
    }

    /**
     * 取消该玩家尚未完成的 Rally 波次复活队列。
     * 在改选 HAB / 原部署点 / 前哨并成功部署后必须调用，避免冷却结束后误拉回 Rally。
     * 不清除 personalRallyReadyAt（个人冷却继续走）。
     */
    public void cancelPendingRespawn(UUID playerId) {
        if (playerId == null) {
            return;
        }
        pendingRespawns.remove(playerId);
    }

    /**
     * 该玩家对指定 Rally 的个人就绪 epoch ms。
     */
    public long getPersonalRallyReadyAt(UUID playerId, TeamPackData teamPack) {
        Long existing = personalRallyReadyAt.get(playerId);
        if (existing != null) {
            // 冷却中或已就绪均保持，不因阵亡重开计数。
            return existing;
        }
        long now = System.currentTimeMillis();
        long ready = computeAlignedReadyAt(playerId, teamPack, now);
        personalRallyReadyAt.put(playerId, ready);
        return ready;
    }

    public int getWaveSeconds() {
        return waveSeconds;
    }


    public List<UnifiedDeployScreenPacket.BastionItem> getDeployItemsForPlayer(ServerPlayer player) {
        List<UnifiedDeployScreenPacket.BastionItem> result = new ArrayList<>();
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return result;
        }

        int squadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
        if (squadId == SquadManager.NO_SQUAD) {
            return result;
        }

        UUID teamPackId = squadTeamPacks.get(squadKey(team, squadId));
        if (teamPackId == null) {
            return result;
        }

        TeamPackData teamPack = getTeamPack(teamPackId);
        if (teamPack == null || !Objects.equals(teamPack.team, team)) {
            return result;
        }

        long now = System.currentTimeMillis();
        long personalReadyAt = getPersonalRallyReadyAt(player.getUUID(), teamPack);
        PendingRallyRespawn pending = pendingRespawns.get(player.getUUID());
        if (pending != null && Objects.equals(pending.teamPackId(), teamPack.teamPackId)) {
            personalReadyAt = pending.spawnAt();
        }
        long remainingSeconds = Math.max(0L, (personalReadyAt - now + 999L) / 1000L);
        String status = remainingSeconds <= 0
            ? "就绪"
            : "冷却 " + remainingSeconds + "/" + waveSeconds + "s";

        BlockPos spawnPos = teamPack.getSpawnPos();
        result.add(new UnifiedDeployScreenPacket.BastionItem(
            teamPack.teamPackId,
            "Rally " + squadId,
            spawnPos.getX() + ", " + spawnPos.getY() + ", " + spawnPos.getZ(),
            UnifiedDeployScreenPacket.BastionItem.TYPE_RALLY,
            status,
            personalReadyAt,
            waveSeconds
        ));
        return result;
    }

    public boolean respawnAtTeamPack(ServerPlayer player, UUID teamPackId) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return false;
        }

        TeamPackData teamPack = getTeamPack(teamPackId);
        if (teamPack == null || !team.equals(teamPack.team)) {
            player.sendSystemMessage(Component.literal("§c无效的队伍集结点！"));
            return false;
        }

        if (GameStateManager.getInstance().getCurrentPhase() != GamePhase.BATTLE
            && GameStateManager.getInstance().getCurrentPhase() != GamePhase.DEPLOYING) {
            player.sendSystemMessage(Component.literal("§c只能在战斗或部署阶段复活！"));
            return false;
        }

        if (!BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c你已经完成了复活选择！"));
            return false;
        }

        int squadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
        if (squadId == SquadManager.NO_SQUAD || squadId != teamPack.squadId) {
            player.sendSystemMessage(Component.literal("§c该队伍集结点不属于你当前的小队！"));
            return false;
        }

        if (isTeamPackMissing(teamPack)) {
            destroyTeamPack(teamPack, null, false, false);
            player.sendSystemMessage(Component.literal("§c该队伍集结点已失效！"));
            return false;
        }

        long now = System.currentTimeMillis();
        long readyAt = getPersonalRallyReadyAt(player.getUUID(), teamPack);
        if (readyAt <= now) {
            // 冷却已好：立即部署，并为下一次使用开启新的个人冷却。
            pendingRespawns.remove(player.getUUID());
            spawnAtRally(player, teamPack);
            scheduleNextPersonalCooldown(player.getUUID(), teamPack);
            return true;
        }

        pendingRespawns.put(player.getUUID(), new PendingRallyRespawn(teamPackId, readyAt));
        long remaining = Math.max(1L, (readyAt - now + 999L) / 1000L);
        player.sendSystemMessage(Component.literal(
            "§d已选择 Rally，冷却中 §f" + remaining + "/" + waveSeconds + " 秒§d。就绪后将自动部署。"));
        return true;
    }

    private void scheduleNextPersonalCooldown(UUID playerId, TeamPackData teamPack) {
        // 固定个人冷却：部署完成后一律 wave_seconds，不跟共享 nextWaveAt 余量对齐。
        long now = System.currentTimeMillis();
        personalRallyReadyAt.put(playerId, now + waveSeconds * 1000L);
        playerDeathTimes.remove(playerId);
    }

    private void processPendingRespawns(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (TeamPackData teamPack : teamPacks.values()) {
            while (teamPack.nextWaveAt <= now) {
                teamPack.nextWaveAt += waveSeconds * 1000L;
            }
        }
        if (pendingRespawns.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, PendingRallyRespawn>> iterator = pendingRespawns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingRallyRespawn> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            TeamPackData teamPack = getTeamPack(entry.getValue().teamPackId());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (!BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
                iterator.remove();
                continue;
            }
            if (teamPack == null || isTeamPackMissing(teamPack)) {
                iterator.remove();
                player.sendSystemMessage(Component.literal("§c所选 Rally 已失效，请重新选择部署点。"));
                org.espetro.network.NetworkManager.sendUnifiedDeployScreen(player, -1);
                continue;
            }
            if (now < entry.getValue().spawnAt()) {
                continue;
            }
            spawnAtRally(player, teamPack);
            scheduleNextPersonalCooldown(player.getUUID(), teamPack);
            iterator.remove();
        }
    }

    public boolean isTeamPackItem(ItemStack stack) {
        return stack.getItem() == Items.BEACON
            && stack.hasTag()
            && stack.getTag() != null
            && stack.getTag().getBoolean(TEAM_PACK_ITEM_TAG);
    }

    public void syncTeamPackItem(ServerPlayer player) {
        applyInheritedLeaderCooldown(
            player.getUUID(),
            Espetro.getPlayerTeam(player),
            SquadManager.getInstance().getPlayerSquadId(player.getUUID()));
        // 非小队长不允许持有 Rally 部署包；小队长手里已领取的保留。
        if (!SquadManager.getInstance().isSquadLeader(player.getUUID())) {
            removeTeamPackItems(player);
        }
    }

    public void giveTeamPackItemIfNeeded(ServerPlayer player) {
        // 兼容旧调用：交给 syncTeamPackItem 的清理策略。
        syncTeamPackItem(player);
    }

    /**
     * 径向菜单「部署 Rally」：发放 1 个 Rally 部署包（信标 + NBT），背包限 1。
     * 返回错误信息；null 表示已发放。
     */
    @Nullable
    public String giveRallyItem(ServerPlayer player) {
        if (org.espetro.team.GameStateManager.getInstance().getCurrentPhase()
            != org.espetro.team.GamePhase.BATTLE) {
            return "§c只有战斗阶段才能部署 Rally。";
        }
        if (!SquadManager.getInstance().isSquadLeader(player.getUUID())) {
            return "§c只有小队长可以部署 Rally。";
        }
        for (ItemStack stack : player.getInventory().items) {
            if (isTeamPackItem(stack)) {
                return "§e你已经携带了一个 Rally 部署包，先放置它。";
            }
        }
        if (isTeamPackItem(player.getInventory().offhand.get(0))) {
            return "§e你已经携带了一个 Rally 部署包，先放置它。";
        }
        ItemStack stack = new ItemStack(Items.BEACON);
        stack.getOrCreateTag().putBoolean(TEAM_PACK_ITEM_TAG, true);
        stack.setHoverName(net.minecraft.network.chat.Component.literal("§bRally 部署包"));
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        return null;
    }

    public void removeTeamPackItems(ServerPlayer player) {
        boolean removed = false;
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (isTeamPackItem(stack)) {
                player.getInventory().items.set(i, ItemStack.EMPTY);
                removed = true;
            }
        }
        if (isTeamPackItem(player.getInventory().offhand.get(0))) {
            player.getInventory().offhand.set(0, ItemStack.EMPTY);
            removed = true;
        }
        if (removed) {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
        }
    }

    public void reconcileTeam(@Nullable String team) {
        if (team == null) {
            return;
        }

        cleanupInvalidTeamPacks(team);

        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.equals(Espetro.getPlayerTeam(player))) {
                syncTeamPackItem(player);
            }
        }
    }

    /**
     * 仅检查能否在 pos 部署 Rally（不修改世界）。
     * 条件：战斗阶段 + 小队队长 + 小队放置冷却 + 附近队员 + 附近无敌人。
     */
    @Nullable
    public String canPlaceTeamPack(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (GameStateManager.getInstance().getCurrentPhase() != GamePhase.BATTLE) {
            return "§c只能在战斗阶段部署队包！";
        }

        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return "§c无法确定你的队伍！";
        }

        SquadManager squadManager = SquadManager.getInstance();
        int squadId = squadManager.getPlayerSquadId(player.getUUID());
        if (squadId == SquadManager.NO_SQUAD) {
            return "§c你当前不在小队中，无法部署队包！";
        }
        if (!squadManager.isSquadLeader(player.getUUID())) {
            return "§c只有小队队长才能部署队包！";
        }
        SquadKey squadKey = squadKey(team, squadId);
        if (squadKey == null) {
            return "§c无法确定你的小队！";
        }
        int cooldownRemaining = getSquadCooldownRemaining(team, squadId);
        if (cooldownRemaining > 0) {
            return "§c队包冷却中！请等待 " + cooldownRemaining + " 秒后再试。";
        }
        int nearbySquadMembers = countNearbySquadMembers(
            player, team, squadId, pos, teammateRadius);
        if (nearbySquadMembers < teammateCount) {
            return "§c部署 Rally 需要放置点 " + formatRadius(teammateRadius)
                + " 格内至少 " + teammateCount + " 名同小队队员！当前仅 "
                + nearbySquadMembers + " 名。";
        }
        if (hasEnemyNear(level, pos, team, enemyPlacementRadius)) {
            return "§c附近 " + (int) enemyPlacementRadius + " 格内有敌人，无法部署 Rally！";
        }
        return null;
    }

    @Nullable
    public String placeTeamPack(ServerPlayer player, ServerLevel level, BlockPos pos) {
        String error = canPlaceTeamPack(player, level, pos);
        if (error != null) {
            return error;
        }

        String team = Espetro.getPlayerTeam(player);
        int squadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
        SquadKey squadKey = squadKey(team, squadId);
        if (squadKey == null) {
            return "§c无法确定你的小队！";
        }

        // 如果小队已有队包，销毁旧队包后再放置新的
        UUID existingId = squadTeamPacks.get(squadKey);
        if (existingId != null) {
            TeamPackData existing = teamPacks.get(existingId);
            if (existing != null && existing.active) {
                destroyTeamPack(existing, null, true, false);
                broadcastToSquad(team, squadId, "§e[队包] 旧队包已被替换。");
            }
        }

        TeamPackData teamPack = new TeamPackData(UUID.randomUUID(), team, squadId, pos, level, durability);
        teamPacks.put(teamPack.teamPackId, teamPack);
        squadTeamPacks.put(squadKey, teamPack.teamPackId);
        teamPackPositions.put(pos.immutable(), teamPack.teamPackId);
        setSquadCooldown(squadKey);

        pendingItemSyncs.add(player.getUUID());

        player.sendSystemMessage(Component.literal(
            "§aRally 已部署！小队员个人复活冷却 " + waveSeconds + " 秒。"));
        if (cooldownSeconds > 0) {
            player.sendSystemMessage(Component.literal("§7队包放置冷却: " + cooldownSeconds + "秒"));
        }
        broadcastToSquad(team, squadId, "§d[队包] §f" + player.getName().getString() + " §d部署了队伍集结点。");
        return null;
    }

    public int getSquadCooldownRemaining(int squadId) {
        int remaining = 0;
        for (Map.Entry<SquadKey, Long> entry : squadCooldowns.entrySet()) {
            if (entry.getKey().squadId == squadId) {
                remaining = Math.max(remaining, getCooldownRemaining(entry.getValue()));
            }
        }
        return remaining;
    }

    public int getSquadCooldownRemaining(@Nullable String team, int squadId) {
        SquadKey squadKey = squadKey(team, squadId);
        if (squadKey == null) {
            return 0;
        }
        return getCooldownRemaining(squadCooldowns.get(squadKey));
    }

    public void handleSquadLeaderTransition(ServerPlayer player, @Nullable String previousTeam, int previousSquadId,
                                            boolean wasLeader, @Nullable String currentTeam, int currentSquadId,
                                            boolean isLeader) {
        if (!wasLeader && !isLeader) {
            return;
        }

        SquadKey previousKey = squadKey(previousTeam, previousSquadId);
        SquadKey currentKey = squadKey(currentTeam, currentSquadId);
        boolean changedSquad = previousKey == null || !previousKey.equals(currentKey);

        if (wasLeader && changedSquad) {
            rememberLeaderCooldown(player.getUUID(), previousKey);
        }

        if (isLeader) {
            applyInheritedLeaderCooldown(player.getUUID(), currentTeam, currentSquadId);
            syncTeamPackItem(player);
        } else if (wasLeader) {
            removeTeamPackItems(player);
        }
    }

    private void setSquadCooldown(SquadKey squadKey) {
        if (cooldownSeconds <= 0) {
            squadCooldowns.remove(squadKey);
            return;
        }
        squadCooldowns.put(squadKey, System.currentTimeMillis());
    }

    @Nullable
    public TeamPackData getTeamPack(UUID teamPackId) {
        TeamPackData teamPack = teamPacks.get(teamPackId);
        return teamPack != null && teamPack.active ? teamPack : null;
    }

    @Nullable
    public TeamPackData findByPos(BlockPos pos) {
        UUID teamPackId = teamPackPositions.get(pos);
        return teamPackId == null ? null : getTeamPack(teamPackId);
    }

    public List<RallySnapshot> getRallySnapshots() {
        long now = System.currentTimeMillis();
        return teamPacks.values().stream()
            .filter(teamPack -> teamPack.active)
            .map(teamPack -> new RallySnapshot(
                teamPack.teamPackId,
                teamPack.team,
                teamPack.squadId,
                teamPack.level.dimension().location().toString(),
                teamPack.pos.getX(),
                teamPack.pos.getY(),
                teamPack.pos.getZ(),
                Math.max(0L, (teamPack.nextWaveAt - now + 999L) / 1000L)
            ))
            .toList();
    }

    public record RallySnapshot(UUID id, String team, int squadId, String dimension,
                                int x, int y, int z, long nextWaveSeconds) {
    }

    /**
     * 取消该玩家尚未完成的 Rally 波次复活队列。
     * 在改选 HAB / 原部署点 / 前哨并成功部署后必须调用，避免冷却结束后误拉回 Rally。
     */
    private void spawnAtRally(ServerPlayer player, TeamPackData teamPack) {
        BastionManager.getInstance().clearWaiting(player.getUUID());
        BlockPos spawnPos = teamPack.getSpawnPos();
        player.teleportTo(teamPack.level,
            spawnPos.getX() + 0.5,
            spawnPos.getY() + 0.1,
            spawnPos.getZ() + 0.5,
            0f, 0f);
        player.setGameMode(GameType.SURVIVAL);
        player.removeAllEffects();
        int invincibilityTicks = org.espetro.config.GameConfig.getRespawnInvincibilityTicks();
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
            invincibilityTicks,
            127,
            false, false, false
        ));
        player.sendSystemMessage(Component.literal("§a已随 Rally 部署复活！"));
        // 战局中加入：真正落地后补职业选择。
        // 不再 sendUnifiedDeployScreen：落地后重开 J 面板会整页闪且一直挡视线；需要时按 J。
        org.espetro.team.GameStateManager.getInstance().onMidGameDeployComplete(player);
    }

    private void burnEnemyProxiedRallies() {
        for (TeamPackData teamPack : new ArrayList<>(teamPacks.values())) {
            if (teamPack.active && hasEnemyNear(
                teamPack.level, teamPack.pos, teamPack.team, enemyBurnRadius)) {
                destroyTeamPack(teamPack, null, true, true);
                broadcastToSquad(teamPack.team, teamPack.squadId,
                    "§c[Rally] 敌人进入 " + (int) enemyBurnRadius + " 格范围，Rally 已烧毁！");
            }
        }
    }

    private int countNearbySquadMembers(ServerPlayer leader, String team, int squadId,
                                        BlockPos center, double radius) {
        double radiusSquared = radius * radius;
        int count = 0;
        for (ServerPlayer player : leader.serverLevel().players()) {
            if (player == leader || !player.isAlive() || player.isSpectator()) continue;
            if (team.equals(Espetro.getPlayerTeam(player))
                && SquadManager.getInstance().getPlayerSquadId(player.getUUID()) == squadId
                && player.blockPosition().distSqr(center) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    private static String formatRadius(double radius) {
        return radius == Math.rint(radius)
            ? Integer.toString((int) radius)
            : Double.toString(radius);
    }

    private boolean hasEnemyNear(ServerLevel level, BlockPos pos, String team, double radius) {
        double radiusSquared = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            String playerTeam = Espetro.getPlayerTeam(player);
            if (playerTeam != null && !team.equals(playerTeam)
                && player.blockPosition().distSqr(pos) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    public void cleanupInvalidTeamPacks() {
        cleanupInvalidTeamPacks(null);
    }

    public void cleanupInvalidTeamPacks(@Nullable String team) {
        Iterator<TeamPackData> iterator = teamPacks.values().iterator();
        while (iterator.hasNext()) {
            TeamPackData teamPack = iterator.next();
            if (!teamPack.active) {
                iterator.remove();
                continue;
            }
            if (team != null && !team.equals(teamPack.team)) {
                continue;
            }
            if (!SquadManager.getInstance().hasSquad(teamPack.team, teamPack.squadId) || isTeamPackMissing(teamPack)) {
                teamPack.active = false;
                squadTeamPacks.remove(squadKey(teamPack.team, teamPack.squadId));
                teamPackPositions.remove(teamPack.pos);
                iterator.remove();
            }
        }
    }

    public void destroyTeamPack(TeamPackData teamPack, @Nullable ServerPlayer actor, boolean removeBlock, boolean enemyAction) {
        if (teamPack == null || !teamPack.active) {
            return;
        }

        unregisterTeamPack(teamPack);

        if (removeBlock && teamPack.level != null && teamPack.level.hasChunkAt(teamPack.pos)) {
            if (teamPack.level.getBlockState(teamPack.pos).is(Blocks.BEACON)) {
                teamPack.level.setBlock(teamPack.pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }

        if (enemyAction) {
            broadcastToSquad(teamPack.team, teamPack.squadId, "§c[队包] 队伍集结点已被敌方摧毁！");
            if (actor != null) {
                actor.sendSystemMessage(Component.literal("§a你已摧毁敌方队伍集结点！"));
            }
        } else if (actor != null) {
            broadcastToSquad(teamPack.team, teamPack.squadId, "§e[队包] 队伍集结点已失效。");
        }
    }

    public void damageTeamPack(TeamPackData teamPack, @Nullable ServerPlayer actor, int damage, boolean enemyAction) {
        if (teamPack == null || !teamPack.active) {
            return;
        }

        teamPack.health -= Math.max(1, damage);
        if (teamPack.health <= 0) {
            destroyTeamPack(teamPack, actor, true, enemyAction);
            return;
        }

        if (actor != null) {
            actor.sendSystemMessage(Component.literal("§e队包耐久: " + teamPack.health + "/" + teamPack.maxHealth));
        }
    }

    public void destroyTeamPackByExplosion(TeamPackData teamPack) {
        if (teamPack == null || !teamPack.active) {
            return;
        }

        unregisterTeamPack(teamPack);
        broadcastToSquad(teamPack.team, teamPack.squadId, "§c[队包] 队伍集结点已被爆炸摧毁！");
    }

    public void reset() {
        reset(true);
    }

    public void clearRuntimeState() {
        reset(false);
    }

    private void reset(boolean removeBlocks) {
        for (TeamPackData teamPack : new ArrayList<>(teamPacks.values())) {
            destroyTeamPack(teamPack, null, removeBlocks, false);
        }
        teamPacks.clear();
        squadTeamPacks.clear();
        teamPackPositions.clear();
        squadCooldowns.clear();
        inheritedLeaderCooldowns.clear();
        pendingItemSyncs.clear();
        pendingRespawns.clear();
        playerDeathTimes.clear();
        personalRallyReadyAt.clear();
        tickCounter = 0L;
    }

    private boolean isTeamPackMissing(TeamPackData teamPack) {
        return teamPack.level == null
            || (teamPack.level.hasChunkAt(teamPack.pos)
            && !teamPack.level.getBlockState(teamPack.pos).is(Blocks.BEACON));
    }

    private void unregisterTeamPack(TeamPackData teamPack) {
        teamPack.active = false;
        teamPacks.remove(teamPack.teamPackId);
        squadTeamPacks.remove(squadKey(teamPack.team, teamPack.squadId));
        teamPackPositions.remove(teamPack.pos);
        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (teamPack.team.equals(Espetro.getPlayerTeam(player))
                    && SquadManager.getInstance().getPlayerSquadId(player.getUUID()) == teamPack.squadId
                    && SquadManager.getInstance().isSquadLeader(player.getUUID())) {
                    pendingItemSyncs.add(player.getUUID());
                }
            }
        }
    }

    @Nullable
    private SquadKey squadKey(@Nullable String team, int squadId) {
        if (team == null || squadId == SquadManager.NO_SQUAD) {
            return null;
        }
        return new SquadKey(team, squadId);
    }

    private void rememberLeaderCooldown(UUID playerId, @Nullable SquadKey squadKey) {
        Long startedAt = getActiveCooldownStartedAt(squadKey);
        if (startedAt == null) {
            inheritedLeaderCooldowns.remove(playerId);
            return;
        }
        inheritedLeaderCooldowns.put(playerId, startedAt);
    }

    private void applyInheritedLeaderCooldown(UUID playerId, @Nullable String team, int squadId) {
        Long inheritedStartedAt = inheritedLeaderCooldowns.get(playerId);
        if (inheritedStartedAt == null) {
            return;
        }
        if (getCooldownRemaining(inheritedStartedAt) <= 0) {
            inheritedLeaderCooldowns.remove(playerId);
            return;
        }

        SquadKey squadKey = squadKey(team, squadId);
        if (squadKey == null) {
            return;
        }

        Long currentStartedAt = getActiveCooldownStartedAt(squadKey);
        if (currentStartedAt == null || inheritedStartedAt > currentStartedAt) {
            squadCooldowns.put(squadKey, inheritedStartedAt);
        }
        inheritedLeaderCooldowns.remove(playerId);
    }

    @Nullable
    private Long getActiveCooldownStartedAt(@Nullable SquadKey squadKey) {
        if (squadKey == null || cooldownSeconds <= 0) {
            return null;
        }
        Long startedAt = squadCooldowns.get(squadKey);
        if (startedAt == null) {
            return null;
        }
        if (getCooldownRemaining(startedAt) <= 0) {
            squadCooldowns.remove(squadKey);
            return null;
        }
        return startedAt;
    }

    private int getCooldownRemaining(@Nullable Long startedAt) {
        if (startedAt == null || cooldownSeconds <= 0) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        int remaining = (int) ((cooldownSeconds * 1000L - elapsed) / 1000L);
        return Math.max(0, remaining);
    }

    private void broadcastToSquad(String team, int squadId, String message) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return;
        }

        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            if (team.equals(Espetro.getPlayerTeam(onlinePlayer))
                && SquadManager.getInstance().getPlayerSquadId(onlinePlayer.getUUID()) == squadId) {
                onlinePlayer.sendSystemMessage(Component.literal(message));
            }
        }
    }

    public static class TeamPackData {
        public final UUID teamPackId;
        public final String team;
        public final int squadId;
        public final BlockPos pos;
        public final ServerLevel level;
        public final int maxHealth;
        public int health;
        public boolean active = true;
        public long nextWaveAt;

        public TeamPackData(UUID teamPackId, String team, int squadId, BlockPos pos, ServerLevel level, int maxHealth) {
            this.teamPackId = teamPackId;
            this.team = team;
            this.squadId = squadId;
            this.pos = pos.immutable();
            this.level = level;
            this.maxHealth = Math.max(1, maxHealth);
            this.health = this.maxHealth;
            this.nextWaveAt = System.currentTimeMillis()
                + TeamPackManager.getInstance().waveSeconds * 1000L;
        }

        public BlockPos getSpawnPos() {
            return pos.above();
        }
    }

    private record PendingRallyRespawn(UUID teamPackId, long spawnAt) {
    }

    private static final class SquadKey {
        private final String team;
        private final int squadId;

        private SquadKey(String team, int squadId) {
            this.team = team;
            this.squadId = squadId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SquadKey other)) {
                return false;
            }
            return squadId == other.squadId && Objects.equals(team, other.team);
        }

        @Override
        public int hashCode() {
            return Objects.hash(team, squadId);
        }
    }
}
