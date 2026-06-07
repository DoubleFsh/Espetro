package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 兵站数据类
 * 存储单个兵站的信息
 */
public class BastionData {

    private final UUID bastionId;
    private final String team; // ATTACK 或 DEFEND
    private String name;
    private final BlockPos position;
    private final ServerLevel level;
    private UUID armorStandId;
    @Nullable
    private BlockPos shulkerPos; // 弹药补给潜影盒位置
    private boolean active;

    private static final int MAX_ARMOR_STAND_HEALTH = 500;
    private static final int FORCE_LOAD_MIN_X_OFFSET = -3;
    private static final int FORCE_LOAD_MAX_X_OFFSET = 1;
    private static final int FORCE_LOAD_MIN_Z_OFFSET = -1;
    private static final int FORCE_LOAD_MAX_Z_OFFSET = 2;

    public BastionData(String team, String name, BlockPos position, ServerLevel level) {
        this.bastionId = UUID.randomUUID();
        this.team = team;
        this.name = name;
        this.position = position;
        this.level = level;
        this.active = true;
    }

    public UUID getBastionId() {
        return bastionId;
    }

    public String getTeam() {
        return team;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BlockPos getPosition() {
        return position;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public UUID getArmorStandId() {
        return armorStandId;
    }

    public void setArmorStandId(UUID armorStandId) {
        this.armorStandId = armorStandId;
    }

    @Nullable
    public BlockPos getShulkerPos() {
        return shulkerPos;
    }

    public void setShulkerPos(BlockPos shulkerPos) {
        this.shulkerPos = shulkerPos;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * 检查盔甲架是否还存在
     */
    public boolean checkArmorStand() {
        if (armorStandId == null) return false;
        if (!isChunkLoaded()) return true;
        Entity entity = level.getEntity(armorStandId);
        return entity != null && entity.isAlive();
    }

    /**
     * 检查兵站所在区块是否已加载。
     */
    public boolean isChunkLoaded() {
        return level.hasChunkAt(position);
    }

    /**
     * 获取兵站结构占用的全部区块。
     */
    public Set<ChunkPos> getForceLoadedChunks() {
        int minChunkX = SectionPos.blockToSectionCoord(position.getX() + FORCE_LOAD_MIN_X_OFFSET);
        int maxChunkX = SectionPos.blockToSectionCoord(position.getX() + FORCE_LOAD_MAX_X_OFFSET);
        int minChunkZ = SectionPos.blockToSectionCoord(position.getZ() + FORCE_LOAD_MIN_Z_OFFSET);
        int maxChunkZ = SectionPos.blockToSectionCoord(position.getZ() + FORCE_LOAD_MAX_Z_OFFSET);

        Set<ChunkPos> chunks = new HashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        return chunks;
    }

    /**
     * 主动加载兵站所在区块，用于选择远距离兵站前的最终校验和传送。
     */
    public void ensureChunkLoaded() {
        for (ChunkPos chunkPos : getForceLoadedChunks()) {
            level.getChunk(chunkPos.x, chunkPos.z);
        }
    }

    /**
     * 获取盔甲架当前生命值
     */
    public float getArmorStandHealth() {
        if (armorStandId == null) return 0;
        Entity entity = level.getEntity(armorStandId);
        if (entity instanceof ArmorStand armorStand) {
            return armorStand.getHealth();
        }
        return 0;
    }

    /**
     * 保存到NBT
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("bastionId", bastionId);
        tag.putString("team", team);
        tag.putString("name", name);
        tag.putInt("x", position.getX());
        tag.putInt("y", position.getY());
        tag.putInt("z", position.getZ());
        if (armorStandId != null) {
            tag.putUUID("armorStandId", armorStandId);
        }
        if (shulkerPos != null) {
            tag.putInt("sx", shulkerPos.getX());
            tag.putInt("sy", shulkerPos.getY());
            tag.putInt("sz", shulkerPos.getZ());
        }
        tag.putBoolean("active", active);
        return tag;
    }

    /**
     * 从NBT加载
     */
    public static BastionData load(CompoundTag tag, ServerLevel level) {
        String team = tag.getString("team");
        String name = tag.getString("name");
        int x = tag.getInt("x");
        int y = tag.getInt("y");
        int z = tag.getInt("z");
        BlockPos pos = new BlockPos(x, y, z);

        BastionData data = new BastionData(team, name, pos, level);

        if (tag.hasUUID("armorStandId")) {
            data.setArmorStandId(tag.getUUID("armorStandId"));
        }
        if (tag.contains("sx")) {
            data.setShulkerPos(new BlockPos(tag.getInt("sx"), tag.getInt("sy"), tag.getInt("sz")));
        }
        data.setActive(tag.getBoolean("active"));

        return data;
    }
}
