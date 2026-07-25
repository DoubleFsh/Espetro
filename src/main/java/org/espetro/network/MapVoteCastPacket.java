package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.MapVoteManager;

import java.util.function.Supplier;

/** C→S cast or change map vote. */
public class MapVoteCastPacket {

    private final String mapFolder;

    public MapVoteCastPacket(String mapFolder) {
        this.mapFolder = mapFolder == null ? "" : mapFolder;
    }

    public static MapVoteCastPacket read(FriendlyByteBuf buf) {
        return new MapVoteCastPacket(buf.readUtf());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(mapFolder);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            MapVoteManager.getInstance().castVote(player, mapFolder);
        });
        ctx.get().setPacketHandled(true);
    }
}
