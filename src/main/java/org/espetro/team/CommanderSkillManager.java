package org.espetro.team;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final String SUPPLY_STATION_TAG = "espetro_vehicle_supply_station";
    private static final String SUPPLY_STATION_ID_DATA = "espetro_vehicle_supply_station_id";
    private static final String SUPPLY_STATION_BLOCK_ID_DATA = "espetro_vehicle_supply_station_block_id";
    private static final String SUPPLY_STATION_BLOCK_X_DATA = "espetro_vehicle_supply_station_block_x";
    private static final String SUPPLY_STATION_BLOCK_Y_DATA = "espetro_vehicle_supply_station_block_y";
    private static final String SUPPLY_STATION_BLOCK_Z_DATA = "espetro_vehicle_supply_station_block_z";
    private static final String ESPOINTS_ARTILLERY_PACKET_CLASS_NAME =
        "com.example.espoints.network.OpenArtillerySupportMapMessage";
    private static final int MAX_ARTILLERY_REQUEST_HISTORY = 128;

    private final Map<UUID, Map<String, Long>> cooldownEndTicks = new HashMap<>();
    private final List<ArtillerySupportRequest> artillerySupportRequests = new ArrayList<>();
    private final Map<UUID, String> pendingTargetSkillIds = new HashMap<>();

    public record SkillView(String id, String displayName, String description, String stats) {
    }

    public record ArtillerySupportRequest(UUID commanderId,
                                          String commanderName,
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

    private record PlannedSupplyStationBlock(BlockPos pos, BlockState state, String blockId) {
    }

    public record VehicleSupplyStationPlacement(String entityType,
                                                String customName,
                                                int x,
                                                int y,
                                                int z,
                                                float yaw,
                                                VehicleSupplyStationBlockPlacement block) {
    }

    public record VehicleSupplyStationBlockPlacement(String blockId, int x, int y, int z) {
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
            Espetro.sendToPlayer(commander, "\u00a7a正在打开战术地图选点界面，右键选择目标点。");
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

    public int runDroneDetection(ServerPlayer commander, double range, int durationSeconds) {
        MinecraftServer server = commander.getServer();
        if (server == null) return -1;

        String commanderTeam = normalizeTeam(Espetro.getPlayerTeam(commander));
        if (commanderTeam == null) {
            Espetro.sendToPlayer(commander, "\u00a7c你不属于任何阵营，无法发动无人机侦测！");
            return -1;
        }

        int durationTicks = durationSeconds * 20;

        int detectedCount = 0;
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (target == commander) continue;
            if (target.level() != commander.level()) continue;

            String targetTeam = normalizeTeam(Espetro.getPlayerTeam(target));
            if (!commanderTeam.equals(targetTeam)) {
                if (commander.distanceTo(target) <= range) {
                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, durationTicks, 0, false, false, true));
                    detectedCount++;
                }
            }
        }

        Espetro.LOGGER.info("指挥官 {} 发动无人机侦测，检测到 {} 名敌方玩家",
            commander.getName().getString(), detectedCount);
        return detectedCount;
    }

    public boolean deployVehicleSupplyStation(ServerPlayer commander, List<VehicleSupplyStationPlacement> placements) {
        ServerLevel level = commander.serverLevel();
        BlockPos basePos = commander.blockPosition();
        String commanderTeam = normalizeTeam(Espetro.getPlayerTeam(commander));
        if (commanderTeam == null || commanderTeam.isBlank()) {
            Espetro.sendToPlayer(commander, "\u00a7c你不属于任何阵营，无法部署载具补给站！");
            return false;
        }

        List<Entity> plannedEntities = new ArrayList<>();
        List<PlannedSupplyStationBlock> plannedBlocks = new ArrayList<>();
        List<BlockPos> plannedBlockPositions = new ArrayList<>();
        UUID stationId = UUID.randomUUID();
        if (placements == null || placements.isEmpty()) {
            Espetro.sendToPlayer(commander, "\u00a7c载具补给站脚本没有提供可生成实体！");
            return false;
        }

        for (VehicleSupplyStationPlacement placement : placements) {
            BlockPos pos = basePos.offset(placement.x(), placement.y(), placement.z());
            if (!level.isInWorldBounds(pos)) {
                Espetro.sendToPlayer(commander, "\u00a7c载具补给站位置超出世界边界！");
                return false;
            }

            PlannedSupplyStationBlock plannedBlock = planSupplyStationBlock(placement, pos);
            if (plannedBlock == null) {
                String blockId = placement.block() != null ? placement.block().blockId() : "<missing>";
                Espetro.sendToPlayer(commander, "\u00a7c载具补给站配置包含无效方块: " + blockId);
                return false;
            }
            if (!level.isInWorldBounds(plannedBlock.pos())) {
                Espetro.sendToPlayer(commander, "\u00a7c载具补给站方块位置超出世界边界！");
                return false;
            }
            if (plannedBlockPositions.contains(plannedBlock.pos())) {
                Espetro.sendToPlayer(commander, "\u00a7c载具补给站方块位置重复: " + plannedBlock.pos().toShortString());
                return false;
            }
            if (!level.isEmptyBlock(plannedBlock.pos())) {
                Espetro.sendToPlayer(commander, "\u00a7c载具补给站方块位置已有方块: " + plannedBlock.pos().toShortString());
                return false;
            }

            Entity entity = createSupplyStationEntity(level, placement, pos, commanderTeam, stationId, basePos, plannedBlock);
            if (entity == null) {
                Espetro.sendToPlayer(commander, "\u00a7c载具补给站配置包含无效实体: " + placement.entityType());
                return false;
            }

            plannedEntities.add(entity);
            plannedBlocks.add(plannedBlock);
            plannedBlockPositions.add(plannedBlock.pos());
        }

        if (plannedEntities.isEmpty()) {
            Espetro.sendToPlayer(commander, "\u00a7c载具补给站没有可生成实体！");
            return false;
        }

        List<BlockPos> placedBlocks = new ArrayList<>();
        for (PlannedSupplyStationBlock plannedBlock : plannedBlocks) {
            if (!level.setBlock(plannedBlock.pos(), plannedBlock.state(), 3)) {
                rollbackSupplyStationBlocks(level, placedBlocks);
                Espetro.sendToPlayer(commander, "\u00a7c载具补给站方块放置失败！");
                return false;
            }
            placedBlocks.add(plannedBlock.pos());
        }

        List<Entity> addedEntities = new ArrayList<>();
        for (Entity entity : plannedEntities) {
            if (!level.addFreshEntity(entity)) {
                rollbackSupplyStationEntities(addedEntities);
                rollbackSupplyStationBlocks(level, placedBlocks);
                Espetro.sendToPlayer(commander, "\u00a7c载具补给站实体生成失败！");
                return false;
            }
            addedEntities.add(entity);
        }

        level.playSound(null, basePos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        Espetro.sendToPlayer(commander, "\u00a7a载具补给站已部署！位置: "
            + basePos.getX() + ", " + basePos.getY() + ", " + basePos.getZ());
        Espetro.LOGGER.info("指挥官 {} 在 {} 部署载具补给站，生成 {} 个实体和 {} 个方块",
            commander.getName().getString(), basePos, plannedEntities.size(), plannedBlocks.size());
        return true;
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

    public List<SkillView> getSkillViews() {
        Map<String, SkillView> views = new HashMap<>();
        for (KubeCommanderSkillDefinition definition : EspetroCommanderSkills.getDefinitions()) {
            String stats = definition.stats().isBlank()
                ? "\u00a78KubeJS | 冷却: " + definition.cooldownSeconds() + "秒"
                : definition.stats();
            views.put(definition.id(), new SkillView(definition.id(), definition.displayName(),
                definition.description(), stats));
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

    public List<ArtillerySupportRequest> getArtillerySupportRequestsSnapshot() {
        synchronized (artillerySupportRequests) {
            return List.copyOf(artillerySupportRequests);
        }
    }

    public List<ArtillerySupportRequest> drainArtillerySupportRequests() {
        synchronized (artillerySupportRequests) {
            List<ArtillerySupportRequest> drained = List.copyOf(artillerySupportRequests);
            artillerySupportRequests.clear();
            return drained;
        }
    }

    private Entity createSupplyStationEntity(
        ServerLevel level,
        VehicleSupplyStationPlacement placement,
        BlockPos pos,
        String team,
        UUID stationId,
        BlockPos stationCenter,
        PlannedSupplyStationBlock plannedBlock
    ) {
        EntityType<?> entityType = resolveConfiguredEntityType(placement.entityType());
        if (entityType == null) {
            return null;
        }

        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;

        Entity entity = entityType.create(level);
        if (entity == null) {
            return null;
        }
        entity.setPos(x, y, z);

        String customName = placement.customName();
        if (customName != null && !customName.isBlank()) {
            entity.setCustomName(Component.literal(customName));
            entity.setCustomNameVisible(true);
        }

        entity.setYRot(placement.yaw());
        entity.setYHeadRot(placement.yaw());
        entity.addTag(SUPPLY_STATION_TAG);
        entity.addTag("espetro_vehicle_supply_station_team_" + team);
        entity.addTag("espetro_vehicle_supply_station_id_" + stationId);
        entity.addTag("espetro_team_" + team);
        entity.addTag("espetro_commander_skill");
        CompoundTag data = entity.getPersistentData();
        data.putString("espetro_vehicle_supply_station_team", team);
        data.putUUID(SUPPLY_STATION_ID_DATA, stationId);
        data.putInt("espetro_vehicle_supply_station_x", stationCenter.getX());
        data.putInt("espetro_vehicle_supply_station_y", stationCenter.getY());
        data.putInt("espetro_vehicle_supply_station_z", stationCenter.getZ());
        data.putString(SUPPLY_STATION_BLOCK_ID_DATA, plannedBlock.blockId());
        data.putInt(SUPPLY_STATION_BLOCK_X_DATA, plannedBlock.pos().getX());
        data.putInt(SUPPLY_STATION_BLOCK_Y_DATA, plannedBlock.pos().getY());
        data.putInt(SUPPLY_STATION_BLOCK_Z_DATA, plannedBlock.pos().getZ());
        return entity;
    }

    private PlannedSupplyStationBlock planSupplyStationBlock(VehicleSupplyStationPlacement placement, BlockPos entityPos) {
        VehicleSupplyStationBlockPlacement blockConfig = placement.block();
        if (blockConfig == null) {
            return null;
        }

        Block block = resolveConfiguredBlock(blockConfig.blockId());
        if (block == null) {
            return null;
        }

        BlockPos blockPos = entityPos.offset(blockConfig.x(), blockConfig.y(), blockConfig.z());
        return new PlannedSupplyStationBlock(blockPos, block.defaultBlockState(), blockConfig.blockId());
    }

    private void rollbackSupplyStationBlocks(ServerLevel level, List<BlockPos> placedBlocks) {
        for (BlockPos pos : placedBlocks) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private void rollbackSupplyStationEntities(List<Entity> entities) {
        for (Entity entity : entities) {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
    }

    public static boolean isVehicleSupplyStationEntity(Entity entity) {
        return entity != null && entity.getTags().contains(SUPPLY_STATION_TAG);
    }

    public void onVehicleSupplyStationDestroyed(Entity destroyedEntity) {
        if (!(destroyedEntity.level() instanceof ServerLevel level)) {
            return;
        }

        CompoundTag destroyedData = destroyedEntity.getPersistentData();
        if (!destroyedData.hasUUID(SUPPLY_STATION_ID_DATA)) {
            return;
        }

        UUID stationId = destroyedData.getUUID(SUPPLY_STATION_ID_DATA);
        List<Entity> stationEntities = findSupplyStationEntities(level, stationId, destroyedEntity);
        Map<BlockPos, String> blocksToRemove = new HashMap<>();
        for (Entity entity : stationEntities) {
            collectSupplyStationBlock(blocksToRemove, entity);
        }

        for (Map.Entry<BlockPos, String> entry : blocksToRemove.entrySet()) {
            removeSupplyStationBlock(level, entry.getKey(), entry.getValue());
        }

        for (Entity entity : stationEntities) {
            if (entity.getUUID().equals(destroyedEntity.getUUID())) {
                continue;
            }
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }

        Espetro.LOGGER.debug("载具补给站 {} 已清理，删除 {} 个实体和 {} 个方块",
            stationId, stationEntities.size(), blocksToRemove.size());
    }

    private List<Entity> findSupplyStationEntities(ServerLevel level, UUID stationId, Entity destroyedEntity) {
        List<Entity> entities = new ArrayList<>();
        entities.add(destroyedEntity);

        for (Entity entity : level.getAllEntities()) {
            if (entity.getUUID().equals(destroyedEntity.getUUID())) {
                continue;
            }
            if (isSameSupplyStation(entity, stationId)) {
                entities.add(entity);
            }
        }

        return entities;
    }

    private boolean isSameSupplyStation(Entity entity, UUID stationId) {
        if (!isVehicleSupplyStationEntity(entity)) {
            return false;
        }
        CompoundTag data = entity.getPersistentData();
        return data.hasUUID(SUPPLY_STATION_ID_DATA) && data.getUUID(SUPPLY_STATION_ID_DATA).equals(stationId);
    }

    private void collectSupplyStationBlock(Map<BlockPos, String> blocksToRemove, Entity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(SUPPLY_STATION_BLOCK_X_DATA)
            || !data.contains(SUPPLY_STATION_BLOCK_Y_DATA)
            || !data.contains(SUPPLY_STATION_BLOCK_Z_DATA)) {
            return;
        }

        BlockPos pos = new BlockPos(
            data.getInt(SUPPLY_STATION_BLOCK_X_DATA),
            data.getInt(SUPPLY_STATION_BLOCK_Y_DATA),
            data.getInt(SUPPLY_STATION_BLOCK_Z_DATA)
        );
        blocksToRemove.put(pos, data.getString(SUPPLY_STATION_BLOCK_ID_DATA));
    }

    private void removeSupplyStationBlock(ServerLevel level, BlockPos pos, String blockId) {
        Block block = resolveConfiguredBlock(blockId);
        if (block == null) {
            return;
        }

        BlockState currentState = level.getBlockState(pos);
        if (currentState.getBlock() == block) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private EntityType<?> resolveConfiguredEntityType(String entityType) {
        ResourceLocation location = parseRegistryLocation(entityType);
        if (location == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(location)) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.get(location);
    }

    private Block resolveConfiguredBlock(String blockId) {
        ResourceLocation location = parseRegistryLocation(blockId);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(location);
    }

    private ResourceLocation parseRegistryLocation(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return id.contains(":")
            ? ResourceLocation.tryParse(id)
            : ResourceLocation.withDefaultNamespace(id);
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
