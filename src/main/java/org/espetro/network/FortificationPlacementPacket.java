package org.espetro.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.bastion.FortificationManager;

import java.util.UUID;
import java.util.function.Supplier;

/** Confirm or cancel one server-issued placement preview. */
public record FortificationPlacementPacket(Action action, UUID token,
                                           BlockPos anchor, Direction facing) {
    public enum Action { CONFIRM, CANCEL }

    public static FortificationPlacementPacket read(FriendlyByteBuf buf) {
        return new FortificationPlacementPacket(buf.readEnum(Action.class), buf.readUUID(),
            buf.readBlockPos(), buf.readEnum(Direction.class));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUUID(token);
        buf.writeBlockPos(anchor);
        buf.writeEnum(facing);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            String error = action == Action.CANCEL
                ? FortificationManager.getInstance().cancelPreview(player, token)
                : FortificationManager.getInstance().confirmPreview(player, token, anchor, facing);
            if (error != null) player.sendSystemMessage(Component.literal(error));
        });
        context.setPacketHandled(true);
    }
}
