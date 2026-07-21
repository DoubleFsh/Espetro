package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.FactionDataLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 打开职业选择界面数据包
 * 服务端将阵营信息和完整职业列表一起发送给客户端，客户端无需自行加载数据包
 */
public class OpenClassSelectionPacket {

    private final String factionId;
    private final String factionName;
    private final String factionDescription;
    private final String factionIcon;
    private final List<ClassInfo> classes;

    /**
     * 服务端构造 —— 从 FactionDataLoader 读取完整数据
     */
    public OpenClassSelectionPacket(String factionId, FactionDataLoader loader) {
        this.factionId = factionId;
        FactionDataLoader.FactionData faction = loader.getFaction(factionId);
        if (faction != null) {
            this.factionName = faction.name;
            this.factionDescription = faction.description;
            this.factionIcon = faction.icon;
        } else {
            this.factionName = "未知编制";
            this.factionDescription = "";
            this.factionIcon = "?";
        }

        FactionDataLoader.ClassKitData[] kits = loader.getClassesForFaction(factionId);
        this.classes = new ArrayList<>(kits.length);
        for (FactionDataLoader.ClassKitData kit : kits) {
            List<VariantInfo> variants = new ArrayList<>();
            if (kit.variants != null) {
                for (FactionDataLoader.ClassVariantData variant : kit.variants.values()) {
                    variants.add(new VariantInfo(variant.id, variant.name,
                        variant.description, variant.maxPlayers));
                }
            }
            this.classes.add(new ClassInfo(
                kit.id, kit.name, kit.description, kit.role, kit.icon,
                kit.maxPlayers, kit.troopValue, kit.healthBonus, kit.speedBonus, variants
            ));
        }
    }

    /** 客户端反序列化 */
    public static OpenClassSelectionPacket read(FriendlyByteBuf buf) {
        String factionId = buf.readUtf();
        String factionName = buf.readUtf();
        String factionDescription = buf.readUtf();
        String factionIcon = buf.readUtf();

        int count = buf.readVarInt();
        List<ClassInfo> classes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String classId = buf.readUtf();
            String name = buf.readUtf();
            String description = buf.readUtf();
            String role = buf.readUtf();
            String icon = buf.readUtf();
            int maxPlayers = buf.readVarInt();
            int troopValue = buf.readVarInt();
            int healthBonus = buf.readVarInt();
            float speedBonus = buf.readFloat();
            int variantCount = buf.readVarInt();
            List<VariantInfo> variants = new ArrayList<>(variantCount);
            for (int variantIndex = 0; variantIndex < variantCount; variantIndex++) {
                variants.add(new VariantInfo(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt()));
            }
            classes.add(new ClassInfo(
                classId, name, description, role, icon, maxPlayers,
                troopValue, healthBonus, speedBonus, variants
            ));
        }
        return new OpenClassSelectionPacket(factionId, factionName, factionDescription, factionIcon, classes);
    }

    /** 内部构造（反序列化用） */
    private OpenClassSelectionPacket(String factionId, String factionName, String factionDescription,
                                     String factionIcon, List<ClassInfo> classes) {
        this.factionId = factionId;
        this.factionName = factionName;
        this.factionDescription = factionDescription;
        this.factionIcon = factionIcon;
        this.classes = classes;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(factionId);
        buf.writeUtf(factionName);
        buf.writeUtf(factionDescription);
        buf.writeUtf(factionIcon);
        buf.writeVarInt(classes.size());
        for (ClassInfo ci : classes) {
            buf.writeUtf(ci.classId);
            buf.writeUtf(ci.name);
            buf.writeUtf(ci.description);
            buf.writeUtf(ci.role);
            buf.writeUtf(ci.icon == null ? "" : ci.icon);
            buf.writeVarInt(ci.maxPlayers);
            buf.writeVarInt(ci.troopValue);
            buf.writeVarInt(ci.healthBonus);
            buf.writeFloat(ci.speedBonus);
            buf.writeVarInt(ci.variants.size());
            for (VariantInfo variant : ci.variants) {
                buf.writeUtf(variant.variantId);
                buf.writeUtf(variant.name != null ? variant.name : variant.variantId);
                buf.writeUtf(variant.description != null ? variant.description : "");
                buf.writeVarInt(variant.maxPlayers);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleOpenClassSelection", OpenClassSelectionPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // ========== 公共访问器 ==========

    public String getFactionId() { return factionId; }
    public String getFactionName() { return factionName; }
    public String getFactionDescription() { return factionDescription; }
    public String getFactionIcon() { return factionIcon; }
    public List<ClassInfo> getClasses() { return classes; }

    // ========== 职业信息 DTO ==========

    public static class ClassInfo {
        public final String classId;
        public final String name;
        public final String description;
        public final String role;
        public final String icon;
        public final int maxPlayers;
        public final int troopValue;
        public final int healthBonus;
        public final float speedBonus;
        public final List<VariantInfo> variants;

        public ClassInfo(String classId, String name, String description, String role, String icon,
                         int maxPlayers, int troopValue, int healthBonus, float speedBonus,
                         List<VariantInfo> variants) {
            this.classId = classId;
            this.name = name;
            this.description = description;
            this.role = role;
            this.icon = icon;
            this.maxPlayers = maxPlayers;
            this.troopValue = troopValue;
            this.healthBonus = healthBonus;
            this.speedBonus = speedBonus;
            this.variants = variants != null ? variants : new ArrayList<>();
        }
    }

    public static class VariantInfo {
        public final String variantId;
        public final String name;
        public final String description;
        public final int maxPlayers;

        public VariantInfo(String variantId, String name, String description, int maxPlayers) {
            this.variantId = variantId;
            this.name = name;
            this.description = description;
            this.maxPlayers = maxPlayers;
        }
    }
}
