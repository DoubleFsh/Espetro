package org.espetro.network;

import io.netty.handler.codec.DecoderException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.logistics.resupply.ResupplySourceRef;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** S2C initial catalogue; never contains arbitrary client prices or definitions. */
public record ResupplyCatalogPacket(UUID token, long catalogRevision, long stateRevision,
                                    ResupplySourceRef source, int balance,
                                    List<Entry> entries) {
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_TEXT = 512;
    public static final int MAX_ITEM_TAG_CHARS = 32_767;
    public static final int MAX_RESOURCE_LOCATION = 256;
    public static final int MAX_VALUE = 1_000_000;

    public ResupplyCatalogPacket {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("too many entries");
    }

    public record Entry(int index, ItemStack icon, String configuredId, int count, int max,
                        int ammoCost, int current, boolean selectable, String reason) {
        public Entry {
            icon = icon == null ? ItemStack.EMPTY : icon.copy();
            configuredId = bounded(configuredId);
            reason = bounded(reason);
        }

        private static String bounded(String value) {
            String text = value == null ? "" : value;
            return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
        }

        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(index);
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(icon.getItem());
            buf.writeUtf(itemId.toString(), MAX_RESOURCE_LOCATION);
            String tag = icon.hasTag() ? icon.getTag().toString() : "";
            if (tag.length() > MAX_ITEM_TAG_CHARS) {
                throw new IllegalArgumentException("resupply item tag too large");
            }
            buf.writeUtf(tag, MAX_ITEM_TAG_CHARS);
            buf.writeUtf(configuredId, MAX_TEXT);
            buf.writeVarInt(count);
            buf.writeVarInt(max);
            buf.writeVarInt(ammoCost);
            buf.writeVarInt(current);
            buf.writeBoolean(selectable);
            buf.writeUtf(reason, MAX_TEXT);
        }

        static Entry read(FriendlyByteBuf buf) {
            int index = buf.readVarInt();
            ResourceLocation itemId = ResourceLocation.tryParse(
                buf.readUtf(MAX_RESOURCE_LOCATION));
            if (itemId == null) throw new DecoderException("invalid resupply item id");
            Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
            if (item == null) throw new DecoderException("unknown resupply item id");
            ItemStack icon = new ItemStack(item);
            String tag = buf.readUtf(MAX_ITEM_TAG_CHARS);
            if (!tag.isEmpty()) {
                try {
                    icon.setTag(TagParser.parseTag(tag));
                } catch (CommandSyntaxException error) {
                    throw new DecoderException("invalid resupply item tag", error);
                }
            }
            String configuredId = buf.readUtf(MAX_TEXT);
            int count = readBoundedValue(buf, "count");
            int max = readBoundedValue(buf, "max");
            int cost = readBoundedValue(buf, "ammo cost");
            int current = readBoundedValue(buf, "current");
            return new Entry(index, icon, configuredId, count, max, cost, current,
                buf.readBoolean(), buf.readUtf(MAX_TEXT));
        }

        private static int readBoundedValue(FriendlyByteBuf buf, String field) {
            int value = buf.readVarInt();
            if (value < 0 || value > MAX_VALUE) {
                throw new DecoderException("invalid resupply " + field);
            }
            return value;
        }
    }

    public static ResupplyCatalogPacket read(FriendlyByteBuf buf) {
        UUID token = buf.readUUID();
        long catalogue = buf.readLong();
        long state = buf.readLong();
        ResupplySourceRef source = ResupplySourceRef.read(buf);
        int balance = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new DecoderException("invalid resupply entry count " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(Entry.read(buf));
        return new ResupplyCatalogPacket(token, catalogue, state, source, balance, entries);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(token);
        buf.writeLong(catalogRevision);
        buf.writeLong(stateRevision);
        source.write(buf);
        buf.writeVarInt(Math.max(0, balance));
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) entry.write(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleResupplyCatalog", ResupplyCatalogPacket.class)
                    .invoke(null, this);
            } catch (ReflectiveOperationException error) {
                org.espetro.Espetro.LOGGER.error("处理补给目录失败", error);
            }
        });
        context.setPacketHandled(true);
    }
}
