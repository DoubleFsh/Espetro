package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.CommanderSkillManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class CommanderSkillSyncPacket {

    private final boolean isCommander;
    private final Map<String, Integer> cooldowns;
    private final List<CommanderSkillManager.SkillView> skills;

    public CommanderSkillSyncPacket(boolean isCommander, Map<String, Integer> cooldowns) {
        this(isCommander, cooldowns, List.of());
    }

    public CommanderSkillSyncPacket(boolean isCommander, Map<String, Integer> cooldowns,
                                    List<CommanderSkillManager.SkillView> skills) {
        this.isCommander = isCommander;
        this.cooldowns = cooldowns != null ? cooldowns : new HashMap<>();
        this.skills = skills != null ? List.copyOf(skills) : List.of();
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
        int skillCount = buf.readVarInt();
        List<CommanderSkillManager.SkillView> skills = new ArrayList<>(skillCount);
        for (int i = 0; i < skillCount; i++) {
            skills.add(new CommanderSkillManager.SkillView(
                buf.readUtf(128),
                buf.readUtf(128),
                buf.readUtf(512),
                buf.readUtf(512),
                buf.readUtf(256)
            ));
        }
        return new CommanderSkillSyncPacket(isCommander, cooldowns, skills);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(isCommander);
        buf.writeVarInt(cooldowns.size());
        for (Map.Entry<String, Integer> entry : cooldowns.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        buf.writeVarInt(skills.size());
        for (CommanderSkillManager.SkillView skill : skills) {
            buf.writeUtf(limit(skill.id(), 128), 128);
            buf.writeUtf(limit(skill.displayName(), 128), 128);
            buf.writeUtf(limit(skill.description(), 512), 512);
            buf.writeUtf(limit(skill.stats(), 512), 512);
            buf.writeUtf(limit(skill.icon(), 256), 256);
        }
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
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

    public List<CommanderSkillManager.SkillView> getSkills() {
        return skills;
    }
}
