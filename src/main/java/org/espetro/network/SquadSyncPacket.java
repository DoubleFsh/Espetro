package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 班组小队同步包（S→C）。
 */
public class SquadSyncPacket {

    private final String team;
    private final List<UnifiedDeployScreenPacket.SquadInfo> squads;
    private final int mySquadId;
    private final List<String> commanderNames;
    private final double teammateNameTagDistance;

    public SquadSyncPacket(String team, List<UnifiedDeployScreenPacket.SquadInfo> squads, int mySquadId) {
        this(team, squads, mySquadId, new ArrayList<>(), 10.0);
    }

    public SquadSyncPacket(String team, List<UnifiedDeployScreenPacket.SquadInfo> squads, int mySquadId,
                           List<String> commanderNames, double teammateNameTagDistance) {
        this.team = team == null ? "" : team;
        this.squads = squads != null ? squads : new ArrayList<>();
        this.mySquadId = mySquadId;
        this.commanderNames = commanderNames != null ? commanderNames : new ArrayList<>();
        this.teammateNameTagDistance = teammateNameTagDistance;
    }

    public static SquadSyncPacket read(FriendlyByteBuf buf) {
        String team = buf.readUtf();
        int size = buf.readVarInt();
        List<UnifiedDeployScreenPacket.SquadInfo> squads = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            squads.add(new UnifiedDeployScreenPacket.SquadInfo(buf));
        }
        int mySquadId = buf.readVarInt();
        int commanderSize = buf.readVarInt();
        List<String> commanderNames = new ArrayList<>();
        for (int i = 0; i < commanderSize; i++) {
            commanderNames.add(buf.readUtf());
        }
        double teammateNameTagDistance = buf.readDouble();
        return new SquadSyncPacket(team, squads, mySquadId, commanderNames, teammateNameTagDistance);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(team);
        buf.writeVarInt(squads.size());
        for (UnifiedDeployScreenPacket.SquadInfo squad : squads) {
            squad.write(buf);
        }
        buf.writeVarInt(mySquadId);
        buf.writeVarInt(commanderNames.size());
        for (String commanderName : commanderNames) {
            buf.writeUtf(commanderName);
        }
        buf.writeDouble(teammateNameTagDistance);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleSquadSync", SquadSyncPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                org.espetro.Espetro.LOGGER.error("Failed to handle SquadSyncPacket", e);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public String getTeam() {
        return team;
    }

    public List<UnifiedDeployScreenPacket.SquadInfo> getSquads() {
        return squads;
    }

    public int getMySquadId() {
        return mySquadId;
    }

    public List<String> getCommanderNames() {
        return commanderNames;
    }

    public double getTeammateNameTagDistance() {
        return teammateNameTagDistance;
    }
}
