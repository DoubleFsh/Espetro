package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 编制选择界面数据包
 * 服务端将可选编制（Faction）列表一起发送，客户端无需自行加载
 */
public class ClassSelectScreenPacket {

    private final String team;
    private final boolean isCommander;
    private final List<FactionInfo> factions;
    private final int timeRemaining;

    public static class FactionInfo {
        public final String id;
        public final String name;

        public FactionInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public ClassSelectScreenPacket(String team, boolean isCommander, List<FactionInfo> factions, int timeRemaining) {
        this.team = team;
        this.isCommander = isCommander;
        this.factions = factions;
        this.timeRemaining = timeRemaining;
    }

    public String getTeam() { return team; }
    public boolean isCommander() { return isCommander; }
    public List<FactionInfo> getFactions() { return factions; }
    public int getTimeRemaining() { return timeRemaining; }

    public static ClassSelectScreenPacket read(FriendlyByteBuf buf) {
        String team = buf.readUtf();
        boolean isCommander = buf.readBoolean();
        int timeRemaining = buf.readVarInt();
        int count = buf.readVarInt();
        List<FactionInfo> factions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buf.readUtf();
            String name = buf.readUtf();
            factions.add(new FactionInfo(id, name));
        }
        return new ClassSelectScreenPacket(team, isCommander, factions, timeRemaining);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(team);
        buf.writeBoolean(isCommander);
        buf.writeVarInt(timeRemaining);
        buf.writeVarInt(factions.size());
        for (FactionInfo info : factions) {
            buf.writeUtf(info.id);
            buf.writeUtf(info.name);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleClassSelectScreen", ClassSelectScreenPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
