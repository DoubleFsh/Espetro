package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Radio / 兵站（HAB）共享运行时记录。
 * {@link StructureKind#RADIO} 持有建材/弹药库存与建造范围；
 * {@link StructureKind#HAB} 为可复活兵站（建筑 + 盔甲架）。
 */
public class BastionData {

    private final UUID bastionId;
    private final String team; // ATTACK 或 DEFEND
    private String name;
    private final BlockPos position;
    private final ServerLevel level;
    private StructureKind kind = StructureKind.RADIO;
    private UUID armorStandId;
    private int bastionNumber = -1;
    private float coreHealth;
    @Nullable
    private BlockPos armorStandPosition;
    @Nullable
    private BlockPos shulkerPos; // Radio 弹药补给潜影盒位置
    private boolean active;
    private int constructionSupplies;
    private int ammunitionSupplies;
    private boolean habBuilt;
    private boolean ammoCrateBuilt;
    private long habAvailableAt;
    private long habDisabledUntil;
    /** 旧档：单一 FOB 同时充当 Radio+HAB，读档后仍可部署直至被拆。 */
    private boolean legacyCombined;
    /** 运行时覆盖缓存：HAB 是否仍在己方 Radio 建造半径内（事件驱动重算，不存 NBT）。 */
    private transient boolean habCoveredCache = true;

    public boolean isHabCoveredCache() {
        return habCoveredCache;
    }

    public void setHabCoveredCache(boolean habCoveredCache) {
        if (this.habCoveredCache == habCoveredCache) return;
        this.habCoveredCache = habCoveredCache;
        markTacticalDirty();
    }

    public BastionData(String team, String name, BlockPos position, ServerLevel level) {
        this(UUID.randomUUID(), team, name, position, level, StructureKind.RADIO);
    }

    public BastionData(String team, String name, BlockPos position, ServerLevel level, StructureKind kind) {
        this(UUID.randomUUID(), team, name, position, level, kind);
    }

    public BastionData(UUID bastionId, String team, String name, BlockPos position, ServerLevel level) {
        this(bastionId, team, name, position, level, StructureKind.RADIO);
    }

    public BastionData(UUID bastionId, String team, String name, BlockPos position, ServerLevel level,
                       StructureKind kind) {
        this.bastionId = bastionId;
        this.team = team;
        this.name = name;
        this.position = position;
        this.level = level;
        this.kind = kind == null ? StructureKind.RADIO : kind;
        this.armorStandPosition = position.above();
        this.active = true;
        this.coreHealth = BastionManager.getInstance().getArmorStandHealth();
        if (this.kind == StructureKind.HAB) {
            this.habBuilt = true;
        }
    }

    public StructureKind getKind() {
        return kind;
    }

    public void setKind(StructureKind kind) {
        StructureKind resolved = kind == null ? StructureKind.RADIO : kind;
        if (this.kind == resolved) return;
        this.kind = resolved;
        markTacticalDirty();
    }

    public boolean isRadio() {
        return kind == StructureKind.RADIO;
    }

    public boolean isHab() {
        return kind == StructureKind.HAB;
    }

    public boolean isLegacyCombined() {
        return legacyCombined;
    }

    public void setLegacyCombined(boolean legacyCombined) {
        this.legacyCombined = legacyCombined;
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
        if (java.util.Objects.equals(this.name, name)) return;
        this.name = name;
        markTacticalDirty();
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
        if (this.active == active) return;
        this.active = active;
        markTacticalDirty();
    }

    public int getConstructionSupplies() {
        return constructionSupplies;
    }

    public int getAmmunitionSupplies() {
        return ammunitionSupplies;
    }

    public void addConstructionSupplies(int amount, int maximum) {
        int updated = Math.max(0, Math.min(maximum, constructionSupplies + amount));
        if (updated == constructionSupplies) return;
        constructionSupplies = updated;
        markTacticalDirty();
    }

    public void addAmmunitionSupplies(int amount, int maximum) {
        int updated = Math.max(0, Math.min(maximum, ammunitionSupplies + amount));
        if (updated == ammunitionSupplies) return;
        ammunitionSupplies = updated;
        markTacticalDirty();
    }

    public boolean consumeConstructionSupplies(int amount) {
        if (amount < 0 || constructionSupplies < amount) {
            return false;
        }
        if (amount == 0) return true;
        constructionSupplies -= amount;
        markTacticalDirty();
        return true;
    }

    public boolean consumeAmmunitionSupplies(int amount) {
        if (amount < 0 || ammunitionSupplies < amount) {
            return false;
        }
        if (amount == 0) return true;
        ammunitionSupplies -= amount;
        markTacticalDirty();
        return true;
    }

    public boolean isHabBuilt() {
        return habBuilt;
    }

    public void setHabBuilt(boolean habBuilt) {
        if (this.habBuilt == habBuilt) return;
        this.habBuilt = habBuilt;
        markTacticalDirty();
    }

    public boolean isAmmoCrateBuilt() {
        return ammoCrateBuilt;
    }

    public void setAmmoCrateBuilt(boolean ammoCrateBuilt) {
        if (this.ammoCrateBuilt == ammoCrateBuilt) return;
        this.ammoCrateBuilt = ammoCrateBuilt;
        markTacticalDirty();
    }

    public long getHabAvailableAt() {
        return habAvailableAt;
    }

    public void setHabAvailableAt(long habAvailableAt) {
        if (this.habAvailableAt == habAvailableAt) return;
        this.habAvailableAt = habAvailableAt;
        markTacticalDirty();
    }

    public long getHabDisabledUntil() {
        return habDisabledUntil;
    }

    public void setHabDisabledUntil(long habDisabledUntil) {
        if (this.habDisabledUntil == habDisabledUntil) return;
        this.habDisabledUntil = habDisabledUntil;
        markTacticalDirty();
    }

    private static void markTacticalDirty() {
        org.espetro.api.EspetroAPI.markTacticalMapStateDirty();
    }

    /**
     * 检查核心（Radio 方块或 HAB 盔甲架）是否还存在。
     */
    public boolean checkArmorStand() {
        if (!isChunkLoaded()) return false;
        // Radio 核心 = 方块本身
        if (kind == StructureKind.RADIO && !legacyCombined) {
            if (BastionItems.RADIO_BLOCK != null
                && level.getBlockState(position).is(BastionItems.RADIO_BLOCK)) {
                armorStandPosition = position;
                resetMissingEntityTicks();
                return true;
            }
            return false;
        }
        if (armorStandId == null) return false;
        Entity entity = level.getEntity(armorStandId);
        if (entity instanceof ArmorStand armorStand && armorStand.isAlive()) {
            BastionManager.getInstance().syncCoreArmorStand(armorStand);
            armorStandPosition = entity.blockPosition();
            coreHealth = armorStand.getHealth();
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
     * 获取核心实体当前生命值（Radio 方块无 HP，返回记录值）。
     */
    public float getArmorStandHealth() {
        if (kind == StructureKind.RADIO && !legacyCombined) {
            return coreHealth;
        }
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
        tag.putString("kind", kind.name());
        tag.putBoolean("legacyCombined", legacyCombined);
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
        tag.putInt("constructionSupplies", constructionSupplies);
        tag.putInt("ammunitionSupplies", ammunitionSupplies);
        tag.putBoolean("habBuilt", habBuilt);
        tag.putBoolean("ammoCrateBuilt", ammoCrateBuilt);
        tag.putLong("habAvailableAt", habAvailableAt);
        tag.putLong("habDisabledUntil", habDisabledUntil);
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

        boolean hasKind = tag.contains("kind");
        StructureKind kind = StructureKind.fromStorage(hasKind ? tag.getString("kind") : null);
        BastionData data = new BastionData(bastionId, team, name, pos, level, kind);
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
        data.constructionSupplies = Math.max(0, tag.getInt("constructionSupplies"));
        data.ammunitionSupplies = Math.max(0, tag.getInt("ammunitionSupplies"));
        // Old saves represented a complete spawn building and ammo crate.
        data.habBuilt = !tag.contains("habBuilt") || tag.getBoolean("habBuilt");
        data.ammoCrateBuilt = !tag.contains("ammoCrateBuilt") || tag.getBoolean("ammoCrateBuilt");
        data.habAvailableAt = tag.getLong("habAvailableAt");
        data.habDisabledUntil = tag.getLong("habDisabledUntil");

        if (!hasKind) {
            // 旧单一 FOB：视为 Radio，且若已建成 HAB 则保留可部署（legacyCombined）。
            data.setKind(StructureKind.RADIO);
            if (data.habBuilt) {
                data.setLegacyCombined(true);
            }
        } else if (tag.contains("legacyCombined")) {
            data.setLegacyCombined(tag.getBoolean("legacyCombined"));
        }
        if (data.isHab()) {
            data.habBuilt = true;
        }

        return data;
    }
}
