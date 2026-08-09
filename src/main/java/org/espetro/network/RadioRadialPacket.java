package org.espetro.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.bastion.BastionEventHandler;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.SquadManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 弹药箱职业轮盘：
 * <ul>
 *   <li>C→S OPEN：校验弹药箱附近己方 Radio，回推职业列表并让客户端开轮盘</li>
 *   <li>C→S RESUPPLY：附近己方 Radio/换装区重发当前职业装备</li>
 *   <li>S→C CLASS_LIST：职业图标、人数、上限、可用状态及装备变体</li>
 * </ul>
 */
public class RadioRadialPacket {

    public enum Kind {
        OPEN_REQUEST,
        RESUPPLY,
        CLASS_LIST
    }

    public static class ClassEntry {
        public final String classId;
        public final String name;
        /** jar 内 roles 短名，如 rifleman / leader */
        public final String icon;
        /** 可选：磁盘 IconImage 完整路径（客户端用 DynamicTexture 加载） */
        public final String iconImage;
        /** Radio 快捷选择使用的默认变体；避免多变体职业因空 ID 被拒绝。 */
        public final String defaultVariantId;
        /** 与 J 键职业按钮相同的小队作用域人数和上限。 */
        public final int currentCount;
        public final int maxCount;
        public final boolean showCount;
        public final boolean enabled;
        /** 冷却禁用使用灰色，其它门槛/满员禁用使用红色。 */
        public final boolean cooldownBlocked;
        public final String denialMessage;
        public final List<VariantEntry> variants;

        public ClassEntry(String classId, String name, String icon) {
            this(classId, name, icon, "", "", 0, 0,
                false, false, false, "", List.of());
        }

        public ClassEntry(String classId, String name, String icon, String iconImage,
                          String defaultVariantId, int currentCount, int maxCount,
                          boolean showCount, boolean enabled, boolean cooldownBlocked,
                          String denialMessage, List<VariantEntry> variants) {
            this.classId = classId == null ? "" : classId;
            this.name = name == null ? "" : name;
            this.icon = icon == null ? "" : icon;
            this.iconImage = iconImage == null ? "" : iconImage;
            this.defaultVariantId = defaultVariantId == null ? "" : defaultVariantId;
            this.currentCount = Math.max(0, currentCount);
            this.maxCount = Math.max(0, maxCount);
            this.showCount = showCount;
            this.enabled = enabled;
            this.cooldownBlocked = cooldownBlocked;
            this.denialMessage = denialMessage == null ? "" : denialMessage;
            this.variants = variants != null ? List.copyOf(variants) : List.of();
        }
    }

    public static class VariantEntry {
        public final String variantId;
        public final String name;
        public final int currentCount;
        public final int maxCount;
        public final boolean strictCount;
        public final boolean enabled;
        public final String denialMessage;

        public VariantEntry(String variantId, String name, int currentCount, int maxCount,
                            boolean strictCount, boolean enabled, String denialMessage) {
            this.variantId = variantId == null ? "" : variantId;
            this.name = name == null ? "" : name;
            this.currentCount = Math.max(0, currentCount);
            this.maxCount = Math.max(0, maxCount);
            this.strictCount = strictCount;
            this.enabled = enabled;
            this.denialMessage = denialMessage == null ? "" : denialMessage;
        }
    }

    private final Kind kind;
    private final BlockPos pos;
    private final List<ClassEntry> classes;

    private RadioRadialPacket(Kind kind, BlockPos pos, List<ClassEntry> classes) {
        this.kind = kind;
        this.pos = pos;
        this.classes = classes != null ? classes : List.of();
    }

    public static RadioRadialPacket openRequest(BlockPos pos) {
        return new RadioRadialPacket(Kind.OPEN_REQUEST, pos, List.of());
    }

    public static RadioRadialPacket resupply(BlockPos pos) {
        return new RadioRadialPacket(Kind.RESUPPLY, pos, List.of());
    }

    public static RadioRadialPacket classList(List<ClassEntry> classes) {
        return classList(BlockPos.ZERO, classes);
    }

    public static RadioRadialPacket classList(BlockPos pos, List<ClassEntry> classes) {
        return new RadioRadialPacket(Kind.CLASS_LIST, pos != null ? pos : BlockPos.ZERO, classes);
    }

    /** 服务端直接为指定来源点（弹药箱等）打开职业选择轮盘。 */
    public static void openClassMenuAt(ServerPlayer player, BlockPos sourcePos) {
        new RadioRadialPacket(Kind.OPEN_REQUEST, sourcePos != null ? sourcePos : BlockPos.ZERO,
            List.of()).handleOpen(player);
    }

    public static RadioRadialPacket read(FriendlyByteBuf buf) {
        Kind kind;
        try {
            kind = Kind.valueOf(buf.readUtf());
        } catch (Exception e) {
            kind = Kind.OPEN_REQUEST;
        }
        BlockPos pos = buf.readBlockPos();
        int n = buf.readVarInt();
        List<ClassEntry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String classId = buf.readUtf();
            String name = buf.readUtf();
            String icon = buf.readUtf();
            String iconImage = buf.readUtf();
            String defaultVariantId = buf.readUtf();
            int currentCount = buf.readVarInt();
            int maxCount = buf.readVarInt();
            boolean showCount = buf.readBoolean();
            boolean enabled = buf.readBoolean();
            boolean cooldownBlocked = buf.readBoolean();
            String denialMessage = buf.readUtf();
            int variantCount = buf.readVarInt();
            List<VariantEntry> variants = new ArrayList<>(variantCount);
            for (int v = 0; v < variantCount; v++) {
                variants.add(new VariantEntry(
                    buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean(), buf.readBoolean(), buf.readUtf()));
            }
            list.add(new ClassEntry(classId, name, icon, iconImage, defaultVariantId,
                currentCount, maxCount, showCount, enabled, cooldownBlocked,
                denialMessage, variants));
        }
        return new RadioRadialPacket(kind, pos, list);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(kind.name());
        buf.writeBlockPos(pos != null ? pos : BlockPos.ZERO);
        buf.writeVarInt(classes.size());
        for (ClassEntry e : classes) {
            buf.writeUtf(e.classId);
            buf.writeUtf(e.name);
            buf.writeUtf(e.icon);
            buf.writeUtf(e.iconImage);
            buf.writeUtf(e.defaultVariantId);
            buf.writeVarInt(e.currentCount);
            buf.writeVarInt(e.maxCount);
            buf.writeBoolean(e.showCount);
            buf.writeBoolean(e.enabled);
            buf.writeBoolean(e.cooldownBlocked);
            buf.writeUtf(e.denialMessage);
            buf.writeVarInt(e.variants.size());
            for (VariantEntry variant : e.variants) {
                buf.writeUtf(variant.variantId);
                buf.writeUtf(variant.name);
                buf.writeVarInt(variant.currentCount);
                buf.writeVarInt(variant.maxCount);
                buf.writeBoolean(variant.strictCount);
                buf.writeBoolean(variant.enabled);
                buf.writeUtf(variant.denialMessage);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> {
                if (kind == Kind.CLASS_LIST) {
                    try {
                        Class.forName("org.espetro.client.gui.RadioRadialController")
                            .getMethod("onClassList", BlockPos.class, List.class)
                            .invoke(null, pos, classes);
                    } catch (Throwable t) {
                        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite
                            && ite.getCause() != null ? ite.getCause() : t;
                        Espetro.LOGGER.warn("RadioRadial client open failed: {}", cause.toString(), cause);
                    }
                }
            });
            context.setPacketHandled(true);
            return;
        }

        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            switch (kind) {
                case OPEN_REQUEST -> handleOpen(player);
                case RESUPPLY -> handleResupply(player);
                default -> {
                }
            }
        });
        context.setPacketHandled(true);
    }

    private void handleOpen(ServerPlayer player) {
        if (!isFriendlyRadioNearby(player, pos)) {
            player.sendSystemMessage(Component.literal("§c附近没有己方 Radio。"));
            return;
        }
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) {
            player.sendSystemMessage(Component.literal("§c你尚未选择编制。"));
            return;
        }
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.ClassKitData[] kits = loader.getClassesForFaction(factionId);
        List<ClassEntry> list = new ArrayList<>();
        ClassCountManager counts = ClassCountManager.getInstance();
        String team = counts.getEffectivePlayerTeam(player.getUUID());
        int squadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
        boolean inSquad = squadId != SquadManager.NO_SQUAD;
        int squadSize = inSquad
            ? SquadManager.getInstance().getSquadMemberUuids(team, squadId).size()
            : 0;
        int cooldown = counts.getClassSwitchCooldownRemaining(player.getUUID());
        if (kits != null) {
            for (FactionDataLoader.ClassKitData kit : kits) {
                if (kit == null) continue;
                int squadCount = counts.getSquadClassCountForViewer(
                    player.getUUID(), team, kit.id);
                int maxCount = kit.teamCount
                    ? Math.max(1, kit.maxPlayers)
                    : kit.maxPerSquad > 0 ? kit.maxPerSquad : Math.max(1, kit.maxPlayers);
                int teamCount = counts.getCount(team, kit.id);
                String denial = "";
                boolean cooldownBlocked = cooldown > 0;
                if (cooldownBlocked) {
                    denial = "职业切换冷却中，还需等待 " + cooldown + " 秒。";
                } else if (!inSquad) {
                    denial = "请先加入班组小队后再选择职业。";
                } else if (kit.teammatesNeed > 0 && squadSize < kit.teammatesNeed) {
                    denial = "小队达到 " + kit.teammatesNeed + " 人后才能选择该职业。";
                } else if (kit.teamCount && squadCount >= kit.maxPlayers) {
                    denial = "本小队该职业人数已满（" + squadCount + "/"
                        + kit.maxPlayers + "）。";
                } else if (!kit.teamCount && teamCount >= kit.maxPlayers) {
                    denial = "该职业全队人数已满（" + teamCount + "/"
                        + kit.maxPlayers + "）。";
                } else if (!kit.teamCount && kit.maxPerSquad > 0
                    && squadCount >= kit.maxPerSquad) {
                    denial = "本小队该职业人数已满（" + squadCount + "/"
                        + kit.maxPerSquad + "）。";
                }
                boolean enabled = denial.isEmpty();
                List<VariantEntry> variants = new ArrayList<>();
                String defaultVariantId = "";
                if (kit.variants != null) {
                    FactionDataLoader.ClassVariantData defaultVariant =
                        kit.variants.get("default");
                    if (defaultVariant != null) {
                        defaultVariantId = defaultVariant.id;
                    } else if (!kit.variants.isEmpty()) {
                        defaultVariantId = kit.variants.values().iterator().next().id;
                    }
                    for (FactionDataLoader.ClassVariantData variant : kit.variants.values()) {
                        int variantCount = kit.teamCount
                            ? counts.countVariantInSquad(
                                team, squadId, kit.id, variant.id)
                            : counts.getVariantCount(team, kit.id, variant.id);
                        boolean variantEnabled = enabled
                            && (!kit.strictCount || variantCount < variant.maxPlayers);
                        String variantDenial = denial;
                        if (enabled && !variantEnabled) {
                            variantDenial = "该装备变体人数已满（" + variantCount + "/"
                                + variant.maxPlayers + "）。";
                        }
                        variants.add(new VariantEntry(
                            variant.id, variant.name, variantCount, variant.maxPlayers,
                            kit.strictCount, variantEnabled, variantDenial));
                    }
                }
                // icon=roles 短名；iconImage=磁盘路径。绝不能把绝对路径塞进 icon
                // （客户端会当 ResourceLocation 解析并崩溃）。
                list.add(new ClassEntry(
                    kit.id,
                    kit.name,
                    kit.icon != null ? kit.icon : "",
                    kit.iconImage != null ? kit.iconImage : "",
                    defaultVariantId,
                    squadCount,
                    maxCount,
                    inSquad,
                    enabled,
                    cooldownBlocked,
                    denial,
                    variants));
            }
        }
        NetworkManager.NET.send(
            PacketDistributor.PLAYER.with(() -> player),
            classList(pos, list));
    }

    private void handleResupply(ServerPlayer player) {
        // 电台补给必须在己方 Radio 旁；统一复用库存与缺口结算（无冷却），
        // 不能用 equipPlayer 整套清包重发绕过 Radio 弹药库存。
        BastionData radio = findFriendlyRadioNearby(player, pos);
        if (radio == null) {
            player.sendSystemMessage(Component.literal("§c请在己方 Radio 附近补充弹药与装备。"));
            return;
        }
        BastionEventHandler.performAmmoResupply(player, radio);
    }

    public static boolean isFriendlyRadioNearby(ServerPlayer player, BlockPos clickPos) {
        return findFriendlyRadioNearby(player, clickPos) != null;
    }

    public static BastionData findFriendlyRadioNearby(ServerPlayer player, BlockPos clickPos) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return null;
        }
        double radius = LogisticsConfig.get().depositRadius + 2.0;
        BlockPos playerPos = player.blockPosition();
        BlockPos anchor = clickPos != null ? clickPos : playerPos;

        // 优先：点击的 Radio 属于己方
        BastionData atClick = BastionManager.getInstance().findRadioByBlockPos(anchor);
        if (atClick != null && team.equals(atClick.getTeam()) && atClick.isActive()) {
            return playerPos.closerThan(atClick.getPosition(), radius)
                || playerPos.closerThan(anchor, radius) ? atClick : null;
        }

        BastionData nearest = BastionManager.getInstance().findNearestRadio(
            player.serverLevel(), anchor, team, radius);
        if (nearest == null) {
            return null;
        }
        BlockPos radioPos = nearest.getPosition();
        return radioPos != null && playerPos.closerThan(radioPos, radius) ? nearest : null;
    }
}
