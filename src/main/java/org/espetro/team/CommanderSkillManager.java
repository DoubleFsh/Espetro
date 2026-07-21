package org.espetro.team;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.ModList;
import org.espetro.Espetro;
import org.espetro.kubejs.commander.EspetroCommanderSkills;
import org.espetro.kubejs.commander.KubeCommanderSkillDefinition;
import org.espetro.kubejs.commander.KubeCommanderSkillEvent;
import org.espetro.network.NetworkManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class CommanderSkillManager {

    private static CommanderSkillManager INSTANCE;
    private static final String ESPOINTS_ARTILLERY_PACKET_CLASS_NAME =
        "com.example.espoints.network.OpenArtillerySupportMapMessage";
    private static final int MAX_ARTILLERY_REQUEST_HISTORY = 128;

    private final Map<UUID, Map<String, Long>> cooldownEndTicks = new HashMap<>();
    private final List<ArtillerySupportRequest> artillerySupportRequests = new ArrayList<>();
    private final Map<UUID, String> pendingTargetSkillIds = new HashMap<>();

    public record SkillView(String id, String displayName, String description, String stats, String icon) {
    }

    public record SkillStatus(String id,
                              String displayName,
                              boolean registered,
                              boolean commander,
                              boolean phaseAllowed,
                              boolean onCooldown,
                              int cooldownSeconds,
                              boolean targetMap,
                              boolean canUse,
                              String phase) {
        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isRegistered() {
            return registered;
        }

        public boolean isCommander() {
            return commander;
        }

        public boolean isPhaseAllowed() {
            return phaseAllowed;
        }

        public boolean isOnCooldown() {
            return onCooldown;
        }

        public int getCooldownSeconds() {
            return cooldownSeconds;
        }

        public boolean isTargetMap() {
            return targetMap;
        }

        public boolean canUse() {
            return canUse;
        }

        public String getPhase() {
            return phase;
        }
    }

    public record ArtillerySupportRequest(UUID commanderId,
                                          String commanderName,
                                          String skillId,
                                          String skillName,
                                          String team,
                                          ResourceKey<Level> dimension,
                                          double x,
                                          double y,
                                          double z,
                                          BlockPos blockPos,
                                          long gameTime,
                                          long createdAtMillis) {
        public UUID getCommanderId() {
            return commanderId;
        }

        public String getCommanderName() {
            return commanderName;
        }

        public String getSkillId() {
            return skillId;
        }

        public String getSkillName() {
            return skillName;
        }

        public String getTeam() {
            return team;
        }

        public ResourceKey<Level> getDimension() {
            return dimension;
        }

        public String getDimensionId() {
            return dimension.location().toString();
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }

        public BlockPos getBlockPos() {
            return blockPos;
        }

        public int getBlockX() {
            return blockPos.getX();
        }

        public int getBlockY() {
            return blockPos.getY();
        }

        public int getBlockZ() {
            return blockPos.getZ();
        }

        public long getGameTime() {
            return gameTime;
        }

        public long getCreatedAtMillis() {
            return createdAtMillis;
        }

        public ServerPlayer getCommander() {
            MinecraftServer server = Espetro.getServer();
            return server == null ? null : server.getPlayerList().getPlayer(commanderId);
        }
    }

    private CommanderSkillManager() {
        INSTANCE = this;
    }

    public static CommanderSkillManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CommanderSkillManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new CommanderSkillManager();
    }

    public boolean activateSkill(ServerPlayer commander, CommanderSkillType skillType) {
        if (commander == null || skillType == null) return false;
        return activateSkill(commander, skillType.getId());
    }

    public boolean activateSkill(ServerPlayer commander, String skillId) {
        if (commander == null || skillId == null || skillId.isBlank()) return false;

        if (!VoteManager.getInstance().isCommander(commander.getUUID())) {
            Espetro.sendToPlayer(commander, "\u00a7c你不是指挥官，无法使用技能！");
            return false;
        }

        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE && phase != GamePhase.DEPLOYING) {
            Espetro.sendToPlayer(commander, "\u00a7c当前阶段无法使用指挥官技能！");
            return false;
        }

        if (isOnCooldown(commander.getUUID(), skillId)) {
            int remaining = getRemainingCooldownSeconds(commander.getUUID(), skillId);
            Espetro.sendToPlayer(commander, "\u00a7c技能冷却中，剩余 " + remaining + " 秒");
            return false;
        }

        KubeCommanderSkillDefinition definition = EspetroCommanderSkills.getDefinition(skillId);
        if (definition != null) {
            boolean success;
            if (definition.isTargetMapTrigger()) {
                success = beginArtilleryTargetSelection(commander, definition.id());
            } else {
                KubeCommanderSkillEvent event = EspetroCommanderSkills.event(
                    definition, commander, normalizeTeam(Espetro.getPlayerTeam(commander)));
                success = EspetroCommanderSkills.execute(definition, event);
                if (success) {
                    finishCommanderSkill(commander, skillId, definition.displayName(),
                        definition.cooldownSeconds() * 20L);
                }
            }
            return success;
        }

        Espetro.sendToPlayer(commander, "\u00a7c未配置指挥官技能: " + skillId
            + "，请在 KubeJS startup_scripts 中注册 Espetro 指挥官技能。");
        return false;
    }

    public boolean beginArtilleryTargetSelection(ServerPlayer commander) {
        return beginArtilleryTargetSelection(commander, EspetroCommanderSkills.DEFAULT_ARTILLERY_SKILL_ID);
    }

    public boolean beginArtilleryTargetSelection(ServerPlayer commander, String skillId) {
        if (commander == null) {
            return false;
        }
        if (!ModList.get().isLoaded("espoints")) {
            Espetro.sendToPlayer(commander, "\u00a7c该指挥官技能需要安装 ESPoints 才能打开战术地图。");
            return false;
        }

        try {
            Class<?> packetClass = Class.forName(ESPOINTS_ARTILLERY_PACKET_CLASS_NAME);
            packetClass.getMethod("sendTo", ServerPlayer.class).invoke(null, commander);
            pendingTargetSkillIds.put(commander.getUUID(),
                skillId == null || skillId.isBlank() ? EspetroCommanderSkills.DEFAULT_ARTILLERY_SKILL_ID : skillId);
            return true;
        } catch (ReflectiveOperationException e) {
            Espetro.LOGGER.warn("无法通过 ESPoints 打开指挥官技能战术地图", e);
            Espetro.sendToPlayer(commander, "\u00a7cESPoints 不支持指挥官技能选点接口，请确认双方模组版本一致。");
            return false;
        }
    }

    public boolean submitArtillerySupportTarget(ServerPlayer commander, double x, double z) {
        if (commander == null || !Double.isFinite(x) || !Double.isFinite(z)) {
            return false;
        }

        UUID commanderId = commander.getUUID();
        String skillId = pendingTargetSkillIds.getOrDefault(commanderId, EspetroCommanderSkills.DEFAULT_ARTILLERY_SKILL_ID);
        KubeCommanderSkillDefinition definition = EspetroCommanderSkills.getDefinition(skillId);
        String skillName = definition != null ? definition.displayName() : "指挥官选点技能";
        if (!VoteManager.getInstance().isCommander(commander.getUUID())) {
            Espetro.sendToPlayer(commander, "\u00a7c你不是指挥官，无法提交" + skillName + "坐标！");
            pendingTargetSkillIds.remove(commanderId);
            return false;
        }

        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE && phase != GamePhase.DEPLOYING) {
            Espetro.sendToPlayer(commander, "\u00a7c当前阶段无法提交" + skillName + "坐标！");
            pendingTargetSkillIds.remove(commanderId);
            return false;
        }

        if (isOnCooldown(commander.getUUID(), skillId)) {
            int remaining = getRemainingCooldownSeconds(commander.getUUID(), skillId);
            Espetro.sendToPlayer(commander, "\u00a7c" + skillName + "冷却中，剩余 " + remaining + " 秒");
            pendingTargetSkillIds.remove(commanderId);
            return false;
        }

        String commanderTeam = normalizeTeam(Espetro.getPlayerTeam(commander));
        if (commanderTeam == null) {
            Espetro.sendToPlayer(commander, "\u00a7c你不属于任何阵营，无法提交" + skillName + "坐标！");
            pendingTargetSkillIds.remove(commanderId);
            return false;
        }

        ServerLevel level = commander.serverLevel();
        BlockPos targetBlock = resolveArtilleryTargetBlock(level, x, z);
        ArtillerySupportRequest request = new ArtillerySupportRequest(
            commander.getUUID(),
            commander.getName().getString(),
            definition != null ? definition.id() : skillId,
            skillName,
            commanderTeam,
            level.dimension(),
            x,
            targetBlock.getY(),
            z,
            targetBlock,
            level.getGameTime(),
            System.currentTimeMillis()
        );

        if (definition == null) {
            Espetro.sendToPlayer(commander, "\u00a7c未找到" + skillName
                + "配置，请在 KubeJS startup_scripts 中注册该指挥官技能。");
            pendingTargetSkillIds.remove(commanderId);
            return false;
        }
        if (!definition.isTargetMapTrigger()) {
            Espetro.sendToPlayer(commander, "\u00a7c" + skillName + "不再是战术地图选点技能，请重新打开技能界面。");
            pendingTargetSkillIds.remove(commanderId);
            return false;
        }
        KubeCommanderSkillEvent event = EspetroCommanderSkills.targetEvent(
            definition, request, commander, level, targetBlock);
        if (!EspetroCommanderSkills.execute(definition, event)) {
            Espetro.sendToPlayer(commander, "\u00a7c" + skillName + "KubeJS 回调执行失败，请检查服务端日志。");
            pendingTargetSkillIds.remove(commanderId);
            return false;
        }
        pendingTargetSkillIds.remove(commanderId);

        synchronized (artillerySupportRequests) {
            while (artillerySupportRequests.size() >= MAX_ARTILLERY_REQUEST_HISTORY) {
                artillerySupportRequests.remove(0);
            }
            artillerySupportRequests.add(request);
        }

        finishCommanderSkill(commander, skillId, definition.displayName(), definition.cooldownSeconds() * 20L);
        Espetro.sendToPlayer(commander, "\u00a7a" + skillName + "坐标已提交: "
            + targetBlock.getX() + ", " + targetBlock.getY() + ", " + targetBlock.getZ());
        Espetro.LOGGER.info("指挥官 {} 提交 {} 坐标: {} {} {} ({})",
            commander.getName().getString(), skillName, targetBlock.getX(), targetBlock.getY(), targetBlock.getZ(),
            request.getDimensionId());
        return true;
    }

    private BlockPos resolveArtilleryTargetBlock(ServerLevel level, double x, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        return new BlockPos(blockX, y, blockZ);
    }

    private void finishCommanderSkill(ServerPlayer commander, String skillId, String displayName, long cooldownTicks) {
        cooldownEndTicks
            .computeIfAbsent(commander.getUUID(), k -> new HashMap<>())
            .put(skillId, getServerTick() + cooldownTicks);

        NetworkManager.sendCommanderSkillSync(commander);

        String team = Espetro.getPlayerTeam(commander);
        if (team != null) {
            Espetro.broadcastToTeam(team, "\u00a76\u26a1 指挥官 " + commander.getName().getString()
                + " 发动了 " + displayName + "！");
        }
    }

    public boolean isOnCooldown(UUID uuid, CommanderSkillType type) {
        return type != null && isOnCooldown(uuid, type.getId());
    }

    public boolean isOnCooldown(UUID uuid, String skillId) {
        Map<String, Long> map = cooldownEndTicks.get(uuid);
        if (map == null) return false;
        Long endTick = map.get(skillId);
        if (endTick == null) return false;
        return getServerTick() < endTick;
    }

    public int getRemainingCooldownSeconds(UUID uuid, CommanderSkillType type) {
        return type == null ? 0 : getRemainingCooldownSeconds(uuid, type.getId());
    }

    public int getRemainingCooldownSeconds(UUID uuid, String skillId) {
        Map<String, Long> map = cooldownEndTicks.get(uuid);
        if (map == null) return 0;
        Long endTick = map.get(skillId);
        if (endTick == null) return 0;
        long remaining = endTick - getServerTick();
        return remaining <= 0 ? 0 : (int) Math.ceil(remaining / 20.0);
    }

    public Map<String, Integer> getCooldownData(UUID uuid) {
        Map<String, Integer> data = new HashMap<>();
        for (SkillView view : getSkillViews()) {
            int remaining = getRemainingCooldownSeconds(uuid, view.id());
            data.put(view.id(), remaining);
        }
        return data;
    }

    public SkillStatus getSkillStatus(ServerPlayer commander, String skillId) {
        String normalizedSkillId = skillId == null ? "" : skillId.trim();
        KubeCommanderSkillDefinition definition = EspetroCommanderSkills.getDefinition(normalizedSkillId);
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        boolean commanderAllowed = commander != null && VoteManager.getInstance().isCommander(commander.getUUID());
        boolean phaseAllowed = phase == GamePhase.BATTLE || phase == GamePhase.DEPLOYING;
        int cooldownSeconds = commander == null ? 0
            : getRemainingCooldownSeconds(commander.getUUID(), normalizedSkillId);
        boolean onCooldown = cooldownSeconds > 0;
        boolean registered = definition != null;
        boolean canUse = registered && commanderAllowed && phaseAllowed && !onCooldown;

        return new SkillStatus(
            normalizedSkillId,
            definition != null ? definition.displayName() : normalizedSkillId,
            registered,
            commanderAllowed,
            phaseAllowed,
            onCooldown,
            cooldownSeconds,
            definition != null && definition.isTargetMapTrigger(),
            canUse,
            phase.name()
        );
    }

    public List<SkillView> getSkillViews() {
        Map<String, SkillView> views = new HashMap<>();
        for (KubeCommanderSkillDefinition definition : EspetroCommanderSkills.getDefinitions()) {
            String stats = definition.stats().isBlank()
                ? "\u00a78KubeJS | 冷却: " + definition.cooldownSeconds() + "秒"
                : definition.stats();
            views.put(definition.id(), new SkillView(definition.id(), definition.displayName(),
                definition.description(), stats, definition.icon()));
        }

        return views.values().stream()
            .sorted((a, b) -> a.id().compareTo(b.id()))
            .toList();
    }

    public ArtillerySupportRequest getLatestArtillerySupportRequest() {
        synchronized (artillerySupportRequests) {
            return artillerySupportRequests.isEmpty()
                ? null
                : artillerySupportRequests.get(artillerySupportRequests.size() - 1);
        }
    }

    public ArtillerySupportRequest getLatestCommanderSkillTargetRequest() {
        return getLatestArtillerySupportRequest();
    }

    public List<ArtillerySupportRequest> getArtillerySupportRequestsSnapshot() {
        synchronized (artillerySupportRequests) {
            return List.copyOf(artillerySupportRequests);
        }
    }

    public List<ArtillerySupportRequest> getCommanderSkillTargetRequestsSnapshot() {
        return getArtillerySupportRequestsSnapshot();
    }

    public List<ArtillerySupportRequest> drainArtillerySupportRequests() {
        synchronized (artillerySupportRequests) {
            List<ArtillerySupportRequest> drained = List.copyOf(artillerySupportRequests);
            artillerySupportRequests.clear();
            return drained;
        }
    }

    public List<ArtillerySupportRequest> drainCommanderSkillTargetRequests() {
        return drainArtillerySupportRequests();
    }

    private String normalizeTeam(String team) {
        if (team == null || team.isBlank()) {
            return null;
        }
        String normalized = team.toLowerCase(Locale.ROOT);
        if (normalized.contains("attack") || normalized.contains("attacker")
            || team.contains("进攻") || team.contains("攻方")) {
            return "ATTACK";
        }
        if (normalized.contains("defend") || normalized.contains("defender")
            || team.contains("防守") || team.contains("守方")) {
            return "DEFEND";
        }
        return null;
    }

    private long getServerTick() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return 0;
        return server.getTickCount();
    }

    public void onServerTick() {
    }

    public void reset() {
        cooldownEndTicks.clear();
        pendingTargetSkillIds.clear();
        synchronized (artillerySupportRequests) {
            artillerySupportRequests.clear();
        }
    }
}
