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

/**
 * 兵站管理器
 * 管理所有兵站的创建、存储和查询
 */
public class BastionManager {

    /** 每个队伍最多同时生效的兵站数量 */
    public static final int MAX_BASTIONS = 4;
    /** 兼容旧调用，实际含义为每个队伍的上限。 */
    @Deprecated
    public static final int MAX_BASTIONS_PER_TEAM = MAX_BASTIONS;
    private static BastionManager INSTANCE;

    // 所有兵站列表
    private final Map<UUID, BastionData> bastions = new HashMap<>();

    // 兵站记录表：部署传送只依赖记录坐标，不依赖区块当前是否加载。
    private final Map<UUID, BlockPos> bastionRecordPositions = new HashMap<>();

    // 正在等待复活选择的玩家
    private final Map<UUID, UUID> waitingPlayers = new HashMap<>(); // playerUUID -> bastionChoiceRequestId

    // 玩家的原部署点位置
    private final Map<UUID, DeployPoint> playerDeployPoints = new HashMap<>(); // playerUUID -> DeployPoint

    // 玩家建造冷却记录
    private final Map<UUID, Long> bastionCooldowns = new HashMap<>(); // playerUUID -> lastUseTimestamp (毫秒)

    // 玩家位置锁定（等待复活选择时）
    private final Map<UUID, net.minecraft.world.phys.Vec3> playerLockPositions = new HashMap<>();

    // 弹药补给追踪（仅冷却，无次数限制）
    private final Map<UUID, Long> resupplyCooldowns = new HashMap<>(); // playerUUID -> 最后补给时间戳

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
            var resourceOptional = org.espetro.data.EspetroDataResources.getPreferred(server.getResourceManager(), location);

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
        if (!hasBastionCapacity(team)) {
            Espetro.LOGGER.warn("队伍 {} 的生效兵站数量已达到上限 {}，拒绝创建: {} ({})",
                team, MAX_BASTIONS, name, pos);
            return null;
        }

        BastionData bastion = new BastionData(team, name, pos, level);
        bastion.setArmorStandPosition(pos.above());

        if (!registerBastionRecord(bastion)) {
            Espetro.LOGGER.warn("兵站记录失败，拒绝创建: {} ({})", name, pos);
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

        Espetro.LOGGER.info("创建兵站: {} (队伍: {}, 编号: {}, 盔甲架位置: {}, 盔甲架ID: {})",
            name, team, bastion.getBastionNumber(), bastion.getArmorStandPosition(), armorStand.getUUID());

        return bastion;
    }

    /**
     * 获取玩家所属队伍的兵站列表
     */
    public List<BastionData> getTeamBastions(String team) {
        List<BastionData> result = new ArrayList<>(MAX_BASTIONS);
        for (BastionData bastion : bastions.values()) {
            if (bastion.getTeam().equals(team) && isBastionUsable(bastion)) {
                result.add(bastion);
            }
        }
        result.sort(Comparator.comparing(BastionData::getName));
        return result;
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
     * 设置兵站启用状态。兵站失效时同步清空记录坐标。
     */
    public void setBastionActive(BastionData bastion, boolean active) {
        if (!active) {
            releaseBastionRecord(bastion);
            bastion.setActive(false);
            return;
        }

        if (!bastion.isActive() && !hasBastionCapacity(bastion.getTeam())) {
            bastion.setActive(false);
            Espetro.LOGGER.warn("兵站 {} 无法重新激活：队伍 {} 的生效兵站数量已达上限",
                bastion.getName(), bastion.getTeam());
            return;
        }

        if (!registerBastionRecord(bastion)) {
            bastion.setActive(false);
            Espetro.LOGGER.warn("兵站 {} 无法重新激活：记录坐标失败", bastion.getName());
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

        removeCoreEntityIfLoaded(bastion, true);

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
        String enemyTeam = "ATTACK".equals(bastionTeam) ? "DEFEND" : "ATTACK";
        Espetro.broadcastToTeam(enemyTeam, "§a[兵站] 敌方兵站 §e" + bastionName + " §a已被摧毁！敌方 -" + penalty + " 兵力");

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
        Iterator<BastionData> iterator = bastions.values().iterator();
        while (iterator.hasNext()) {
            BastionData bastion = iterator.next();
            if (!bastion.isActive()) {
                iterator.remove();
            } else if (bastion.checkArmorStand()) {
                updateBastionArmorStandPosition(bastion, bastion.getArmorStandPosition());
            } else {
                ensureCoreArmorStand(bastion);
            }
        }
    }

    /**
     * 激活玩家的兵站选择状态
     */
    public void activatePlayerBastionSelection(UUID playerId) {
        waitingPlayers.put(playerId, UUID.randomUUID());
    }

    /**
     * 重置所有兵站。只清理已加载区块内的核心实体，避免重置或退出世界时同步加载远处区块。
     */
    public void reset() {
        reset(true);
    }

    /**
     * 仅清理运行时状态，不访问世界实体。用于服务器已停止后的兜底清理。
     */
    public void clearRuntimeState() {
        reset(false);
    }

    private void reset(boolean removeLoadedEntities) {
        for (BastionData bastion : bastions.values()) {
            if (removeLoadedEntities) {
                removeCoreEntityIfLoaded(bastion, false);
            }
            releaseBastionRecord(bastion);
        }
        bastions.clear();
        waitingPlayers.clear();
        playerDeployPoints.clear();
        bastionCooldowns.clear();
        resupplyCooldowns.clear();
        clearBastionRecords();
    }

    private void removeCoreEntityIfLoaded(BastionData bastion, boolean kill) {
        if (bastion == null || bastion.getArmorStandId() == null) {
            return;
        }

        BlockPos entityPos = bastion.getArmorStandPosition();
        if (entityPos == null) {
            entityPos = bastion.getPosition().above();
        }

        ServerLevel level = bastion.getLevel();
        if (level == null || !isChunkLoaded(level, entityPos)) {
            return;
        }

        Entity entity = level.getEntity(bastion.getArmorStandId());
        if (entity == null) {
            return;
        }

        if (kill) {
            entity.kill();
        } else {
            entity.discard();
        }
    }

    /**
     * 获取当前所有队伍生效兵站数量。
     */
    public int getActiveBastionCount() {
        int count = 0;
        for (BastionData bastion : bastions.values()) {
            if (bastion.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取指定队伍当前生效兵站数量。
     */
    public int getActiveBastionCount(String team) {
        int count = 0;
        for (BastionData bastion : bastions.values()) {
            if (bastion.isActive() && Objects.equals(team, bastion.getTeam())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 兼容旧调用：只检查所有队伍总数。
     */
    @Deprecated
    public boolean hasBastionCapacity() {
        return getActiveBastionCount() < MAX_BASTIONS;
    }

    /**
     * 指定队伍是否还能建造兵站。上限只统计当前场上同队伍生效兵站。
     */
    public boolean hasBastionCapacity(String team) {
        return getActiveBastionCount(team) < MAX_BASTIONS;
    }

    /**
     * 获取兵站记录的盔甲架坐标。部署传送只依赖该坐标，不依赖强加载区块。
     */
    @Nullable
    public BlockPos getRecordedArmorStandPosition(BastionData bastion) {
        BlockPos recordPos = bastionRecordPositions.get(bastion.getBastionId());
        if (recordPos != null) {
            return recordPos;
        }
        BlockPos recorded = bastion.getArmorStandPosition();
        return recorded != null ? recorded : bastion.getPosition().above();
    }

    /**
     * 更新兵站盔甲架坐标，并同步到记录表。
     */
    public void updateBastionArmorStandPosition(BastionData bastion, BlockPos pos) {
        bastion.setArmorStandPosition(pos);
        bastionRecordPositions.put(bastion.getBastionId(), pos);
    }

    private boolean isBastionUsable(BastionData bastion) {
        if (!bastion.isActive()) {
            return false;
        }
        if (!bastionRecordPositions.containsKey(bastion.getBastionId()) && !registerBastionRecord(bastion)) {
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

    private boolean isChunkLoaded(ServerLevel level, BlockPos pos) {
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
        BlockPos armorStandPos = bastion.getArmorStandPosition();
        if (armorStandPos == null) {
            armorStandPos = bastion.getPosition().above();
            bastion.setArmorStandPosition(armorStandPos);
        }

        bastionRecordPositions.put(bastion.getBastionId(), armorStandPos);
        bastion.setBastionNumber(findAvailableBastionNumber(bastion));
        return true;
    }

    private void releaseBastionRecord(BastionData bastion) {
        bastionRecordPositions.remove(bastion.getBastionId());
        Espetro.LOGGER.debug("释放兵站记录: {}", bastion.getName());
        bastion.setBastionNumber(-1);
        bastion.clearArmorStandPosition();
    }

    private void clearBastionRecords() {
        bastionRecordPositions.clear();
    }

    private int findAvailableBastionNumber(BastionData bastion) {
        boolean[] used = new boolean[MAX_BASTIONS + 1];
        for (BastionData other : bastions.values()) {
            if (other.getBastionId().equals(bastion.getBastionId())) {
                continue;
            }
            if (!other.isActive() || !Objects.equals(other.getTeam(), bastion.getTeam())) {
                continue;
            }
            int number = other.getBastionNumber();
            if (number >= 1 && number <= MAX_BASTIONS) {
                used[number] = true;
            }
        }

        int savedNumber = bastion.getBastionNumber();
        if (savedNumber >= 1 && savedNumber <= MAX_BASTIONS && !used[savedNumber]) {
            return savedNumber;
        }

        for (int number = 1; number <= MAX_BASTIONS; number++) {
            if (!used[number]) {
                return number;
            }
        }
        return MAX_BASTIONS;
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

    // ==================== 弹药补给（仅冷却，无次数限制）====================

    /**
     * 尝试补给弹药（仅检查冷却）
     * @return null表示成功，String表示失败原因
     */
    @Nullable
    public String tryResupply(UUID playerId) {
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
     * 记录补给成功（更新冷却时间）
     */
    public void recordResupply(UUID playerId) {
        resupplyCooldowns.put(playerId, System.currentTimeMillis());
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

        // 清除等待状态
        clearWaiting(player.getUUID());

        // 传送玩家到原部署点。该坐标由队伍复活点 JSON 配置保存，不再因目标区块未加载而取消。
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
