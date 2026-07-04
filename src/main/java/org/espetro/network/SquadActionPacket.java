package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.team.SquadManager;
import org.espetro.team.TeamPackManager;

import java.util.function.Supplier;

/**
 * 班组小队操作包（C→S）。
 */
public class SquadActionPacket {

    public enum Action {
        CREATE,
        JOIN,
        LEAVE,
        DELETE
    }

    private final Action action;
    private final int squadId;
    private final String squadName;

    public SquadActionPacket(Action action, int squadId, String squadName) {
        this.action = action;
        this.squadId = squadId;
        this.squadName = squadName == null ? "" : squadName;
    }

    public static SquadActionPacket create(String squadName) {
        return new SquadActionPacket(Action.CREATE, SquadManager.NO_SQUAD, squadName);
    }

    public static SquadActionPacket join(int squadId) {
        return new SquadActionPacket(Action.JOIN, squadId, "");
    }

    public static SquadActionPacket leave() {
        return new SquadActionPacket(Action.LEAVE, SquadManager.NO_SQUAD, "");
    }

    public static SquadActionPacket delete(int squadId) {
        return new SquadActionPacket(Action.DELETE, squadId, "");
    }

    public static SquadActionPacket read(FriendlyByteBuf buf) {
        Action action;
        try {
            action = Action.valueOf(buf.readUtf());
        } catch (IllegalArgumentException ignored) {
            action = Action.JOIN;
        }
        int squadId = buf.readVarInt();
        String squadName = buf.readUtf();
        return new SquadActionPacket(action, squadId, squadName);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(action.name());
        buf.writeVarInt(squadId);
        buf.writeUtf(squadName);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            String previousTeam = Espetro.getPlayerTeam(player);
            int previousSquadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
            boolean wasLeader = SquadManager.getInstance().isSquadLeader(player.getUUID());

            SquadManager.ActionResult result = switch (action) {
                case CREATE -> SquadManager.getInstance().createSquad(player, squadName);
                case JOIN -> SquadManager.getInstance().joinSquad(player, squadId);
                case LEAVE -> SquadManager.getInstance().leaveSquad(player);
                case DELETE -> SquadManager.getInstance().deleteSquad(player, squadId);
            };

            player.sendSystemMessage(Component.literal((result.success ? "\u00a7a" : "\u00a7c") + result.message));

            if (result.success) {
                TeamPackManager.getInstance().handleSquadLeaderTransition(
                    player,
                    previousTeam,
                    previousSquadId,
                    wasLeader,
                    Espetro.getPlayerTeam(player),
                    SquadManager.getInstance().getPlayerSquadId(player.getUUID()),
                    SquadManager.getInstance().isSquadLeader(player.getUUID())
                );
            }

            if (result.team != null) {
                TeamPackManager.getInstance().reconcileTeam(result.team);
                NetworkManager.syncSquadsToTeam(result.team);
            } else {
                TeamPackManager.getInstance().syncTeamPackItem(player);
                NetworkManager.sendSquadSync(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
