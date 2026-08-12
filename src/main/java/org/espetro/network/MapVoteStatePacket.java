package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.MapVoteManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S→C map vote state snapshot.
 */
public class MapVoteStatePacket {

    public final boolean active;
    public final int remainingSeconds;
    public final long endGameTime;
    public final List<Candidate> candidates;
    public final Map<String, Integer> tally;
    public final String myVoteMapFolder;
    public final String winnerMapFolder;
    public final String winnerDisplayName;

    public static final class Candidate {
        public final String mapFolder;
        public final String displayName;
        public final String dimensionId;

        public Candidate(String mapFolder, String displayName, String dimensionId) {
            this.mapFolder = mapFolder;
            this.displayName = displayName;
            this.dimensionId = dimensionId;
        }
    }

    public MapVoteStatePacket(boolean active, int remainingSeconds, long endGameTime,
                              List<Candidate> candidates, Map<String, Integer> tally,
                              String myVoteMapFolder, String winnerMapFolder, String winnerDisplayName) {
        this.active = active;
        this.remainingSeconds = remainingSeconds;
        this.endGameTime = endGameTime;
        this.candidates = candidates != null ? candidates : List.of();
        this.tally = tally != null ? tally : Map.of();
        this.myVoteMapFolder = myVoteMapFolder;
        this.winnerMapFolder = winnerMapFolder;
        this.winnerDisplayName = winnerDisplayName;
    }

    public static MapVoteStatePacket from(MapVoteManager mgr, ServerPlayer viewer) {
        List<Candidate> list = new ArrayList<>();
        for (var c : mgr.getCandidates()) {
            list.add(new Candidate(c.mapFolder, c.displayName, c.dimensionId.toString()));
        }
        String my = viewer != null ? mgr.getPlayerVote(viewer.getUUID()) : null;
        String winFolder = mgr.getWinner() != null ? mgr.getWinner().mapFolder : null;
        String winName = mgr.getWinner() != null ? mgr.getWinner().displayName : null;
        long end = 0L;
        if (viewer != null && viewer.server != null) {
            end = mgr.getEndGameTime(viewer.server);
        }
        return new MapVoteStatePacket(mgr.isActive(), mgr.getRemainingSeconds(), end,
            list, mgr.getTally(), my, winFolder, winName);
    }

    public static MapVoteStatePacket read(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        int remaining = buf.readVarInt();
        long end = buf.readLong();
        int n = buf.readVarInt();
        List<Candidate> candidates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String folder = buf.readUtf();
            String name = buf.readUtf();
            String dimId = buf.readUtf();
            candidates.add(new Candidate(folder, name, dimId));
        }
        int m = buf.readVarInt();
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (int i = 0; i < m; i++) {
            tally.put(buf.readUtf(), buf.readVarInt());
        }
        String my = buf.readBoolean() ? buf.readUtf() : null;
        String winF = buf.readBoolean() ? buf.readUtf() : null;
        String winN = buf.readBoolean() ? buf.readUtf() : null;
        return new MapVoteStatePacket(active, remaining, end, candidates, tally, my, winF, winN);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(remainingSeconds);
        buf.writeLong(endGameTime);
        buf.writeVarInt(candidates.size());
        for (Candidate c : candidates) {
            buf.writeUtf(c.mapFolder);
            buf.writeUtf(c.displayName);
            buf.writeUtf(c.dimensionId);
        }
        buf.writeVarInt(tally.size());
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue());
        }
        buf.writeBoolean(myVoteMapFolder != null);
        if (myVoteMapFolder != null) buf.writeUtf(myVoteMapFolder);
        buf.writeBoolean(winnerMapFolder != null);
        if (winnerMapFolder != null) buf.writeUtf(winnerMapFolder);
        buf.writeBoolean(winnerDisplayName != null);
        if (winnerDisplayName != null) buf.writeUtf(winnerDisplayName);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleMapVoteState", MapVoteStatePacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                // dedicated server
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
