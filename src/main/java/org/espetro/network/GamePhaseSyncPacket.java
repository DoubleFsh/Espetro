package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.GamePhase;

import java.util.function.Supplier;

/**
 * 游戏阶段同步数据包
 * 服务端广播当前游戏阶段到客户端
 */
public class GamePhaseSyncPacket {

    private final String phaseName;
    private final String mapFolder;

    public GamePhaseSyncPacket(GamePhase phase) {
        this(phase, "");
    }

    public GamePhaseSyncPacket(GamePhase phase, String mapFolder) {
        this.phaseName = phase.name();
        this.mapFolder = mapFolder == null ? "" : mapFolder;
    }

    public static GamePhaseSyncPacket read(FriendlyByteBuf buf) {
        String phaseName = buf.readUtf();
        String mapFolder = buf.readUtf();
        try {
            return new GamePhaseSyncPacket(GamePhase.valueOf(phaseName), mapFolder);
        } catch (IllegalArgumentException e) {
            return new GamePhaseSyncPacket(GamePhase.LOBBY, mapFolder);
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(phaseName);
        buf.writeUtf(mapFolder);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        final String phaseNameRef = this.phaseName;
        final String mapFolderRef = this.mapFolder;
        
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleGamePhase", String.class, String.class)
                    .invoke(null, phaseNameRef, mapFolderRef);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public String getPhaseName() { return phaseName; }
    public String getMapFolder() { return mapFolder; }
}
