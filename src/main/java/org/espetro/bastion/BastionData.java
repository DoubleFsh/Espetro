package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
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
    private boolean active;

    private static final int MAX_ARMOR_STAND_HEALTH = 500;

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
        Entity entity = level.getEntity(armorStandId);
        return entity != null && entity.isAlive();
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
        data.setActive(tag.getBoolean("active"));

        return data;
    }
}
