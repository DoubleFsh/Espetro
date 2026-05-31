package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 投票数据同步包
 * 同步投票数和票数统计给客户端
 */
public class VoteDataPacket {

    private final Map<String, Integer> voteCounts;
    private final int timeRemaining;

    public VoteDataPacket(Map<String, Integer> voteCounts, int timeRemaining) {
        this.voteCounts = voteCounts;
        this.timeRemaining = timeRemaining;
    }

    public static VoteDataPacket read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<String, Integer> voteCounts = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf();
            int count = buf.readInt();
            voteCounts.put(name, count);
        }
        int timeRemaining = buf.readInt();
        return new VoteDataPacket(voteCounts, timeRemaining);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(voteCounts.size());
        for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeInt(entry.getValue());
        }
        buf.writeInt(timeRemaining);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleVoteData", VoteDataPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public Map<String, Integer> getVoteCounts() {
        return voteCounts;
    }

    public int getTimeRemaining() {
        return timeRemaining;
    }
}
