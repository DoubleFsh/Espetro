package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 复活点选择界面包（S→C）
 * 玩家死亡后推送，列出原部署点 + 可用兵站，替代聊天可点击消息
 */
public class DeployPointSelectPacket {

    private final boolean hasDeployPoint;
    private final String deployPointPos; // "x, y, z"
    private final List<BastionItem> bastions;

    public DeployPointSelectPacket(boolean hasDeployPoint, String deployPointPos, List<BastionItem> bastions) {
        this.hasDeployPoint = hasDeployPoint;
        this.deployPointPos = deployPointPos;
        this.bastions = bastions;
    }

    public DeployPointSelectPacket(FriendlyByteBuf buf) {
        this.hasDeployPoint = buf.readBoolean();
        this.deployPointPos = buf.readUtf();
        int size = buf.readVarInt();
        this.bastions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            bastions.add(new BastionItem(
                buf.readUUID(),
                buf.readUtf(),
                buf.readUtf()  // pos string
            ));
        }
    }

    public static DeployPointSelectPacket read(FriendlyByteBuf buf) {
        return new DeployPointSelectPacket(buf);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(hasDeployPoint);
        buf.writeUtf(deployPointPos);
        buf.writeVarInt(bastions.size());
        for (BastionItem b : bastions) {
            buf.writeUUID(b.id);
            buf.writeUtf(b.name);
            buf.writeUtf(b.pos);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleDeployPointSelect", DeployPointSelectPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                org.espetro.Espetro.LOGGER.error("Failed to handle DeployPointSelectPacket", e);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean hasDeployPoint() { return hasDeployPoint; }
    public String getDeployPointPos() { return deployPointPos; }
    public List<BastionItem> getBastions() { return bastions; }

    /**
     * 单个兵站/复活点信息
     */
    public static class BastionItem {
        public final UUID id;
        public final String name;
        public final String pos; // "x, y, z"

        public BastionItem(UUID id, String name, String pos) {
            this.id = id;
            this.name = name;
            this.pos = pos;
        }
    }
}
