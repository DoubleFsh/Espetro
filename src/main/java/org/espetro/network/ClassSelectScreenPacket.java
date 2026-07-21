package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 编制选择界面数据包
 * 服务端将可选编制（Faction）列表一起发送，客户端无需自行加载
 * 同时携带对手队伍已确定的编制信息（如果已定）
 */
public class ClassSelectScreenPacket {

    private final String team;
    private final boolean isCommander;
    private final List<FactionInfo> factions;
    private final int timeRemaining;
    // 对手编制信息
    private final String opponentTeamName;
    private final String opponentFaction;
    // 对手阶段剩余时间（秒），-1 表示无对手阶段
    private final int opponentTimeRemaining;
    private final String selectedFactionId;

    public static class FactionInfo {
        public final String id;
        public final String name;
        public final String selectionImage;
        public final int voteCount;

        public FactionInfo(String id, String name, String selectionImage, int voteCount) {
            this.id = id;
            this.name = name;
            this.selectionImage = selectionImage != null ? selectionImage : "";
            this.voteCount = voteCount;
        }
    }

    public ClassSelectScreenPacket(String team, boolean isCommander, List<FactionInfo> factions,
                                    int timeRemaining, String opponentTeamName, String opponentFaction,
                                    int opponentTimeRemaining, String selectedFactionId) {
        this.team = team;
        this.isCommander = isCommander;
        this.factions = factions;
        this.timeRemaining = timeRemaining;
        this.opponentTeamName = opponentTeamName;
        this.opponentFaction = opponentFaction;
        this.opponentTimeRemaining = opponentTimeRemaining;
        this.selectedFactionId = selectedFactionId != null ? selectedFactionId : "";
    }

    public String getTeam() { return team; }
    public boolean isCommander() { return isCommander; }
    public List<FactionInfo> getFactions() { return factions; }
    public int getTimeRemaining() { return timeRemaining; }
    public String getOpponentTeamName() { return opponentTeamName; }
    public String getOpponentFaction() { return opponentFaction; }
    public int getOpponentTimeRemaining() { return opponentTimeRemaining; }
    public String getSelectedFactionId() { return selectedFactionId; }

    public static ClassSelectScreenPacket read(FriendlyByteBuf buf) {
        String team = buf.readUtf();
        boolean isCommander = buf.readBoolean();
        int timeRemaining = buf.readVarInt();
        int count = buf.readVarInt();
        List<FactionInfo> factions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buf.readUtf();
            String name = buf.readUtf();
            String selectionImage = buf.readUtf();
            int voteCount = buf.readVarInt();
            factions.add(new FactionInfo(id, name, selectionImage, voteCount));
        }
        String opponentTeamName = buf.readUtf();
        String opponentFaction = buf.readUtf();
        int opponentTimeRemaining = buf.readInt();
        String selectedFactionId = buf.readUtf();
        return new ClassSelectScreenPacket(team, isCommander, factions, timeRemaining,
            opponentTeamName, opponentFaction, opponentTimeRemaining, selectedFactionId);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(team);
        buf.writeBoolean(isCommander);
        buf.writeVarInt(timeRemaining);
        buf.writeVarInt(factions.size());
        for (FactionInfo info : factions) {
            buf.writeUtf(info.id);
            buf.writeUtf(info.name);
            buf.writeUtf(info.selectionImage);
            buf.writeVarInt(info.voteCount);
        }
        buf.writeUtf(opponentTeamName != null ? opponentTeamName : "");
        buf.writeUtf(opponentFaction != null ? opponentFaction : "");
        buf.writeInt(opponentTimeRemaining);
        buf.writeUtf(selectedFactionId);
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
