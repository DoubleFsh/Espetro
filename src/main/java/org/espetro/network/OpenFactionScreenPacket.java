package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 强制打开攻防方选择界面包
 */
public class OpenFactionScreenPacket {

    public OpenFactionScreenPacket() {
    }

    public static OpenFactionScreenPacket read(FriendlyByteBuf buf) {
        return new OpenFactionScreenPacket();
    }

    public void write(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleOpenFactionScreen")
                    .invoke(null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
