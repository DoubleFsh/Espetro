package org.espetro.bastion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import org.espetro.Espetro;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 兵站管理器
 * 管理所有兵站的创建、存储和查询
 */
public class BastionManager {

    /** 全局最多记录兵站数量 */
    public static final int MAX_BASTIONS = 4;
    /** 兼容旧调用，实际已改为全局上限。 */
    @Deprecated
    public static final int MAX_BASTIONS_PER_TEAM = MAX_BASTIONS;
    private static BastionManager INSTANCE;

    // 所有兵站列表
    private final Map<UUID, BastionData> bastions = new HashMap<>();

    // 固定 4 槽兵站记录表：槽位即隐藏兵站编号，坐标为盔甲架最新位置
    private final UUID[] bastionSlots = new UUID[MAX_BASTIONS];
    private final BlockPos[] bastionSlotPositions = new BlockPos[MAX_BASTIONS];

    // 正在等待复活选择的玩家
    private final Map<UUID, UUID> waitingPlayers = new HashMap<>(); // playerUUID -> bastionChoiceRequestId

    // 玩家的原部署点位置
    private final Map<UUID, DeployPoint> playerDeployPoints = new HashMap<>(); // playerUUID -> DeployPoint

    // 玩家建造冷却记录
    private final Map<UUID, Long> bastionCooldowns = new HashMap<>(); // playerUUID -> lastUseTimestamp (毫秒)

    // 玩家位置锁定（等待复活选择时）
    private final Map<UUID, net.minecraft.world.phys.Vec3> playerLockPositions = new HashMap<>();

    // 弹药补给追踪
    private final Map<UUID, Long> resupplyCooldowns = new HashMap<>(); // playerUUID -> 最后补给时间戳
    private final Map<UUID, Integer> resupplyCounts = new HashMap<>(); // playerUUID -> 累计补给次数

    /** 弹药补给冷却时间（毫秒） */
    public static final long RESUPPLY_COOLDOWN_MS = 5 * 60 * 1000;

    // 从 JSON 配置读取的值
    private int cooldownSeconds = 800;
    private int requiredPlanks = 640;
    private int armorStandHealth = 5;
    private int destroyTroopPenalty = 20;

    private BastionManager() {
        INSTANCE = this;
        loadConfig();
    }

    /**
     * 从 JSON 文件加载配置
     */
    private void loadConfig() {
        try {
            MinecraftServer server = Espetro.getServer();
            if (server == null) {
                Espetro.LOGGER.warn("服务器未初始化，使用默认配置");
                return;
            }

            net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.parse("espetro:config/bastion.json");
            var resourceOptional = server.getResourceManager().getResource(location);

            if (!resourceOptional.isPresent()) {
                Espetro.LOGGER.warn("未找到 bastion.json 配置文件，使用默认值");
                return;
            }

            InputStream inputStream = resourceOptional.get().open();
            if (inputStream == null) {
                Espetro.LOGGER.warn("无法打开 bastion.json 配置文件，使用默认值");
                return;
            }

            Gson gson = new Gson();
            JsonObject json = gson.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);
            inputStream.close();

            if (json.has("bastion")) {
                JsonObject bastion = json.getAsJsonObject("bastion");
                if (bastion.has("cooldown_seconds")) {
                    cooldownSeconds = bastion.get("cooldown_seconds").getAsInt();
                }
                if (bastion.has("required_planks")) {
                    requiredPlanks = bastion.get("required_planks").getAsInt();
                }
                if (bastion.has("armor_stand_health")) {
                    armorStandHealth = Math.max(1, bastion.get("armor_stand_health").getAsInt());
                }
                if (bastion.has("destroy_troop_penalty")) {
                    destroyTroopPenalty = bastion.get("destroy_troop_penalty").getAsInt();
                }
            }

            Espetro.LOGGER.info("兵站配置已加载: 冷却{}秒, 需要{}木板, 盔甲架{}血, 摧毁扣除{}兵力",
                cooldownSeconds, requiredPlanks, armorStandHealth, destroyTroopPenalty);

        } catch (Exception e) {
            Espetro.LOGGER.error("加载兵站配置失败: {}", e.getMessage());
        }
    }

    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        loadConfig();
    }

    /**
     * 获取冷却时间（秒）
     */
    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * 获取所需木板数量
     */
    public int getRequiredPlanks() {
        return requiredPlanks;
    }

    /**
     * 获取盔甲架血量
     */
    public int getArmorStandHealth() {
        return armorStandHealth;
    }

    /**
     * 获取兵站被摧毁时扣除的兵力值
     */
    public int getDestroyTroopPenalty() {
        return destroyTroopPenalty;
    }

    public static BastionManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new BastionManager();
        }
        return INSTANCE;
    }

    /**
     * 创建兵站
     * @param level 世界
     * @param pos 位置
     * @param team 队伍
     * @param name 兵站名称
     * @return 创建的兵站数据，失败返回null
     */
    public BastionData createBastion(ServerLevel level, BlockPos pos, String team, String name) {
        BastionData bastion = new BastionData(team, name, pos, level);
        bastion.setArmorStandPosition(pos.above());

        if (!registerBastionRecord(bastion)) {
            Espetro.LOGGER.warn("兵站数量已达到上限 {}，拒绝创建: {} ({})", MAX_BASTIONS, name, pos);
            return null;
        }
        bastion.setCoreHealth(armorStandHealth);

        // 创建盔甲架实体
        ArmorStand armorStand = createCoreArmorStand(level, pos.above(), team, name);
        if (armorStand == null) {
            releaseBastionRecord(bastion);
            Espetro.LOGGER.error("无法创建盔甲架实体");
            return null;
        }

        // 生成并添加到世界
        level.addFreshEntity(armorStand);

        bastion.setArmorStandId(armorStand.getUUID());
        updateBastionArmorStandPosition(bastion, armorStand.blockPosition());
        bastion.setActive(true);

        bastions.put(bastion.getBastionId(), bastion);

        Espetro.LOGGER.info("创建兵站: {} (队伍: {}, 编号槽: {}, 盔甲架位置: {}, 盔甲架ID: {})",
            name, team, bastion.getBastionNumber(), bastion.getArmorStandPosition(), armorStand.getUUID());

        return bastion;
    }

    /**
     * 获取玩家所属队伍的兵站列表
     */
    public List<BastionData> getTeamBastions(String team) {
        return bastions.values().stream()
            .filter(b -> b.getTeam().equals(team) && isBastionUsable(b))
            .sorted(Comparator.comparing(BastionData::getName))
            .collect(Collectors.toList());
    }

    /**
     * 获取所有兵站列表
     */
    public List<BastionData> getAllBastions() {
        return new ArrayList<>(bastions.values());
    }

    /**
     * 通过兵站核心盔甲架 UUID 查询兵站。
     */
    @Nullable
    public BastionData findBastionByArmorStand(UUID armorStandId) {
        for (BastionData bastion : bastions.values()) {
            if (bastion.getArmorStandId() != null && bastion.getArmorStandId().equals(armorStandId)) {
                return bastion;
            }
        }
        return null;
    }

    /**
     * 获取指定ID的兵站
     */
    @Nullable
    public BastionData getBastion(UUID bastionId) {
        return bastions.get(bastionId);
    }

    /**
     * 设置兵站启用状态。兵站失效时同步清空记录坐标并释放隐藏编号。
     */
    public void setBastionActive(BastionData bastion, boolean active) {
        if (!active) {
            releaseBastionRecord(bastion);
            bastion.setActive(false);
            return;
        }

        if (!registerBastionRecord(bastion)) {
            bastion.setActive(false);
            Espetro.LOGGER.warn("兵站 {} 无法重新激活：编号槽已满", bastion.getName());
            return;
        }
        bastion.setActive(active);
    }

    /**
     * 对兵站核心造成伤害。核心生命由模组维护，不依赖原版盔甲架破坏逻辑。
     *
     * @return true 表示该伤害已被兵站系统处理。
     */
    public boolean damageBastionCore(BastionData bastion, float amount, @Nullable Entity attacker) {
        if (bastion == null || !bastion.isActive() || amount <= 0) {
            return false;
        }

        float maxHealth = Math.max(1, armorStandHealth);
        float currentHealth = bastion.getCoreHealth();
        if (currentHealth <= 0 || currentHealth > maxHealth) {
            currentHealth = maxHealth;
        }

        float remaining = currentHealth - amount;
        bastion.setCoreHealth(Math.max(0, remaining));
        bastion.resetMissingEntityTicks();

        Entity coreEntity = bastion.getArmorStandId() == null ? null : bastion.getLevel().getEntity(bastion.getArmorStandId());
        if (coreEntity instanceof ArmorStand armorStand && armorStand.isAlive()) {
            syncCoreArmorStand(armorStand);
            if (remaining <= 0) {
                // 核心血量归零，直接杀死盔甲架实体
                armorStand.kill();
            } else {
                float visualHealth = Math.max(1.0F, bastion.getCoreHealth());
                armorStand.setHealth(Math.min(visualHealth, armorStand.getMaxHealth()));
                updateBastionArmorStandPosition(bastion, armorStand.blockPosition());
            }
        }

        Espetro.LOGGER.debug("兵站核心受击: {} 伤害={}, 剩余={}/{}",
            bastion.getName(), amount, bastion.getCoreHealth(), maxHealth);

        if (remaining <= 0) {
            destroyBastion(bastion, attacker);
        }
        return true;
    }

    /**
     * 统一摧毁兵站，负责释放编号、移除核心实体、广播和扣兵力。
     */
    public void destroyBastion(BastionData bastion, @Nullable Entity attacker) {
        if (bastion == null || !bastion.isActive()) {
            return;
        }

        String bastionName = bastion.getName();
        String bastionTeam = bastion.getTeam();

        // 强制移除盔甲架实体（无论区块是否加载）
        if (bastion.getArmorStandId() != null) {
            BlockPos entityPos = bastion.getArmorStandPosition();
            if (entityPos == null) entityPos = bastion.getPosition().above();
            bastion.getLevel().getChunkAt(entityPos);
            Entity entity = bastion.getLevel().getEntity(bastion.getArmorStandId());
            if (entity != null) {
                entity.kill();
            }
        }

        setBastionActive(bastion, false);

        int penalty = getDestroyTroopPenalty();
        org.espetro.team.TroopCountManager troopManager = org.espetro.team.TroopCountManager.getInstance();
        if ("ATTACK".equals(bastionTeam)) {
            troopManager.modifyAttackTroops(-penalty);
        } else {
            troopManager.modifyDefendTroops(-penalty);
        }

        Espetro.LOGGER.info("兵站 {} 被摧毁！攻击者={}", bastionName, attacker == null ? "unknown" : attacker.getName().getString());
        Espetro.broadcastToTeam(bastionTeam, "§c[兵站] §e" + bastionName + " §c已被摧毁！- " + penalty + " 兵力");

        ServerPlayer commander = findCommanderForTeam(bastionTeam);
        if (commander != null) {
            commander.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c你的兵站 §e" + bastionName + " §c已被摧毁！"
            ));
        }
    }

    /**
     * 玩家选择兵站复活
     */
    public boolean selectBastion(ServerLevel level, UUID playerId, UUID bastionId) {
        BastionData bastion = bastions.get(bastionId);
        if (bastion == null || !bastion.isActive()) {
            return false;
        }

        // 检查玩家是否正在等待选择兵站
        if (!waitingPlayers.containsKey(playerId)) {
            return false;
        }
        waitingPlayers.remove(playerId);

        return true;
    }

    /**
     * 玩家死亡时，设置为等待兵站选择状态
     */
    public void onPlayerDeath(ServerLevel level, UUID playerId) {
        waitingPlayers.put(playerId, UUID.randomUUID());
    }

    /**
     * 检查玩家是否在等待兵站选择
     */
    public boolean isWaitingForBastion(UUID playerId) {
        return waitingPlayers.containsKey(playerId);
    }

    /**
     * 移除等待状态
     */
    public void clearWaiting(UUID playerId) {
        waitingPlayers.remove(playerId);
        unlockPlayerPosition(playerId);
    }

    /**
     * 锁定玩家位置（等待复活选择时不可移动）
     */
    public void lockPlayerPosition(UUID playerId, net.minecraft.world.phys.Vec3 pos) {
        playerLockPositions.put(playerId, pos);
    }

    /**
     * 解锁玩家位置
     */
    public void unlockPlayerPosition(UUID playerId) {
        playerLockPositions.remove(playerId);
    }

    /**
     * 获取玩家锁定位置
     */
    @Nullable
    public net.minecraft.world.phys.Vec3 getPlayerLockPosition(UUID playerId) {
        return playerLockPositions.get(playerId);
    }

    /**
     * 移除无效兵站
     */
    public void removeInvalidBastions() {
        List<UUID> toRemove = new ArrayList<>();
        for (BastionData bastion : bastions.values()) {
            if (!bastion.isActive()) {
                toRemove.add(bastion.getBastionId());
            } else if (bastion.checkArmorStand()) {
                updateBastionArmorStandPosition(bastion, bastion.getArmorStandPosition());
            } else {
                ensureCoreArmorStand(bastion);
            }
        }
        for (UUID id : toRemove) {
            bastions.remove(id);
        }
    }

    /**
     * 激活玩家的兵站选择状态
     */
    public void activatePlayerBastionSelection(UUID playerId) {
        waitingPlayers.put(playerId, UUID.randomUUID());
    }

    /**
     * 重置所有兵站（清空所有数据记录，不持久化）
     */
    public void reset() {
        // 移除所有盔甲架（强制加载区块）
        for (BastionData bastion : bastions.values()) {
            releaseBastionRecord(bastion);
            if (bastion.getArmorStandId() != null) {
                BlockPos entityPos = bastion.getArmorStandPosition();
                if (entityPos == null) entityPos = bastion.getPosition().above();
                bastion.getLevel().getChunkAt(entityPos);
                Entity entity = bastion.getLevel().getEntity(bastion.getArmorStandId());
                if (entity != null) {
                    entity.kill();
                }
            }
        }
        bastions.clear();
        waitingPlayers.clear();
        playerDeployPoints.clear();
        bastionCooldowns.clear();
        resupplyCooldowns.clear();
        resupplyCounts.clear();
        clearBastionRecords();
    }

    /**
     * 获取当前已占用的兵站记录槽数量。
     */
    public int getActiveBastionCount() {
        return (int) bastions.values().stream()
            .filter(BastionData::isActive)
            .count();
    }

    /**
     * 是否还有可用的兵站记录槽。
     */
    public boolean hasBastionCapacity() {
        return getActiveBastionCount() < MAX_BASTIONS;
    }

    /**
     * 获取兵站记录的盔甲架坐标。部署传送只依赖该坐标，不依赖强加载区块。
     */
    @Nullable
    public BlockPos getRecordedArmorStandPosition(BastionData bastion) {
        int slot = bastion.getBastionNumber() - 1;
        if (isOwnedSlot(slot, bastion.getBastionId())) {
            BlockPos slotPos = bastionSlotPositions[slot];
            if (slotPos != null) {
                return slotPos;
            }
        }
        BlockPos recorded = bastion.getArmorStandPosition();
        return recorded != null ? recorded : bastion.getPosition().above();
    }

    /**
     * 更新兵站盔甲架坐标，并同步到对应编号槽。
     */
    public void updateBastionArmorStandPosition(BastionData bastion, BlockPos pos) {
        bastion.setArmorStandPosition(pos);
        int slot = bastion.getBastionNumber() - 1;
        if (isOwnedSlot(slot, bastion.getBastionId())) {
            bastionSlotPositions[slot] = pos;
        }
    }

    private boolean isBastionUsable(BastionData bastion) {
        if (!bastion.isActive()) {
            return false;
        }
        if (findBastionSlot(bastion.getBastionId()) < 0 && !registerBastionRecord(bastion)) {
            return false;
        }
        if (bastion.checkArmorStand()) {
            BlockPos armorStandPos = bastion.getArmorStandPosition();
            if (armorStandPos != null) {
                updateBastionArmorStandPosition(bastion, armorStandPos);
            }
        } else {
            ensureCoreArmorStand(bastion);
        }
        return getRecordedArmorStandPosition(bastion) != null;
    }

    public boolean ensureCoreArmorStand(BastionData bastion) {
        if (bastion == null || !bastion.isActive()) {
            return false;
        }

        if (bastion.getArmorStandId() != null) {
            Entity entity = bastion.getLevel().getEntity(bastion.getArmorStandId());
            if (entity instanceof ArmorStand armorStand && armorStand.isAlive()) {
                syncCoreArmorStand(armorStand);
                updateBastionArmorStandPosition(bastion, armorStand.blockPosition());
                return true;
            }
        }

        BlockPos corePos = getRecordedArmorStandPosition(bastion);
        if (corePos == null || !bastion.getLevel().hasChunkAt(corePos)) {
            return false;
        }

        ArmorStand armorStand = createCoreArmorStand(bastion.getLevel(), corePos, bastion.getTeam(), bastion.getName());
        if (armorStand == null) {
            return false;
        }

        float visualHealth = Math.max(1.0F, Math.min(bastion.getCoreHealth(), armorStandHealth));
        armorStand.setHealth(visualHealth);
        bastion.getLevel().addFreshEntity(armorStand);
        bastion.setArmorStandId(armorStand.getUUID());
        updateBastionArmorStandPosition(bastion, armorStand.blockPosition());
        bastion.resetMissingEntityTicks();

        Espetro.LOGGER.info("兵站 {} 的核心盔甲架缺失，已在记录位置 {} 重建", bastion.getName(), corePos);
        return true;
    }

    /**
     * 传送前的非加载检查。只接受当前已加载区块，避免 ServerPlayer.teleportTo
     * 在主线程同步等待远处区块生成/读取，复现旧强加载实现的卡死路径。
     */
    public boolean isTeleportTargetLoaded(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        return level.getChunkSource().hasChunk(chunkPos.x, chunkPos.z);
    }

    @Nullable
    private ArmorStand createCoreArmorStand(ServerLevel level, BlockPos corePos, String team, String name) {
        ArmorStand armorStand = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(level);
        if (armorStand == null) {
            return null;
        }

        armorStand.setPos(corePos.getX() + 0.5, corePos.getY(), corePos.getZ() + 0.5);
        armorStand.setCustomName(net.minecraft.network.chat.Component.literal(name));
        armorStand.setCustomNameVisible(false);
        syncCoreArmorStand(armorStand);
        armorStand.setHealth(armorStandHealth);

        ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
        CompoundTag displayTag = new CompoundTag();
        displayTag.putInt("color", "ATTACK".equals(team) ? 0xAA0000 : 0x0000AA);
        CompoundTag tag = new CompoundTag();
        tag.put("display", displayTag);
        helmet.setTag(tag);
        armorStand.setItemSlot(EquipmentSlot.HEAD, helmet);

        return armorStand;
    }

    void syncCoreArmorStand(ArmorStand armorStand) {
        AttributeInstance maxHealth = armorStand.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getBaseValue() != armorStandHealth) {
            maxHealth.setBaseValue(armorStandHealth);
        }
        armorStand.setInvulnerable(false);
        armorStand.setSilent(true);
        armorStand.addTag("bastion_armor_stand");
    }

    @Nullable
    private ServerPlayer findCommanderForTeam(String team) {
        var server = Espetro.getServer();
        if (server == null) return null;

        var voteManager = org.espetro.team.VoteManager.getInstance();
        java.util.UUID commanderId = "ATTACK".equals(team) ?
            voteManager.getAttackCommander() : voteManager.getDefendCommander();

        if (commanderId != null) {
            return server.getPlayerList().getPlayer(commanderId);
        }
        return null;
    }

    private boolean registerBastionRecord(BastionData bastion) {
        int existingSlot = findBastionSlot(bastion.getBastionId());
        if (existingSlot >= 0) {
            occupyBastionSlot(bastion, existingSlot);
            return true;
        }

        int savedSlot = bastion.getBastionNumber() - 1;
        if (isFreeSlot(savedSlot)) {
            occupyBastionSlot(bastion, savedSlot);
            return true;
        }

        int startSlot = hashBastionId(bastion.getBastionId());
        for (int offset = 0; offset < MAX_BASTIONS; offset++) {
            int slot = (startSlot + offset) % MAX_BASTIONS;
            if (isFreeSlot(slot)) {
                occupyBastionSlot(bastion, slot);
                return true;
            }
        }
        return false;
    }

    private void occupyBastionSlot(BastionData bastion, int slot) {
        BlockPos armorStandPos = bastion.getArmorStandPosition();
        if (armorStandPos == null) {
            armorStandPos = bastion.getPosition().above();
            bastion.setArmorStandPosition(armorStandPos);
        }

        bastionSlots[slot] = bastion.getBastionId();
        bastionSlotPositions[slot] = armorStandPos;
        bastion.setBastionNumber(slot + 1);
    }

    private void releaseBastionRecord(BastionData bastion) {
        int slot = findBastionSlot(bastion.getBastionId());
        if (slot >= 0) {
            bastionSlots[slot] = null;
            bastionSlotPositions[slot] = null;
            Espetro.LOGGER.debug("释放兵站编号槽 {}: {}", slot + 1, bastion.getName());
        }
        bastion.setBastionNumber(-1);
        bastion.clearArmorStandPosition();
    }

    private void clearBastionRecords() {
        Arrays.fill(bastionSlots, null);
        Arrays.fill(bastionSlotPositions, null);
    }

    private int hashBastionId(UUID bastionId) {
        return Math.floorMod(bastionId.hashCode(), MAX_BASTIONS);
    }

    private int findBastionSlot(UUID bastionId) {
        for (int i = 0; i < MAX_BASTIONS; i++) {
            if (bastionId.equals(bastionSlots[i])) {
                return i;
            }
        }
        return -1;
    }

    private boolean isFreeSlot(int slot) {
        return slot >= 0 && slot < MAX_BASTIONS && bastionSlots[slot] == null;
    }

    private boolean isOwnedSlot(int slot, UUID bastionId) {
        return slot >= 0
            && slot < MAX_BASTIONS
            && bastionId.equals(bastionSlots[slot]);
    }

    /**
     * 检查玩家是否在建造冷却中
     * @return 剩余冷却秒数，0表示无冷却
     */
    public int getBastionCooldownRemaining(UUID playerId) {
        Long lastUse = bastionCooldowns.get(playerId);
        if (lastUse == null) {
            return 0;
        }

        long elapsed = System.currentTimeMillis() - lastUse;
        int remaining = (int) ((cooldownSeconds * 1000L - elapsed) / 1000L);
        return Math.max(0, remaining);
    }

    /**
     * 设置玩家建造冷却
     */
    public void setBastionCooldown(UUID playerId) {
        bastionCooldowns.put(playerId, System.currentTimeMillis());
    }

    /**
     * 检查玩家是否可以建造兵站
     * @return null表示可以，String表示不能的原因
     */
    @Nullable
    public String canBuildBastion(UUID playerId) {
        int remaining = getBastionCooldownRemaining(playerId);
        if (remaining > 0) {
            return "§c兵站建造冷却中！请等待 " + remaining + " 秒后再试。";
        }
        return null;
    }

    // ==================== 弹药补给 ====================

    /**
     * 尝试补给弹药
     * @return null表示成功，String表示失败原因
     */
    @Nullable
    public String tryResupply(UUID playerId) {
        return tryResupply(playerId, -1);
    }

    /**
     * 尝试补给弹药。
     * @param maxResupplies 最大补给次数；小于等于0表示无限制
     * @return null表示成功，String表示失败原因
     */
    @Nullable
    public String tryResupply(UUID playerId, int maxResupplies) {
        if (maxResupplies > 0) {
            int used = getResupplyCount(playerId);
            if (used >= maxResupplies) {
                return "§c本次生命的弹药补给次数已用完！(" + used + "/" + maxResupplies + ")";
            }
        }

        // 检查冷却
        Long lastResupply = resupplyCooldowns.get(playerId);
        if (lastResupply != null) {
            long remaining = RESUPPLY_COOLDOWN_MS - (System.currentTimeMillis() - lastResupply);
            if (remaining > 0) {
                int sec = (int) (remaining / 1000);
                int min = sec / 60;
                sec %= 60;
                return "§c弹药补给冷却中！剩余 " + min + "分" + sec + "秒";
            }
        }
        return null;
    }

    /**
     * 记录补给成功
     */
    public void recordResupply(UUID playerId) {
        resupplyCooldowns.put(playerId, System.currentTimeMillis());
        resupplyCounts.merge(playerId, 1, Integer::sum);
    }

    /**
     * 获取玩家剩余补给次数（-1 表示无限制）
     */
    public int getResupplyCount(UUID playerId) {
        return resupplyCounts.getOrDefault(playerId, 0);
    }

    /**
     * 重置玩家补给次数（死亡时调用）
     */
    public void resetResupplyCount(UUID playerId) {
        resupplyCounts.remove(playerId);
    }

    /**
     * 获取玩家补给冷却剩余秒数
     */
    public int getResupplyCooldownRemaining(UUID playerId) {
        Long last = resupplyCooldowns.get(playerId);
        if (last == null) return 0;
        return (int) Math.max(0, (RESUPPLY_COOLDOWN_MS - (System.currentTimeMillis() - last)) / 1000);
    }

    /**
     * 根据潜影盒位置查找对应兵站
     */
    @Nullable
    public BastionData findBastionByShulkerPos(BlockPos pos) {
        for (BastionData b : bastions.values()) {
            if (b.isActive() && pos.equals(b.getShulkerPos())) {
                return b;
            }
        }
        return null;
    }

    /**
     * 保存玩家的原部署点位置
     */
    public void savePlayerDeployPoint(ServerPlayer player) {
        BlockPos bedPos = player.getRespawnPosition();
        BlockPos spawnPos = player.server.overworld().getSharedSpawnPos();

        BlockPos deployPos;
        if (bedPos != null) {
            deployPos = bedPos;
        } else {
            deployPos = spawnPos;
        }

        playerDeployPoints.put(player.getUUID(), new DeployPoint(deployPos, player.server.overworld()));
    }

    /**
     * 保存玩家指定的部署点位置（用于战局中加入）
     */
    public void savePlayerDeployPoint(ServerPlayer player, BlockPos pos, ServerLevel level) {
        playerDeployPoints.put(player.getUUID(), new DeployPoint(pos, level));
    }

    /**
     * 获取玩家的原部署点
     */
    @Nullable
    public DeployPoint getPlayerDeployPoint(UUID playerId) {
        return playerDeployPoints.get(playerId);
    }

    /**
     * 在原部署点复活玩家
     */
    public boolean respawnAtDeployPoint(ServerLevel level, ServerPlayer player) {
        DeployPoint deployPoint = playerDeployPoints.get(player.getUUID());
        if (deployPoint == null) {
            return false;
        }

        if (!isTeleportTargetLoaded(deployPoint.level, deployPoint.pos)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c原部署点所在区块尚未加载，已取消本次复活以避免服务器卡顿。请靠近该区域或稍后重试。"
            ));
            Espetro.LOGGER.warn("拒绝将玩家 {} 传送到未加载原部署点区块: {} ({})",
                player.getName().getString(), deployPoint.pos, deployPoint.level.dimension().location());
            return false;
        }

        // 清除等待状态
        clearWaiting(player.getUUID());

        // 传送玩家到原部署点
        player.teleportTo(deployPoint.level, deployPoint.pos.getX() + 0.5, deployPoint.pos.getY() + 0.1, deployPoint.pos.getZ() + 0.5, 0f, 0f);

        // 设置生存模式
        player.setGameMode(GameType.SURVIVAL);

        // 移除所有效果
        player.removeAllEffects();

        // 给予短暂的无敌效果
        int invincibilityTicks = org.espetro.config.GameConfig.getRespawnInvincibilityTicks();
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
            invincibilityTicks,
            127, // 最大等级
            false, false, false
        ));

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a已在原部署点复活！"));

        return true;
    }

    /**
     * 原部署点数据类
     */
    public static class DeployPoint {
        public final BlockPos pos;
        public final ServerLevel level;

        public DeployPoint(BlockPos pos, ServerLevel level) {
            this.pos = pos;
            this.level = level;
        }
    }
}
