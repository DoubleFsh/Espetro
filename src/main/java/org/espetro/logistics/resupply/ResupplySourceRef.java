package org.espetro.logistics.resupply;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;
import java.util.UUID;

/** Stable, bounded reference to the server-side account backing a menu. */
public record ResupplySourceRef(Kind kind, BlockPos blockPos, UUID entityId) {
    public enum Kind {
        RADIO,
        VEHICLE,
        /** 主出生点无限弹药箱：功能与弹药箱一致，但不消耗弹药值。 */
        MAIN_BASE_AMMO
    }

    public ResupplySourceRef {
        kind = Objects.requireNonNull(kind, "kind");
        blockPos = blockPos == null ? BlockPos.ZERO : blockPos.immutable();
        entityId = entityId == null ? new UUID(0L, 0L) : entityId;
    }

    public static ResupplySourceRef radio(BlockPos pos) {
        return new ResupplySourceRef(Kind.RADIO, pos, null);
    }

    public static ResupplySourceRef vehicle(UUID id) {
        return new ResupplySourceRef(Kind.VEHICLE, BlockPos.ZERO, id);
    }

    public static ResupplySourceRef mainBaseAmmo(BlockPos pos) {
        return new ResupplySourceRef(Kind.MAIN_BASE_AMMO, pos, null);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        if (kind == Kind.RADIO || kind == Kind.MAIN_BASE_AMMO) buf.writeBlockPos(blockPos);
        else buf.writeUUID(entityId);
    }

    public static ResupplySourceRef read(FriendlyByteBuf buf) {
        Kind kind = buf.readEnum(Kind.class);
        return switch (kind) {
            case RADIO -> radio(buf.readBlockPos());
            case MAIN_BASE_AMMO -> mainBaseAmmo(buf.readBlockPos());
            default -> vehicle(buf.readUUID());
        };
    }
}
