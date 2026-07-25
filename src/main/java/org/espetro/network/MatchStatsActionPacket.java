package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.governance.CommanderGovernanceManager;
import org.espetro.team.SquadManager;

import java.util.UUID;
import java.util.function.Supplier;

/** C→S scoreboard context menu actions. */
public class MatchStatsActionPacket {

    public enum Action {
        FORCE_JOIN_SQUAD,
        KICK_FROM_SQUAD
    }

    private final Action action;
    private final UUID target;

    public MatchStatsActionPacket(Action action, UUID target) {
        this.action = action;
        this.target = target;
    }

    public static MatchStatsActionPacket read(FriendlyByteBuf buf) {
        Action a;
        try {
            a = Action.valueOf(buf.readUtf());
        } catch (Exception e) {
            a = Action.FORCE_JOIN_SQUAD;
        }
        return new MatchStatsActionPacket(a, buf.readUUID());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(action.name());
        buf.writeUUID(target);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            SquadManager.ActionResult result = switch (action) {
                case FORCE_JOIN_SQUAD -> SquadManager.getInstance().forceJoinSquad(player, target);
                case KICK_FROM_SQUAD -> SquadManager.getInstance().kickMember(player, target);
            };
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                (result.success ? "§a" : "§c") + result.message));
            if (result.success && result.team != null) {
                NetworkManager.syncSquadsToTeam(result.team);
                NetworkManager.broadcastClassCounts(result.team,
                    org.espetro.team.ClassCountManager.getInstance().getPlayerFaction(player.getUUID()));
                NetworkManager.broadcastMatchStats(org.espetro.stats.PlayerMatchStatsManager.getInstance());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
