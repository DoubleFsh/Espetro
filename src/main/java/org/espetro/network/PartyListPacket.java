package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.PartyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** S→C 队伍列表同步。包含所有队伍的基本信息。 */
public class PartyListPacket {

    /** 队伍展示信息（不暴露密码明文，仅标记是否有密码）。 */
    public static final class PartyInfo {
        public final UUID partyId;
        public final String ownerName;
        public final int memberCount;
        public final boolean locked;
        public final boolean hasPassword;
        /** 当前客户端玩家是否在此队伍中（由服务端写入）。 */
        public final UUID myPartyId;

        public PartyInfo(UUID partyId, String ownerName, int memberCount, boolean locked,
                         boolean hasPassword, UUID myPartyId) {
            this.partyId = partyId;
            this.ownerName = ownerName;
            this.memberCount = memberCount;
            this.locked = locked;
            this.hasPassword = hasPassword;
            this.myPartyId = myPartyId;
        }
    }

    public final List<PartyInfo> parties;
    public final int maxPartySize;
    /** 当前客户端玩家的队伍 ID，null 表示未加入。 */
    public final UUID myPartyId;
    /** 当前客户端玩家是否是自己队伍的队长。 */
    public final boolean isOwner;

    public PartyListPacket(List<PartyInfo> parties, int maxPartySize, UUID myPartyId, boolean isOwner) {
        this.parties = parties != null ? parties : List.of();
        this.maxPartySize = maxPartySize;
        this.myPartyId = myPartyId;
        this.isOwner = isOwner;
    }

    public static PartyListPacket from(PartyManager pm, UUID viewerId) {
        List<PartyInfo> list = new ArrayList<>();
        UUID myPartyId = null;
        boolean isOwner = false;
        for (PartyManager.PartyData p : pm.getParties()) {
            boolean isViewerOwner = p.ownerId.equals(viewerId);
            boolean viewerInParty = p.members.contains(viewerId);
            if (viewerInParty) {
                myPartyId = p.partyId;
                isOwner = isViewerOwner;
            }
            list.add(new PartyInfo(p.partyId, p.ownerName, p.members.size(),
                p.locked, p.password != null && !p.password.isEmpty(),
                viewerInParty ? p.partyId : null));
        }
        return new PartyListPacket(list, PartyManager.getMaxPartySize(), myPartyId, isOwner);
    }

    public static PartyListPacket read(FriendlyByteBuf buf) {
        int maxSize = buf.readVarInt();
        int n = buf.readVarInt();
        List<PartyInfo> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            String owner = buf.readUtf(32);
            int count = buf.readVarInt();
            boolean locked = buf.readBoolean();
            boolean hasPw = buf.readBoolean();
            UUID myPid = buf.readBoolean() ? buf.readUUID() : null;
            list.add(new PartyInfo(id, owner, count, locked, hasPw, myPid));
        }
        UUID myPid = buf.readBoolean() ? buf.readUUID() : null;
        boolean isOwner = buf.readBoolean();
        return new PartyListPacket(list, maxSize, myPid, isOwner);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(maxPartySize);
        buf.writeVarInt(parties.size());
        for (PartyInfo p : parties) {
            buf.writeUUID(p.partyId);
            buf.writeUtf(p.ownerName, 32);
            buf.writeVarInt(p.memberCount);
            buf.writeBoolean(p.locked);
            buf.writeBoolean(p.hasPassword);
            buf.writeBoolean(p.myPartyId != null);
            if (p.myPartyId != null) buf.writeUUID(p.myPartyId);
        }
        buf.writeBoolean(myPartyId != null);
        if (myPartyId != null) buf.writeUUID(myPartyId);
        buf.writeBoolean(isOwner);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handlePartyList", PartyListPacket.class)
                    .invoke(null, this);
            } catch (Exception ignored) {
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
