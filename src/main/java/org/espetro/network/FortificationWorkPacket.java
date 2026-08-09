package org.espetro.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.bastion.FortificationManager;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/** One rate-limited engineer-shovel operation against a block or entity fortification. */
public record FortificationWorkPacket(@Nullable BlockPos blockTarget,
                                      @Nullable UUID entityTarget,
                                      boolean build) {
    public static FortificationWorkPacket block(BlockPos target, boolean build) {
        return new FortificationWorkPacket(target, null, build);
    }

    public static FortificationWorkPacket entity(UUID target, boolean build) {
        return new FortificationWorkPacket(null, target, build);
    }

    public static FortificationWorkPacket read(FriendlyByteBuf buf) {
        boolean entity = buf.readBoolean();
        UUID entityTarget = entity ? buf.readUUID() : null;
        BlockPos blockTarget = entity ? null : buf.readBlockPos();
        return new FortificationWorkPacket(blockTarget, entityTarget, buf.readBoolean());
    }

    public void write(FriendlyByteBuf buf) {
        boolean entity = entityTarget != null;
        buf.writeBoolean(entity);
        if (entity) buf.writeUUID(entityTarget);
        else buf.writeBlockPos(blockTarget == null ? BlockPos.ZERO : blockTarget);
        buf.writeBoolean(build);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (entityTarget != null) {
                FortificationManager.getInstance().workEntity(player, entityTarget, build);
            } else if (blockTarget != null) {
                FortificationManager.getInstance().work(player, blockTarget, build);
            }
        });
        context.setPacketHandled(true);
    }
}
