package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class CommanderSkillSyncPacket {

    private final boolean isCommander;
    private final Map<String, Integer> cooldowns;

    public CommanderSkillSyncPacket(boolean isCommander, Map<String, Integer> cooldowns) {
        this.isCommander = isCommander;
        this.cooldowns = cooldowns != null ? cooldowns : new HashMap<>();
    }

    public static CommanderSkillSyncPacket read(FriendlyByteBuf buf) {
        boolean isCommander = buf.readBoolean();
        int size = buf.readVarInt();
        Map<String, Integer> cooldowns = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String key = buf.readUtf();
            int value = buf.readVarInt();
            cooldowns.put(key, value);
        }
        return new CommanderSkillSyncPacket(isCommander, cooldowns);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(isCommander);
        buf.writeVarInt(cooldowns.size());
        for (Map.Entry<String, Integer> entry : cooldowns.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleCommanderSkillSync", CommanderSkillSyncPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean isCommander() {
        return isCommander;
    }

    public Map<String, Integer> getCooldowns() {
        return cooldowns;
    }
}