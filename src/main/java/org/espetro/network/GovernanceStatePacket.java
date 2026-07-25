package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.governance.CommanderGovernanceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** S→C commander governance state for both teams. */
public class GovernanceStatePacket {

    public static final class TeamState {
        public final String team;
        public final String state;
        public final UUID commander;
        public final UUID challenger;
        public final int remainingSeconds;
        public final long endGameTime;
        public final Map<String, Integer> voteCounts; // candidate uuid string -> votes
        public final List<UUID> volunteers;
        public final UUID myVote;

        public TeamState(String team, String state, UUID commander, UUID challenger,
                         int remainingSeconds, long endGameTime,
                         Map<String, Integer> voteCounts, List<UUID> volunteers, UUID myVote) {
            this.team = team;
            this.state = state;
            this.commander = commander;
            this.challenger = challenger;
            this.remainingSeconds = remainingSeconds;
            this.endGameTime = endGameTime;
            this.voteCounts = voteCounts != null ? voteCounts : Map.of();
            this.volunteers = volunteers != null ? volunteers : List.of();
            this.myVote = myVote;
        }
    }

    public final List<TeamState> teams;

    public GovernanceStatePacket(List<TeamState> teams) {
        this.teams = teams != null ? teams : List.of();
    }

    public static GovernanceStatePacket from(CommanderGovernanceManager mgr, UUID viewer) {
        List<TeamState> list = new ArrayList<>();
        for (String team : List.of("ATTACK", "DEFEND")) {
            var g = mgr.getTeam(team);
            Map<String, Integer> counts = new HashMap<>();
            for (UUID c : g.votes.values()) {
                counts.merge(c.toString(), 1, Integer::sum);
            }
            int remaining = 0;
            if (g.state != CommanderGovernanceManager.State.IDLE) {
                remaining = Math.max(0, g.timeoutSeconds - g.tickCounter / 20);
            }
            UUID myVote = viewer != null ? g.votes.get(viewer) : null;
            list.add(new TeamState(team, g.state.name(), g.commander, g.challenger,
                remaining, g.endGameTime, counts, new ArrayList<>(g.volunteers), myVote));
        }
        return new GovernanceStatePacket(list);
    }

    public static GovernanceStatePacket read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<TeamState> teams = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String team = buf.readUtf();
            String state = buf.readUtf();
            UUID commander = buf.readBoolean() ? buf.readUUID() : null;
            UUID challenger = buf.readBoolean() ? buf.readUUID() : null;
            int remaining = buf.readVarInt();
            long end = buf.readLong();
            int m = buf.readVarInt();
            Map<String, Integer> counts = new HashMap<>();
            for (int j = 0; j < m; j++) {
                counts.put(buf.readUtf(), buf.readVarInt());
            }
            int v = buf.readVarInt();
            List<UUID> vols = new ArrayList<>(v);
            for (int j = 0; j < v; j++) {
                vols.add(buf.readUUID());
            }
            UUID myVote = buf.readBoolean() ? buf.readUUID() : null;
            teams.add(new TeamState(team, state, commander, challenger, remaining, end, counts, vols, myVote));
        }
        return new GovernanceStatePacket(teams);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(teams.size());
        for (TeamState t : teams) {
            buf.writeUtf(t.team);
            buf.writeUtf(t.state);
            buf.writeBoolean(t.commander != null);
            if (t.commander != null) buf.writeUUID(t.commander);
            buf.writeBoolean(t.challenger != null);
            if (t.challenger != null) buf.writeUUID(t.challenger);
            buf.writeVarInt(t.remainingSeconds);
            buf.writeLong(t.endGameTime);
            buf.writeVarInt(t.voteCounts.size());
            for (var e : t.voteCounts.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeVarInt(e.getValue());
            }
            buf.writeVarInt(t.volunteers.size());
            for (UUID u : t.volunteers) buf.writeUUID(u);
            buf.writeBoolean(t.myVote != null);
            if (t.myVote != null) buf.writeUUID(t.myVote);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleGovernanceState", GovernanceStatePacket.class)
                    .invoke(null, this);
            } catch (Exception ignored) {
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
