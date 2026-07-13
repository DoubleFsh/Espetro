package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.CommanderSkillManager;
import org.espetro.team.CommanderSkillType;

import java.util.function.Supplier;

public class CommanderSkillPacket {

    private final String skillId;

    public CommanderSkillPacket(String skillId) {
        this.skillId = skillId != null ? skillId : "";
    }

    public static CommanderSkillPacket query() {
        return new CommanderSkillPacket("");
    }

    public static CommanderSkillPacket activate(CommanderSkillType type) {
        return new CommanderSkillPacket(type.getId());
    }

    public static CommanderSkillPacket read(FriendlyByteBuf buf) {
        String skillId = buf.readUtf(128);
        return new CommanderSkillPacket(skillId);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(skillId.length() <= 128 ? skillId : skillId.substring(0, 128), 128);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (skillId.isEmpty()) {
                NetworkManager.sendCommanderSkillSync(player);
                return;
            }

            CommanderSkillManager.getInstance().activateSkill(player, skillId);
        });
        ctx.get().setPacketHandled(true);
    }
}
