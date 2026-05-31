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

    public GamePhaseSyncPacket(GamePhase phase) {
        this.phaseName = phase.name();
    }

    public static GamePhaseSyncPacket read(FriendlyByteBuf buf) {
        String phaseName = buf.readUtf();
        try {
            return new GamePhaseSyncPacket(GamePhase.valueOf(phaseName));
        } catch (IllegalArgumentException e) {
            return new GamePhaseSyncPacket(GamePhase.WAITING_FOR_PLAYERS);
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(phaseName);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        final String phaseNameRef = this.phaseName;
        
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleGamePhase", String.class)
                    .invoke(null, phaseNameRef);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}