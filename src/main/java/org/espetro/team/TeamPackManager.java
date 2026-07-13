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
    private int cooldownSeconds = 300;
    private int durability = 1;
    private float breakSpeedMultiplier = 8.0f;

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

    private void loadConfig() {
        cooldownSeconds = 300;
        durability = 1;
        breakSpeedMultiplier = 8.0f;
        try {
            MinecraftServer server = Espetro.getServer();
            if (server == null) {
                Espetro.LOGGER.warn("服务器未初始化，队包使用默认配置");
                return;
            }

            ResourceLocation location = ResourceLocation.parse("espetro:config/team_pack.json");
            var resourceOptional = org.espetro.data.EspetroDataResources.getPreferred(server.getResourceManager(), location);
            if (!resourceOptional.isPresent()) {
                Espetro.LOGGER.warn("未找到 team_pack.json 配置文件，使用默认值");
                return;
            }

            InputStream inputStream = resourceOptional.get().open();
            if (inputStream == null) {
                Espetro.LOGGER.warn("无法打开 team_pack.json 配置文件，使用默认值");
                return;
            }

            JsonObject json = GSON.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);
            inputStream.close();

            if (json != null && json.has("team_pack")) {
                JsonObject teamPack = json.getAsJsonObject("team_pack");
                if (teamPack.has("cooldown_seconds")) {
                    cooldownSeconds = Math.max(0, teamPack.get("cooldown_seconds").getAsInt());
                }
                if (teamPack.has("durability")) {
                    durability = Math.max(1, teamPack.get("durability").getAsInt());
                }
                if (teamPack.has("break_speed_multiplier")) {
                    breakSpeedMultiplier = Math.max(1.0f, teamPack.get("break_speed_multiplier").getAsFloat());
                }
            }

            Espetro.LOGGER.info("队包配置已加载: 冷却{}秒, 耐久{}, 破坏速度倍率{}",
                cooldownSeconds, durability, breakSpeedMultiplier);
        } catch (Exception e) {
            Espetro.LOGGER.error("加载队包配置失败: {}", e.getMessage());
        }
    }

    public void reloadConfig() {
        loadConfig();
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
        if (pendingItemSyncs.isEmpty()) {
            return;
        }

        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            pendingItemSyncs.clear();
            return;
        }

        List<UUID> pendingPlayers = new ArrayList<>(pendingItemSyncs);
        pendingItemSyncs.clear();
        for (UUID playerId : pendingPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                syncTeamPackItem(player);
            }
        }
    }

    public boolean isTeamPackItem(ItemStack stack) {
        return stack.getItem() == Items.BEACON
            && stack.hasTag()
            && stack.getTag() != null
            && stack.getTag().getBoolean(TEAM_PACK_ITEM_TAG);
    }

    public void syncTeamPackItem(ServerPlayer player) {
        boolean isLeader = SquadManager.getInstance().isSquadLeader(player.getUUID());
        if (!isLeader) {
            removeTeamPackItems(player);
            return;
        }
        String team = Espetro.getPlayerTeam(player);
        int squadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
        applyInheritedLeaderCooldown(player.getUUID(), team, squadId);
        giveTeamPackItemIfNeeded(player);
    }

    public void giveTeamPackItemIfNeeded(ServerPlayer player) {
        if (!SquadManager.getInstance().isSquadLeader(player.getUUID())) {
            removeTeamPackItems(player);
            return;
        }

        for (ItemStack stack : player.getInventory().items) {
            if (isTeamPackItem(stack)) {
                return;
            }
        }
        if (isTeamPackItem(player.getInventory().offhand.get(0))) {
            return;
        }

        ItemStack teamPack = new ItemStack(Items.BEACON);
        CompoundTag tag = teamPack.getOrCreateTag();
        tag.putBoolean(TEAM_PACK_ITEM_TAG, true);
        teamPack.setHoverName(Component.literal("§d队包方块"));
        player.getInventory().add(teamPack);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        player.sendSystemMessage(Component.literal("§d你获得了 §f队包方块§d！放置信标后可为本小队提供“队伍集结点”。"));
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

    @Nullable
    public String placeTeamPack(ServerPlayer player, ServerLevel level, BlockPos pos) {
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
        if (squadTeamPacks.containsKey(squadKey)) {
            return "§c你的小队已经部署了一个队包，不能重复放置！";
        }
        int cooldownRemaining = getSquadCooldownRemaining(team, squadId);
        if (cooldownRemaining > 0) {
            return "§c队包冷却中！请等待 " + cooldownRemaining + " 秒后再试。";
        }

        TeamPackData teamPack = new TeamPackData(UUID.randomUUID(), team, squadId, pos, level, durability);
        teamPacks.put(teamPack.teamPackId, teamPack);
        squadTeamPacks.put(squadKey, teamPack.teamPackId);
        teamPackPositions.put(pos.immutable(), teamPack.teamPackId);
        setSquadCooldown(squadKey);

        pendingItemSyncs.add(player.getUUID());

        player.sendSystemMessage(Component.literal("§a队包已部署！你的队员死亡后可选择 §d队伍集结点 §a复活。"));
        if (cooldownSeconds > 0) {
            player.sendSystemMessage(Component.literal("§7队包部署冷却: " + cooldownSeconds + "秒"));
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

        BlockPos spawnPos = teamPack.getSpawnPos();
        result.add(new UnifiedDeployScreenPacket.BastionItem(
            teamPack.teamPackId,
            "队伍集结点",
            spawnPos.getX() + ", " + spawnPos.getY() + ", " + spawnPos.getZ()
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
        player.sendSystemMessage(Component.literal("§a已在 §d队伍集结点 §a复活！"));
        return true;
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

        public TeamPackData(UUID teamPackId, String team, int squadId, BlockPos pos, ServerLevel level, int maxHealth) {
            this.teamPackId = teamPackId;
            this.team = team;
            this.squadId = squadId;
            this.pos = pos.immutable();
            this.level = level;
            this.maxHealth = Math.max(1, maxHealth);
            this.health = this.maxHealth;
        }

        public BlockPos getSpawnPos() {
            return pos.above();
        }
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
