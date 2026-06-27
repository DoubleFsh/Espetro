package org.espetro.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import org.espetro.Espetro;
import org.espetro.bastion.BastionManager;
import org.espetro.team.ClassCountManager;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 载具管理器
 * 管理载具部署、追踪、冷却和复活
 */
public class VehicleManager {

    private static VehicleManager INSTANCE;

    // factionId -> (vehicleType -> List<UUID>) 追踪活跃载具
    private final Map<String, Map<String, List<UUID>>> activeVehicles = new HashMap<>();

    // factionId -> (vehicleType -> spawnTimeMillis) 刷新冷却
    private final Map<String, Map<String, Long>> cooldowns = new HashMap<>();

    private VehicleManager() {
        INSTANCE = this;
    }

    public static VehicleManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new VehicleManager();
        }
        return INSTANCE;
    }

    /**
     * 获取指定编制指定类型的活跃载具数量
     */
    public int getActiveCount(String factionId, String vehicleType) {
        return getList(factionId, vehicleType).size();
    }

    /**
     * 获取指定编制某类型载具列表
     */
    private List<UUID> getList(String factionId, String vehicleType) {
        return activeVehicles
            .computeIfAbsent(factionId, k -> new HashMap<>())
            .computeIfAbsent(vehicleType, k -> new ArrayList<>());
    }

    /**
     * 获取冷却剩余毫秒数，0表示无冷却
     */
    public long getCooldownRemaining(String factionId, String vehicleType) {
        Long lastSpawn = cooldowns
            .computeIfAbsent(factionId, k -> new HashMap<>())
            .get(vehicleType);
        if (lastSpawn == null) return 0;

        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (cfg == null) return 0;

        long elapsed = System.currentTimeMillis() - lastSpawn;
        long remaining = cfg.respawnMillis() - elapsed;
        return Math.max(0, remaining);
    }

    /**
     * 指挥官部署载具
     * @return null 表示成功，否则返回错误消息
     */
    @Nullable
    public String deployVehicle(ServerPlayer commander, String vehicleType) {
        // 获取指挥官编制
        String factionId = ClassCountManager.getInstance().getPlayerFaction(commander.getUUID());
        if (factionId == null) {
            return "§c你没有选择编制！";
        }

        // 检查编制是否配置了该载具
        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (cfg == null) {
            return "§c当前编制不支持部署此载具类型！";
        }

        // 检查部署上限
        int current = getActiveCount(factionId, vehicleType);
        if (current >= cfg.max) {
            return "§c" + getDisplayName(factionId, vehicleType) + " 已达到部署上限！(" + current + "/" + cfg.max + ")";
        }

        // 检查冷却
        long cooldownRemaining = getCooldownRemaining(factionId, vehicleType);
        if (cooldownRemaining > 0) {
            long seconds = cooldownRemaining / 1000;
            return "§c" + getDisplayName(factionId, vehicleType) + " 刷新冷却中！剩余 " + seconds + " 秒。";
        }

        ServerLevel level = resolveDeployLevel(commander, cfg);
        BlockPos spawnPos = resolveSpawnPosition(level, cfg, commander, null);
        if (spawnPos == null) {
            return "§c无法解析载具部署位置，或部署点附近没有合适的位置！";
        }

        // 创建载具实体
        Entity vehicleEntity = createVehicleEntity(level, vehicleType, spawnPos, factionId, cfg);
        if (vehicleEntity == null) {
            return "§c创建载具实体失败！";
        }

        level.addFreshEntity(vehicleEntity);

        // 记录
        getList(factionId, vehicleType).add(vehicleEntity.getUUID());

        // 设置冷却
        cooldowns.computeIfAbsent(factionId, k -> new HashMap<>()).put(vehicleType, System.currentTimeMillis());

        commander.sendSystemMessage(Component.literal(
            "§a已部署 " + getDisplayName(factionId, vehicleType) + " §a！(" + (current + 1) + "/" + cfg.max + ") §7位置: " +
            spawnPos.getX() + " " + spawnPos.getY() + " " + spawnPos.getZ()));

        Espetro.LOGGER.info("指挥官 {} 部署载具: {} (编制: {}, 位置: {})",
            commander.getName().getString(), vehicleType, factionId, spawnPos);

        return null;
    }

    /**
     * 部署阶段开始时，为本局选中的编制预先部署每种已配置载具各一辆。
     * 该入口不向聊天栏发送消息；生成的载具会写入 activeVehicles，从而占用部署上限。
     *
     * @return 成功预部署的载具数量
     */
    public int deployInitialVehicles(String factionId, ServerLevel level, BlockPos deployPointBase) {
        Map<String, VehicleConfig.VehicleTypeConfig> configs = VehicleConfig.getFactionVehicles(factionId);
        if (configs.isEmpty()) {
            return 0;
        }

        int deployed = 0;
        for (Map.Entry<String, VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String vehicleType = entry.getKey();
            VehicleConfig.VehicleTypeConfig cfg = entry.getValue();

            int current = getActiveCount(factionId, vehicleType);
            if (current >= cfg.max) {
                Espetro.LOGGER.debug("初始载具预部署跳过: {} / {} 已达上限 ({}/{})",
                    factionId, vehicleType, current, cfg.max);
                continue;
            }

            BlockPos spawnPos = resolveSpawnPosition(level, cfg, null, deployPointBase);
            if (spawnPos == null) {
                Espetro.LOGGER.warn("初始载具预部署失败: 无法解析 {} / {} 的部署位置", factionId, vehicleType);
                continue;
            }

            Entity vehicleEntity = createVehicleEntity(level, vehicleType, spawnPos, factionId, cfg);
            if (vehicleEntity == null) {
                Espetro.LOGGER.warn("初始载具预部署失败: 无法创建 {} / {} 的实体", factionId, vehicleType);
                continue;
            }

            level.addFreshEntity(vehicleEntity);
            getList(factionId, vehicleType).add(vehicleEntity.getUUID());
            deployed++;

            Espetro.LOGGER.info("初始载具已预部署: {} / {} 位置: {} ({}/{})",
                factionId, vehicleType, spawnPos, current + 1, cfg.max);
        }

        return deployed;
    }

    /**
     * 移除已死亡的载具追踪
     */
    public void onVehicleDeath(UUID entityId) {
        for (Map.Entry<String, Map<String, List<UUID>>> factionEntry : activeVehicles.entrySet()) {
            for (Map.Entry<String, List<UUID>> typeEntry : factionEntry.getValue().entrySet()) {
                if (typeEntry.getValue().remove(entityId)) {
                    Espetro.LOGGER.debug("载具 {} 已移除追踪", entityId);
                    return;
                }
            }
        }
    }

    /**
     * 检查某个实体是否是我们追踪的载具
     */
    public boolean isTrackedVehicle(UUID entityId) {
        for (Map<String, List<UUID>> typeMap : activeVehicles.values()) {
            for (List<UUID> list : typeMap.values()) {
                if (list.contains(entityId)) return true;
            }
        }
        return false;
    }

    /**
     * 重置所有载具
     */
    public void reset() {
        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            List<UUID> trackedIds = new ArrayList<>();
            for (Map<String, List<UUID>> typeMap : activeVehicles.values()) {
                for (List<UUID> list : typeMap.values()) {
                    trackedIds.addAll(list);
                }
            }

            for (UUID id : trackedIds) {
                Entity entity = server.overworld().getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
        }
        activeVehicles.clear();
        cooldowns.clear();
    }

    /**
     * 清理不再存在的载具记录，避免实体被外部命令移除后仍占用上限。
     */
    public void removeInvalidVehicles() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        for (Map<String, List<UUID>> typeMap : activeVehicles.values()) {
            for (List<UUID> list : typeMap.values()) {
                list.removeIf(id -> {
                    Entity entity = server.overworld().getEntity(id);
                    if (entity == null) {
                        return true;
                    }
                    return entity.isRemoved();
                });
            }
        }
    }

    /**
     * 获取某编制的载具状态摘要
     */
    public List<String> getFactionVehicleStatus(String factionId) {
        List<String> result = new ArrayList<>();
        Map<String, VehicleConfig.VehicleTypeConfig> configs = VehicleConfig.getFactionVehicles(factionId);

        for (Map.Entry<String, VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String type = entry.getKey();
            VehicleConfig.VehicleTypeConfig cfg = entry.getValue();
            int count = getActiveCount(factionId, type);
            long cooldown = getCooldownRemaining(factionId, type);
            String cooldownStr = cooldown > 0 ? " §7(冷却 " + (cooldown / 1000) + "s)" : " §a✓";
            result.add(getDisplayName(factionId, type) + ": " + count + "/" + cfg.max + cooldownStr);
        }

        return result;
    }

    // ========== 辅助方法 ==========

    private ServerLevel resolveDeployLevel(ServerPlayer commander, VehicleConfig.VehicleTypeConfig cfg) {
        return commander.server.overworld();
    }

    @Nullable
    private BlockPos resolveSpawnPosition(
        ServerLevel level,
        VehicleConfig.VehicleTypeConfig cfg,
        @Nullable ServerPlayer commander,
        @Nullable BlockPos deployPointBase
    ) {
        VehicleConfig.DeploymentConfig deployment = cfg.deployment;
        BlockPos base = resolveBasePosition(commander, deployPointBase, deployment);
        if (base == null) return null;

        int[] offset = deployment.offset;
        BlockPos center = base.offset(offset[0], offset[1], offset[2]);
        return findSpawnPosition(level, center, deployment);
    }

    @Nullable
    private BlockPos resolveBasePosition(
        @Nullable ServerPlayer commander,
        @Nullable BlockPos deployPointBase,
        VehicleConfig.DeploymentConfig deployment
    ) {
        if (deployment.fixed()) {
            if (deployment.absolute == null || deployment.absolute.length < 3) return null;
            return new BlockPos(deployment.absolute[0], deployment.absolute[1], deployment.absolute[2]);
        }

        if (deployPointBase != null) {
            return deployPointBase;
        }

        if (commander == null) {
            return null;
        }

        BastionManager.DeployPoint deployPoint = BastionManager.getInstance().getPlayerDeployPoint(commander.getUUID());
        return deployPoint != null ? deployPoint.pos : null;
    }

    /**
     * 按载具自己的部署配置寻找生成位置。
     */
    @Nullable
    private BlockPos findSpawnPosition(ServerLevel level, BlockPos center, VehicleConfig.DeploymentConfig deployment) {
        if (!deployment.snapToGround) {
            return level.getBlockState(center).isAir() ? center : null;
        }

        int radius = Math.max(0, deployment.radius);
        Random random = new Random();

        // 尝试多次寻找合适位置；radius 为 0 时只检查中心点。
        int attempts = Math.max(1, 12 + radius * 4);
        for (int attempt = 0; attempt < attempts; attempt++) {
            int dx = radius == 0 ? 0 : random.nextInt(radius * 2 + 1) - radius;
            int dz = radius == 0 ? 0 : random.nextInt(radius * 2 + 1) - radius;
            BlockPos pos = center.offset(dx, 0, dz);

            for (int dy = deployment.verticalScan; dy >= -deployment.verticalScan; dy--) {
                BlockPos checkPos = pos.offset(0, dy, 0);
                if (level.getBlockState(checkPos).isAir() &&
                    !level.getBlockState(checkPos.below()).isAir()) {
                    return checkPos;
                }
            }
        }

        if (level.getBlockState(center).isAir()) {
            return center;
        }
        return null;
    }

    /**
     * 创建载具实体（从配置读取 entity_type）
     */
    @Nullable
    private Entity createVehicleEntity(ServerLevel level, String vehicleType, BlockPos pos, String factionId, VehicleConfig.VehicleTypeConfig config) {
        EntityType<?> entityTypeObj = config.getEntityType();
        if (entityTypeObj == null) {
            Espetro.LOGGER.warn("载具 {} 未配置 entity_type 或注册名无效", vehicleType);
            return null;
        }

        Entity entity = entityTypeObj.create(level);
        if (entity == null) return null;

        // 基础位置和名称
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;

        String name = config.displayName != null ? config.displayName : vehicleType;
        entity.setCustomName(Component.literal(name));
        entity.setCustomNameVisible(false);

        entity.setPos(x, y, z);
        entity.setYRot(config.deployment.yaw);
        entity.setYHeadRot(config.deployment.yaw);
        entity.addTag("espetro_vehicle");
        entity.addTag("espetro_" + vehicleType);
        entity.addTag("espetro_vehicle_type_" + vehicleType);
        return entity;
    }

    /**
     * 获取载具类型的显示名（含颜色），优先从配置读取
     */
    public static String getDisplayName(String factionId, String vehicleType) {
        VehicleConfig.VehicleTypeConfig cfg = VehicleConfig.getVehicleConfig(factionId, vehicleType);
        if (cfg != null && cfg.displayName != null) {
            return cfg.displayName;
        }
        return vehicleType;
    }

    /**
     * 向指挥官发送可点击的载具部署信息
     */
    public static void sendDeployChatMessages(ServerPlayer player, String factionId) {
        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("════ ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00)).withBold(true)))
            .append(Component.literal("载具部署面板").withStyle(Style.EMPTY.withColor(0xFFAA00).withBold(true)))
            .append(Component.literal(" ════").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00)).withBold(true))));
        player.sendSystemMessage(Component.literal("编制: ").withStyle(Style.EMPTY.withColor(0xAAAAAA))
            .append(Component.literal(getFactionDisplayName(factionId)).withStyle(Style.EMPTY.withColor(0x00FFAA))));
        player.sendSystemMessage(Component.literal("载具将按 JSON 配置的部署位置生成。").withStyle(Style.EMPTY.withColor(0x888888)));
        player.sendSystemMessage(Component.literal(""));

        Map<String, VehicleConfig.VehicleTypeConfig> configs = VehicleConfig.getFactionVehicles(factionId);
        if (configs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c当前编制无载具配置。"));
            return;
        }

        for (Map.Entry<String, VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String type = entry.getKey();
            VehicleConfig.VehicleTypeConfig cfg = entry.getValue();
            String displayName = getDisplayName(factionId, type);
            int current = getInstance().getActiveCount(factionId, type);
            long cooldown = getInstance().getCooldownRemaining(factionId, type);
            String status = cooldown > 0 ? "§c冷却 " + (cooldown / 1000) + "s" : (current >= cfg.max ? "§6已满" : "§a就绪");

            player.sendSystemMessage(Component.empty()
                .append(Component.literal("▸ " + displayName + "  ").withStyle(Style.EMPTY.withColor(0xFFFFFF)))
                .append(Component.literal(status + " (" + current + "/" + cfg.max + " | " + cfg.respawnMinutes + "分钟刷新)").withStyle(Style.EMPTY.withColor(0xAAAAAA))));
            player.sendSystemMessage(Component.empty()
                .append(Component.literal("  [")
                    .withStyle(Style.EMPTY.withColor(0x555555)))
                .append(Component.literal("点击部署")
                    .withStyle(Style.EMPTY
                        .withColor(TextColor.fromRgb(0x55FF55))
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/vehicle spawn " + quoteCommandString(type)))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("§a点击部署 " + displayName))))
                )
                .append(Component.literal("]").withStyle(Style.EMPTY.withColor(0x555555)))
            );
        }

        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.literal("输入 /vehicle list 查看实时状态").withStyle(Style.EMPTY.withColor(0x888888)));
    }

    private static String getFactionDisplayName(String factionId) {
        return switch (factionId) {
            case "pla_medium_brigade" -> "PLA中型合成旅";
            case "pla_heavy_brigade" -> "PLA重型合成旅";
            case "russia_army" -> "俄罗斯陆上部队";
            case "russia_logistics" -> "俄罗斯支援部队";
            case "us_cavalry" -> "美国第一骑兵旅";
            case "us_airborne" -> "美国141空降部队";
            case "middle_east_militia" -> "中东联合武装";
            case "ukraine_irregular" -> "乌萨克非正规武装";
            default -> factionId;
        };
    }

    private static String quoteCommandString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
