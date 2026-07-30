package org.espetro.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.Event;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.StructureKind;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 兵站（Radio / HAB）生命周期事件：建造与摧毁均为事件驱动出口，
 * 不依赖强加载区块或每 tick 轮询推断核心是否存在。
 */
public abstract class BastionLifecycleEvent extends Event {

    private final UUID bastionId;
    private final String name;
    private final String team;
    private final StructureKind kind;
    @Nullable
    private final ServerLevel level;
    @Nullable
    private final BlockPos position;

    protected BastionLifecycleEvent(BastionData bastion) {
        this.bastionId = bastion.getBastionId();
        this.name = bastion.getName();
        this.team = bastion.getTeam();
        this.kind = bastion.getKind();
        this.level = bastion.getLevel();
        this.position = bastion.getPosition();
    }

    public UUID bastionId() {
        return bastionId;
    }

    public String name() {
        return name;
    }

    public String team() {
        return team;
    }

    public StructureKind kind() {
        return kind;
    }

    @Nullable
    public ServerLevel level() {
        return level;
    }

    @Nullable
    public BlockPos position() {
        return position;
    }

    /** 结构创建成功并入库后触发（Radio 放置 / HAB 建成）。 */
    public static final class Built extends BastionLifecycleEvent {
        public Built(BastionData bastion) {
            super(bastion);
        }
    }

    /** 结构摧毁流程完成后触发（核心死亡、方块破坏、己方拆除等统一经 destroyBastion）。 */
    public static final class Destroyed extends BastionLifecycleEvent {
        @Nullable
        private final Entity attacker;
        private final boolean deductedManpower;
        private final int manpowerPenalty;

        public Destroyed(BastionData bastion, @Nullable Entity attacker,
                         boolean deductedManpower, int manpowerPenalty) {
            super(bastion);
            this.attacker = attacker;
            this.deductedManpower = deductedManpower;
            this.manpowerPenalty = manpowerPenalty;
        }

        @Nullable
        public Entity attacker() {
            return attacker;
        }

        public boolean deductedManpower() {
            return deductedManpower;
        }

        public int manpowerPenalty() {
            return manpowerPenalty;
        }
    }
}
