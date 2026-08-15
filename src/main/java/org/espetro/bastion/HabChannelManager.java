package org.espetro.bastion;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Binary/source compatibility facade for the retired stationary HAB channel.
 * HAB is now a normal JSON v2 + Structure NBT construction preview.
 */
public final class HabChannelManager {

    private static final HabChannelManager INSTANCE = new HabChannelManager();

    private HabChannelManager() {
    }

    public static HabChannelManager getInstance() {
        return INSTANCE;
    }

    public void start(ServerPlayer player, String team) {
        String error = FortificationManager.getInstance().beginPreview(
            player, FortificationManager.BUILTIN_HAB);
        player.sendSystemMessage(Component.literal(error == null
            ? "§e左键确认兵站施工范围，右键取消。" : error));
    }

    public void cancel(UUID playerId, @Nullable String reason) {
        // No channel state remains. Preview cancellation is token-authoritative.
    }

    public void reset() {
    }

    public void tick() {
    }
}
