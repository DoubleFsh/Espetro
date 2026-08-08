package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.PartyManager;

import java.util.UUID;
import java.util.function.Supplier;

/** C→S 组队操作。 */
public class PartyActionPacket {

    public enum Action {
        CREATE,
        JOIN,
        LEAVE,
        KICK,
        TOGGLE_LOCK,
        DISBAND,
        /** 客户端请求刷新队伍列表（不修改任何状态）。 */
        REQUEST_LIST
    }

    private final Action action;
    private final UUID partyId;
    private final String password;
    private final UUID targetId; // 踢人目标

    public PartyActionPacket(Action action, UUID partyId, String password, UUID targetId) {
        this.action = action;
        this.partyId = partyId;
        this.password = password == null ? "" : password;
        this.targetId = targetId;
    }

    // 工厂方法
    public static PartyActionPacket create(String password) {
        return new PartyActionPacket(Action.CREATE, null, password, null);
    }

    public static PartyActionPacket join(UUID partyId, String password) {
        return new PartyActionPacket(Action.JOIN, partyId, password, null);
    }

    public static PartyActionPacket leave() {
        return new PartyActionPacket(Action.LEAVE, null, null, null);
    }

    public static PartyActionPacket kick(UUID partyId, UUID targetId) {
        return new PartyActionPacket(Action.KICK, partyId, null, targetId);
    }

    public static PartyActionPacket toggleLock(UUID partyId) {
        return new PartyActionPacket(Action.TOGGLE_LOCK, partyId, null, null);
    }

    public static PartyActionPacket disband(UUID partyId) {
        return new PartyActionPacket(Action.DISBAND, partyId, null, null);
    }

    public static PartyActionPacket requestList() {
        return new PartyActionPacket(Action.REQUEST_LIST, null, null, null);
    }

    public static PartyActionPacket read(FriendlyByteBuf buf) {
        Action action = Action.values()[buf.readByte()];
        UUID partyId = buf.readBoolean() ? buf.readUUID() : null;
        String password = buf.readBoolean() ? buf.readUtf(64) : null;
        UUID targetId = buf.readBoolean() ? buf.readUUID() : null;
        return new PartyActionPacket(action, partyId, password, targetId);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeByte(action.ordinal());
        buf.writeBoolean(partyId != null);
        if (partyId != null) buf.writeUUID(partyId);
        buf.writeBoolean(password != null && !password.isEmpty());
        if (password != null && !password.isEmpty()) buf.writeUtf(password, 64);
        buf.writeBoolean(targetId != null);
        if (targetId != null) buf.writeUUID(targetId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PartyManager pm = PartyManager.getInstance();

            switch (action) {
                case CREATE:
                    String err = null;
                    if (pm.isInParty(player.getUUID())) {
                        err = "§c你已在队伍中，请先退出。";
                    } else {
                        pm.createParty(player, password);
                    }
                    if (err != null) player.sendSystemMessage(Component.literal(err));
                    break;

                case JOIN:
                    if (partyId == null) break;
                    String joinErr = pm.joinParty(partyId, player, password);
                    if (joinErr != null) player.sendSystemMessage(Component.literal("§c" + joinErr));
                    break;

                case LEAVE:
                    pm.leaveParty(player.getUUID());
                    break;

                case KICK:
                    if (partyId == null || targetId == null) break;
                    String kickErr = pm.kickMember(partyId, player.getUUID(), targetId);
                    if (kickErr != null) player.sendSystemMessage(Component.literal("§c" + kickErr));
                    break;

                case TOGGLE_LOCK:
                    if (partyId == null) break;
                    String lockErr = pm.toggleLock(partyId, player.getUUID());
                    if (lockErr != null) player.sendSystemMessage(Component.literal("§c" + lockErr));
                    break;

                case DISBAND:
                    if (partyId == null) break;
                    PartyManager.PartyData p = pm.getParty(partyId);
                    if (p != null && p.ownerId.equals(player.getUUID())) {
                        pm.disbandParty(partyId);
                    } else {
                        player.sendSystemMessage(Component.literal("§c只有队长才能解散队伍。"));
                    }
                    break;

                case REQUEST_LIST:
                    pm.syncToPlayer(player);
                    break;
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
