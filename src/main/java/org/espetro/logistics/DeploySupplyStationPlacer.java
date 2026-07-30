package org.espetro.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.espetro.Espetro;
import org.espetro.team.SpawnPointConfig;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在双方原部署点（spawn_points）旁自动放置 {@link LogisticsBlocks#SUPPLY_SOURCE}，
 * 并在方块上方显示「补给站」文字标题。
 * <p>
 * 位置：部署点朝向右侧 2 格，与部署点同高（Y +0）。
 */
public final class DeploySupplyStationPlacer {

    public static final String TAG = "espetro_deploy_supply_station";
    public static final String LABEL = "补给站";
    /** 相对部署点朝向的侧向偏移（格）。 */
    private static final double SIDE_OFFSET = 2.0;
    /** 相对部署点 Y 的抬升（格）。0 = 与部署点同高。 */
    private static final int Y_OFFSET = 0;
    /** 标题盔甲架相对方块顶部的额外高度。 */
    private static final double LABEL_Y_ABOVE_BLOCK = 0.85;
    private static final Map<String, List<PlacedStation>> PLACED = new HashMap<>();

    private DeploySupplyStationPlacer() {
    }

    /**
     * 清除本维度旧站后，在 ATTACK / DEFEND 原部署点旁各放一座补给站。
     *
     * @return 成功放置数量
     */
    public static int placeAtSpawnPoints(ServerLevel level) {
        if (level == null || LogisticsBlocks.SUPPLY_SOURCE == null) {
            return 0;
        }
        clear(level);

        int placed = 0;
        for (String team : new String[]{"ATTACK", "DEFEND"}) {
            SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
            if (spawn == null) {
                continue;
            }
            if (placeOne(level, spawn, team)) {
                placed++;
            }
        }
        Espetro.LOGGER.info("原部署点补给站预放完成: {} 个 (维度 {})",
            placed, level.dimension().location());
        return placed;
    }

    /**
     * 移除本维度由本类放置的补给方块与标题实体。
     */
    public static int clear(@Nullable ServerLevel level) {
        if (level == null) {
            return 0;
        }
        String dimension = level.dimension().location().toString();
        List<PlacedStation> stations = PLACED.remove(dimension);
        if (stations == null || stations.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (PlacedStation station : stations) {
            if (level.hasChunkAt(station.blockPos())
                && isAutoSupplyBlock(level, station.blockPos())) {
                level.setBlock(station.blockPos(), Blocks.AIR.defaultBlockState(), 3);
                removed++;
            }
            var label = level.getEntity(station.labelId());
            if (label != null && !label.isRemoved()) {
                label.discard();
            }
        }
        return removed;
    }

    private static boolean placeOne(ServerLevel level, SpawnPointConfig.SpawnPoint spawn, String team) {
        BlockPos pos = resolveOffset(spawn);
        if (!level.hasChunkAt(pos)) {
            Espetro.LOGGER.warn("部署点补给站区块尚未预载，跳过 {} ({})", pos, team);
            return false;
        }

        BlockState state = LogisticsBlocks.SUPPLY_SOURCE.defaultBlockState();
        if (!level.setBlock(pos, state, 3)) {
            Espetro.LOGGER.warn("无法放置部署点补给站 at {} ({})", pos, team);
            return false;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SupplySourceBlockEntity supply) {
            // 与 logistics.json 默认 source_ids: ["default"] 对齐
            supply.setSourceId("default");
        }

        UUID labelId = spawnLabel(level, pos, team);
        if (labelId == null) {
            Espetro.LOGGER.warn("部署点补给站标题生成失败 at {} ({})", pos, team);
            labelId = UUID.randomUUID();
        }
        PLACED.computeIfAbsent(
            level.dimension().location().toString(), ignored -> new java.util.ArrayList<>())
            .add(new PlacedStation(pos.immutable(), labelId));
        return true;
    }

    /**
     * 部署点朝向右侧 2 格，Y 与部署点同高。
     */
    public static BlockPos resolveOffset(SpawnPointConfig.SpawnPoint spawn) {
        float yawRad = spawn.yaw * ((float) Math.PI / 180f);
        // Minecraft yaw: 0 = +Z，90 = -X。右侧 = (-cos, -sin)
        double rightX = -Mth.cos(yawRad);
        double rightZ = -Mth.sin(yawRad);
        int x = Mth.floor(spawn.x + rightX * SIDE_OFFSET);
        int y = Mth.floor(spawn.y) + Y_OFFSET;
        int z = Mth.floor(spawn.z + rightZ * SIDE_OFFSET);
        return new BlockPos(x, y, z);
    }

    @Nullable
    private static UUID spawnLabel(ServerLevel level, BlockPos blockPos, String team) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        if (stand == null) {
            return null;
        }
        double x = blockPos.getX() + 0.5;
        double y = blockPos.getY() + 1.0 + LABEL_Y_ABOVE_BLOCK;
        double z = blockPos.getZ() + 0.5;
        stand.setPos(x, y, z);
        stand.setCustomName(Component.literal(LABEL));
        stand.setCustomNameVisible(true);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.setInvulnerable(true);
        // ArmorStand#setMarker 在官方映射下非 public，用 NBT 打开 Marker（无碰撞箱）
        CompoundTag flags = new CompoundTag();
        stand.saveWithoutId(flags);
        flags.putBoolean("Marker", true);
        flags.putBoolean("Invisible", true);
        flags.putBoolean("NoGravity", true);
        flags.putBoolean("Silent", true);
        flags.putBoolean("Invulnerable", true);
        flags.putBoolean("CustomNameVisible", true);
        stand.load(flags);
        stand.setPos(x, y, z);
        stand.setCustomName(Component.literal(LABEL));
        stand.setCustomNameVisible(true);
        stand.addTag(TAG);
        stand.addTag(TAG + "_team_" + team);
        if (!level.addFreshEntity(stand)) {
            stand.discard();
            return null;
        }
        return stand.getUUID();
    }

    private static boolean isAutoSupplyBlock(ServerLevel level, BlockPos pos) {
        if (LogisticsBlocks.SUPPLY_SOURCE == null) {
            return false;
        }
        return level.getBlockState(pos).is(LogisticsBlocks.SUPPLY_SOURCE);
    }

    private record PlacedStation(BlockPos blockPos, UUID labelId) {
    }
}
