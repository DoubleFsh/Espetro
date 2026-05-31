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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
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

    private static BastionManager INSTANCE;

    // 所有兵站列表
    private final Map<UUID, BastionData> bastions = new HashMap<>();

    // 正在等待复活选择的玩家
    private final Map<UUID, UUID> waitingPlayers = new HashMap<>(); // playerUUID -> bastionChoiceRequestId

    // 玩家的原部署点位置
    private final Map<UUID, DeployPoint> playerDeployPoints = new HashMap<>(); // playerUUID -> DeployPoint

    // 玩家建造冷却记录
    private final Map<UUID, Long> bastionCooldowns = new HashMap<>(); // playerUUID -> lastUseTimestamp (毫秒)

    // 玩家位置锁定（等待复活选择时）
    private final Map<UUID, net.minecraft.world.phys.Vec3> playerLockPositions = new HashMap<>();

    // 从 JSON 配置读取的值
    private int cooldownSeconds = 60;
    private int requiredPlanks = 640;
    private int armorStandHealth = 500;
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
                    armorStandHealth = bastion.get("armor_stand_health").getAsInt();
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
        // 创建盔甲架实体
        ArmorStand armorStand = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(level);
        if (armorStand == null) {
            Espetro.LOGGER.error("无法创建盔甲架实体");
            return null;
        }

        // 设置盔甲架位置（在兵站中心上方1格）
        armorStand.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        armorStand.setCustomName(net.minecraft.network.chat.Component.literal(name));
        armorStand.setCustomNameVisible(false);
        armorStand.setHealth(armorStandHealth); // 使用配置的血量
        armorStand.setInvulnerable(false);
        armorStand.setSilent(true);
        armorStand.addTag("bastion_armor_stand");

        // 根据队伍装备对应颜色的皮革头盔
        ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
        CompoundTag displayTag = new CompoundTag();
        displayTag.putInt("color", "ATTACK".equals(team) ? 0xAA0000 : 0x0000AA);
        CompoundTag tag = new CompoundTag();
        tag.put("display", displayTag);
        helmet.setTag(tag);
        armorStand.setItemSlot(EquipmentSlot.HEAD, helmet);

        // 生成并添加到世界
        level.addFreshEntity(armorStand);

        // 创建兵站数据
        BastionData bastion = new BastionData(team, name, pos, level);
        bastion.setArmorStandId(armorStand.getUUID());
        bastion.setActive(true);

        bastions.put(bastion.getBastionId(), bastion);

        Espetro.LOGGER.info("创建兵站: {} (队伍: {}, 位置: {}, 盔甲架ID: {})",
            name, team, pos, armorStand.getUUID());

        return bastion;
    }

    /**
     * 获取玩家所属队伍的兵站列表
     */
    public List<BastionData> getTeamBastions(String team) {
        return bastions.values().stream()
            .filter(b -> b.getTeam().equals(team) && b.isActive() && b.checkArmorStand())
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
     * 获取指定ID的兵站
     */
    @Nullable
    public BastionData getBastion(UUID bastionId) {
        return bastions.get(bastionId);
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
            if (!bastion.checkArmorStand()) {
                bastion.setActive(false);
                Espetro.LOGGER.info("兵站 {} 的盔甲架已失效，兵站被移除", bastion.getName());
                toRemove.add(bastion.getBastionId());
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
     * 保存所有兵站数据
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (BastionData bastion : bastions.values()) {
            list.add(bastion.save());
        }
        tag.put("bastions", list);
        return tag;
    }

    /**
     * 加载所有兵站数据
     */
    public void load(CompoundTag tag, ServerLevel level) {
        bastions.clear();
        ListTag list = tag.getList("bastions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag bastionTag = list.getCompound(i);
            BastionData bastion = BastionData.load(bastionTag, level);
            bastions.put(bastion.getBastionId(), bastion);
        }
        Espetro.LOGGER.info("加载了 {} 个兵站", bastions.size());
    }

    /**
     * 重置所有兵站
     */
    public void reset() {
        // 移除所有盔甲架
        for (BastionData bastion : bastions.values()) {
            if (bastion.getArmorStandId() != null) {
                Entity entity = bastion.getLevel().getEntity(bastion.getArmorStandId());
                if (entity != null) {
                    entity.discard();
                }
            }
        }
        bastions.clear();
        waitingPlayers.clear();
        playerDeployPoints.clear();
        bastionCooldowns.clear();
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
