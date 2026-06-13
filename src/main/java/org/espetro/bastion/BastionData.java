package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import javax.annotation.Nullable;
import java.util.UUID;

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
    private int bastionNumber = -1;
    private float coreHealth;
    @Nullable
    private BlockPos armorStandPosition;
    @Nullable
    private BlockPos shulkerPos; // 弹药补给潜影盒位置
    private boolean active;

    public BastionData(String team, String name, BlockPos position, ServerLevel level) {
        this(UUID.randomUUID(), team, name, position, level);
    }

    public BastionData(UUID bastionId, String team, String name, BlockPos position, ServerLevel level) {
        this.bastionId = bastionId;
        this.team = team;
        this.name = name;
        this.position = position;
        this.level = level;
        this.armorStandPosition = position.above();
        this.active = true;
        this.coreHealth = BastionManager.getInstance().getArmorStandHealth();
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

    public float getCoreHealth() {
        return coreHealth;
    }

    public void setCoreHealth(float coreHealth) {
        this.coreHealth = coreHealth;
    }

    public void resetMissingEntityTicks() {
        // 保留为空方法，兼容管理器调用语义：核心实体恢复后清除短暂缺失状态。
    }

    public int getBastionNumber() {
        return bastionNumber;
    }

    public void setBastionNumber(int bastionNumber) {
        this.bastionNumber = bastionNumber;
    }

    @Nullable
    public BlockPos getArmorStandPosition() {
        return armorStandPosition;
    }

    public void setArmorStandPosition(@Nullable BlockPos armorStandPosition) {
        this.armorStandPosition = armorStandPosition;
    }

    public void clearArmorStandPosition() {
        this.armorStandPosition = null;
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
        if (!isChunkLoaded()) return armorStandPosition != null;
        Entity entity = level.getEntity(armorStandId);
        if (entity instanceof ArmorStand armorStand && armorStand.isAlive()) {
            BastionManager.getInstance().syncCoreArmorStand(armorStand);
            armorStandPosition = entity.blockPosition();
            resetMissingEntityTicks();
            return true;
        }
        return false;
    }

    /**
     * 检查兵站所在区块是否已加载。
     */
    public boolean isChunkLoaded() {
        return level.hasChunkAt(armorStandPosition != null ? armorStandPosition : position);
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
        tag.putInt("bastionNumber", bastionNumber);
        tag.putFloat("coreHealth", coreHealth);
        if (armorStandPosition != null) {
            tag.putInt("armorStandX", armorStandPosition.getX());
            tag.putInt("armorStandY", armorStandPosition.getY());
            tag.putInt("armorStandZ", armorStandPosition.getZ());
        }
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
        UUID bastionId = tag.hasUUID("bastionId") ? tag.getUUID("bastionId") : UUID.randomUUID();
        String team = tag.getString("team");
        String name = tag.getString("name");
        int x = tag.getInt("x");
        int y = tag.getInt("y");
        int z = tag.getInt("z");
        BlockPos pos = new BlockPos(x, y, z);

        BastionData data = new BastionData(bastionId, team, name, pos, level);
        if (tag.contains("bastionNumber")) {
            data.setBastionNumber(tag.getInt("bastionNumber"));
        }
        if (tag.contains("coreHealth")) {
            data.setCoreHealth(tag.getFloat("coreHealth"));
        }
        if (tag.contains("armorStandX")) {
            data.setArmorStandPosition(new BlockPos(
                tag.getInt("armorStandX"),
                tag.getInt("armorStandY"),
                tag.getInt("armorStandZ")
            ));
        } else {
            data.setArmorStandPosition(pos.above());
        }

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
