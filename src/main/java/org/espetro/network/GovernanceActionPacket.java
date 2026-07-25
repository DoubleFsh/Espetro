package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.governance.CommanderGovernanceManager;

import java.util.UUID;
import java.util.function.Supplier;

/** C→S governance actions. */
public class GovernanceActionPacket {

    public enum Action {
        START_IMPEACHMENT,
        VOTE_IMPEACHMENT,
        VOLUNTEER_VACANCY,
        VOTE_VACANCY
    }

    private final Action action;
    private final UUID candidate;

    public GovernanceActionPacket(Action action, UUID candidate) {
        this.action = action;
        this.candidate = candidate;
    }

    public static GovernanceActionPacket read(FriendlyByteBuf buf) {
        Action a;
        try {
            a = Action.valueOf(buf.readUtf());
        } catch (Exception e) {
            a = Action.START_IMPEACHMENT;
        }
        UUID c = buf.readBoolean() ? buf.readUUID() : null;
        return new GovernanceActionPacket(a, c);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(action.name());
        buf.writeBoolean(candidate != null);
        if (candidate != null) buf.writeUUID(candidate);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            CommanderGovernanceManager mgr = CommanderGovernanceManager.getInstance();
            switch (action) {
                case START_IMPEACHMENT -> mgr.tryStartImpeachment(player);
                case VOTE_IMPEACHMENT -> {
                    if (candidate != null) mgr.castImpeachmentVote(player, candidate);
                }
                case VOLUNTEER_VACANCY -> mgr.volunteerForVacancy(player);
                case VOTE_VACANCY -> {
                    if (candidate != null) mgr.castVacancyVote(player, candidate);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
