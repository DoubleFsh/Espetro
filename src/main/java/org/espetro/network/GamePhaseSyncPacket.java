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
    private final String objectiveMode;

    public GamePhaseSyncPacket(GamePhase phase) {
        this(phase, "");
    }

    public GamePhaseSyncPacket(GamePhase phase, String mapFolder) {
        this(phase, mapFolder, "");
    }

    public GamePhaseSyncPacket(GamePhase phase, String mapFolder, String objectiveMode) {
        this.phaseName = phase.name();
        this.mapFolder = mapFolder == null ? "" : mapFolder;
        this.objectiveMode = objectiveMode == null ? "" : objectiveMode;
    }

    public static GamePhaseSyncPacket read(FriendlyByteBuf buf) {
        String phaseName = buf.readUtf();
        String mapFolder = buf.readUtf();
        String objectiveMode = buf.readUtf();
        try {
            return new GamePhaseSyncPacket(GamePhase.valueOf(phaseName), mapFolder, objectiveMode);
        } catch (IllegalArgumentException e) {
            return new GamePhaseSyncPacket(GamePhase.LOBBY, mapFolder, objectiveMode);
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(phaseName);
        buf.writeUtf(mapFolder);
        buf.writeUtf(objectiveMode);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        final String phaseNameRef = this.phaseName;
        final String mapFolderRef = this.mapFolder;
        final String objectiveModeRef = this.objectiveMode;
        
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleGamePhase", String.class, String.class, String.class)
                    .invoke(null, phaseNameRef, mapFolderRef, objectiveModeRef);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public String getPhaseName() { return phaseName; }
    public String getMapFolder() { return mapFolder; }
    public String getObjectiveMode() { return objectiveMode; }
}
