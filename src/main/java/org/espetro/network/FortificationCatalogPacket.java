package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.bastion.FortificationConfig;
import org.espetro.bastion.FortificationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Role-filtered global fortification catalogue used by the AuraTip build wheel. */
public final class FortificationCatalogPacket {

    private static final int MAX_ENTRIES = 64;
    private final boolean request;
    private final List<Entry> entries;

    private FortificationCatalogPacket(boolean request, List<Entry> entries) {
        this.request = request;
        this.entries = List.copyOf(entries);
    }

    public static FortificationCatalogPacket request() {
        return new FortificationCatalogPacket(true, List.of());
    }

    public static FortificationCatalogPacket forPlayer(ServerPlayer player) {
        List<Entry> entries = new ArrayList<>();
        for (FortificationConfig.FortificationDef def : FortificationConfig.list()) {
            if (!FortificationManager.canUse(player, def) || entries.size() >= MAX_ENTRIES) continue;
            entries.add(new Entry(def.id, def.displayName, def.icon,
                def.constructionCost, def.ammunitionCost));
        }
        return new FortificationCatalogPacket(false, entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public static FortificationCatalogPacket read(FriendlyByteBuf buf) {
        boolean request = buf.readBoolean();
        if (request) return request();
        int size = Math.min(MAX_ENTRIES, Math.max(0, buf.readVarInt()));
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(
                buf.readUtf(64), buf.readUtf(128), buf.readUtf(256),
                buf.readVarInt(), buf.readVarInt()));
        }
        return new FortificationCatalogPacket(false, entries);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(request);
        if (request) return;
        buf.writeVarInt(Math.min(entries.size(), MAX_ENTRIES));
        for (int i = 0; i < entries.size() && i < MAX_ENTRIES; i++) {
            Entry entry = entries.get(i);
            buf.writeUtf(entry.id(), 64);
            buf.writeUtf(entry.displayName(), 128);
            buf.writeUtf(entry.icon(), 256);
            buf.writeVarInt(Math.max(0, entry.constructionCost()));
            buf.writeVarInt(Math.max(0, entry.ammunitionCost()));
        }
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> sender), forPlayer(sender));
            } else {
                try {
                    Class.forName("org.espetro.client.ClientPacketHandlers")
                        .getMethod("handleFortificationCatalog", FortificationCatalogPacket.class)
                        .invoke(null, this);
                } catch (ReflectiveOperationException e) {
                    org.espetro.Espetro.LOGGER.error("处理工事目录同步失败", e);
                }
            }
        });
        context.setPacketHandled(true);
    }

    public record Entry(String id, String displayName, String icon,
                        int constructionCost, int ammunitionCost) {
    }
}
