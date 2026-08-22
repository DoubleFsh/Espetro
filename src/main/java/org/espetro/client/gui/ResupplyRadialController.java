package org.espetro.client.gui;

import cc.sighs.auratip.api.action.Actions;
import cc.sighs.auratip.api.client.RadialMenuClientApi;
import cc.sighs.auratip.api.radiamenu.RadialMenuBuilder;
import cc.sighs.auratip.api.radiamenu.RadialMenuRegistry;
import cc.sighs.auratip.api.radiamenu.icon.IRadialIcon;
import cc.sighs.auratip.api.radiamenu.icon.ItemIcon;
import cc.sighs.auratip.data.RadialMenuData;
import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.espetro.logistics.resupply.ResupplySourceRef;
import org.espetro.network.CloseResupplySessionPacket;
import org.espetro.network.NetworkManager;
import org.espetro.network.ResupplyCatalogPacket;
import org.espetro.network.ResupplyEntryDeltaPacket;
import org.espetro.network.SelectResupplyEntryPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dynamic, persistent AuraTip pages for server-authoritative per-item resupply. */
public final class ResupplyRadialController {
    private static final String OWNER = "espetro_resupply";
    private static final ResourceLocation MENU = id("resupply/items");
    private static final ResourceLocation SELECT = id("resupply/select");
    private static final ResourceLocation NAVIGATE = id("resupply/navigate");
    private static final int PAGE_SIZE = 5;
    private static final String AVAILABLE = "#FFFFD54F";
    private static final String UNAVAILABLE = "#FFFF4D4D";
    private static final String HOVER = "#FFFFFFFF";

    private static boolean initialized;
    private static UUID token;
    private static long catalogRevision;
    private static long stateRevision;
    private static long nextActionSeq = 1L;
    private static ResupplySourceRef source;
    private static int balance;
    private static int page;
    private static List<ResupplyCatalogPacket.Entry> entries = List.of();
    private static boolean menuWasActive;
    private static long rebuildCount;

    private ResupplyRadialController() {
    }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        Actions.register(SELECT, params -> {
            int index;
            try {
                index = Integer.parseInt(params.getString("index", "-1"));
            } catch (NumberFormatException ignored) {
                return;
            }
            ResupplyCatalogPacket.Entry entry = find(index);
            if (entry == null || token == null || source == null) return;
            if (!entry.selectable()) {
                EspetroTipNotifier.showDenial("无法补给",
                    entry.reason().isBlank() ? "该项目当前不可用。" : entry.reason());
                return;
            }
            NetworkManager.NET.sendToServer(new SelectResupplyEntryPacket(token,
                catalogRevision, nextActionSeq++, index, source));
        });
        Actions.register(NAVIGATE, params -> {
            String action = params.getString("action", "");
            switch (action) {
                case "previous" -> {
                    if (page > 0) page--;
                    replacePage();
                }
                case "next" -> {
                    if (page + 1 < pageCount()) page++;
                    replacePage();
                }
                case "back" -> returnToRoot();
                default -> { }
            }
        });
    }

    public static void onCatalog(ResupplyCatalogPacket packet) {
        initialize();
        token = packet.token();
        catalogRevision = packet.catalogRevision();
        stateRevision = packet.stateRevision();
        source = packet.source();
        balance = packet.balance();
        entries = List.copyOf(packet.entries());
        nextActionSeq = 1L;
        page = 0;
        replacePage();
        menuWasActive = true;
    }

    public static void onDelta(ResupplyEntryDeltaPacket packet) {
        if (token == null || !token.equals(packet.token())) return;
        if (packet.close()) {
            EspetroTipNotifier.showDenial("补给会话已关闭", packet.message());
            if (RadialMenuClientApi.isActive()) {
                cc.sighs.auratip.client.render.RadialMenuOverlay.INSTANCE.close();
            }
            clear(false);
            return;
        }
        if (packet.stateRevision() < stateRevision) return;
        boolean changed = packet.stateRevision() != stateRevision
            || balance != packet.balance();
        stateRevision = packet.stateRevision();
        balance = packet.balance();
        if (packet.entry() != null) {
            ArrayList<ResupplyCatalogPacket.Entry> updated = new ArrayList<>(entries);
            int index = packet.entry().index();
            if (index >= 0 && index < updated.size()
                && !sameView(updated.get(index), packet.entry())) {
                updated.set(index, packet.entry());
                entries = List.copyOf(updated);
                changed = true;
            }
        }
        if (!packet.success() && !packet.message().isBlank()) {
            EspetroTipNotifier.showDenial("无法补给", packet.message());
        }
        if (changed) replacePage();
    }

    public static void tick() {
        if (token == null) return;
        boolean active = RadialMenuClientApi.isActive()
            && RadialMenuClientApi.activeMenuId().filter(MENU::equals).isPresent();
        if (menuWasActive && !active) clear(true);
        menuWasActive = active;
    }

    public static boolean isActive() {
        return token != null && RadialMenuClientApi.activeMenuId().filter(MENU::equals).isPresent();
    }

    /** Test/debug counter: unchanged ticks and duplicate deltas must not rebuild menus. */
    public static long rebuildCount() {
        return rebuildCount;
    }

    private static void replacePage() {
        if (token == null) return;
        RadialMenuData data = buildPage();
        if (!RadialMenuClientApi.replace(data)) {
            RadialMenuRegistry.setMenus(OWNER, List.of(data));
            RadialMenuClientApi.open(MENU);
        }
        rebuildCount++;
        menuWasActive = true;
    }

    private static boolean sameView(ResupplyCatalogPacket.Entry left,
                                    ResupplyCatalogPacket.Entry right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.index() == right.index()
            && left.configuredId().equals(right.configuredId())
            && left.count() == right.count()
            && left.max() == right.max()
            && left.ammoCost() == right.ammoCost()
            && left.current() == right.current()
            && left.selectable() == right.selectable()
            && left.reason().equals(right.reason())
            && ItemStack.isSameItemSameTags(left.icon(), right.icon())
            && left.icon().getCount() == right.icon().getCount();
    }

    private static RadialMenuData buildPage() {
        int pages = pageCount();
        if (page >= pages) page = Math.max(0, pages - 1);
        RadialMenuBuilder builder = new RadialMenuBuilder(MENU)
            .radii(44, 108)
            .animationSpeed(1.25F)
            .ringColors(List.of("#E6141719", "#F02A2D2F"));
        int first = page * PAGE_SIZE;
        int end = Math.min(entries.size(), first + PAGE_SIZE);
        for (int i = first; i < end; i++) {
            ResupplyCatalogPacket.Entry entry = effective(entries.get(i));
            ItemStack iconStack = entry.icon().isEmpty()
                ? new ItemStack(Items.BARRIER) : entry.icon().copy();
            iconStack.setCount(1);
            String itemName = entry.icon().isEmpty() ? entry.configuredId()
                : entry.icon().getHoverName().getString();
            String label = itemName + " §f+" + entry.count() + " §7"
                + entry.current() + "/" + entry.max() + " §b[" + entry.ammoCost() + "]";
            if (!entry.selectable() && !entry.reason().isBlank()) {
                label += " §c" + entry.reason();
            }
            builder = builder.persistentSlot("espetro.resupply.entry." + entry.index(),
                new ItemIcon(iconStack, 1.15F),
                Actions.script(SELECT, Map.of("index", Integer.toString(entry.index()))),
                Component.literal(label), HOVER,
                entry.selectable() ? AVAILABLE : UNAVAILABLE);
        }
        builder = builder.persistentSlot("espetro.resupply.back", GlyphIcon.BACK,
            Actions.script(NAVIGATE, Map.of("action", "back")),
            Component.literal("返回  §b余额 " + balance), "#FFAAAAAA", "#FF44484D");
        if (page > 0) {
            builder = builder.persistentSlot("espetro.resupply.previous", GlyphIcon.PREVIOUS,
                Actions.script(NAVIGATE, Map.of("action", "previous")),
                Component.literal("上一页 " + page + "/" + pages), "#FFFFFFFF", "#FF506070");
        }
        if (page + 1 < pages) {
            builder = builder.persistentSlot("espetro.resupply.next", GlyphIcon.NEXT,
                Actions.script(NAVIGATE, Map.of("action", "next")),
                Component.literal("下一页 " + (page + 2) + "/" + pages), "#FFFFFFFF", "#FF506070");
        }
        return builder.build();
    }

    private static ResupplyCatalogPacket.Entry effective(ResupplyCatalogPacket.Entry entry) {
        if (entry.selectable() && balance < entry.ammoCost()) {
            return new ResupplyCatalogPacket.Entry(entry.index(), entry.icon(), entry.configuredId(),
                entry.count(), entry.max(), entry.ammoCost(), entry.current(), false, "来源弹药不足");
        }
        return entry;
    }

    static int pageCount(int entryCount, int pageSize) {
        int size = Math.max(1, pageSize);
        return Math.max(1, (Math.max(0, entryCount) + size - 1) / size);
    }

    static List<String> pageSlotIds(int entryCount, int pageIndex, int balanceValue) {
        int pages = pageCount(entryCount, PAGE_SIZE);
        int safePage = Math.min(Math.max(0, pageIndex), pages - 1);
        int first = safePage * PAGE_SIZE;
        int end = Math.min(entryCount, first + PAGE_SIZE);
        List<String> ids = new ArrayList<>();
        for (int i = first; i < end; i++) {
            ids.add("espetro.resupply.entry." + i);
        }
        ids.add("espetro.resupply.back");
        if (safePage > 0) {
            ids.add("espetro.resupply.previous");
        }
        if (safePage + 1 < pages) {
            ids.add("espetro.resupply.next");
        }
        return ids;
    }

    private static int pageCount() {
        return pageCount(entries.size(), PAGE_SIZE);
    }

    private static ResupplyCatalogPacket.Entry find(int index) {
        return index >= 0 && index < entries.size() ? effective(entries.get(index)) : null;
    }

    private static void returnToRoot() {
        ResupplySourceRef previousSource = source;
        clear(true);
        if (previousSource == null) return;
        if (previousSource.kind() == ResupplySourceRef.Kind.RADIO) {
            RadioRadialController.replaceRoot();
        } else {
            VehicleWheelController.replaceRoot();
        }
    }

    private static void clear(boolean notifyServer) {
        UUID oldToken = token;
        token = null;
        source = null;
        entries = List.of();
        page = 0;
        menuWasActive = false;
        if (notifyServer && oldToken != null) {
            NetworkManager.NET.sendToServer(new CloseResupplySessionPacket(oldToken));
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("espetro", path);
    }

    private enum GlyphIcon implements IRadialIcon {
        BACK("↩"), PREVIOUS("‹"), NEXT("›");

        private static final Codec<GlyphIcon> CODEC = Codec.STRING.xmap(GlyphIcon::valueOf,
            GlyphIcon::name);
        private final String glyph;

        GlyphIcon(String glyph) {
            this.glyph = glyph;
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y, float scale, float alpha) {
            Minecraft minecraft = Minecraft.getInstance();
            float drawScale = 1.7F * scale;
            int opacity = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(drawScale, drawScale, 1.0F);
            graphics.drawString(minecraft.font, glyph, -minecraft.font.width(glyph) / 2,
                -minecraft.font.lineHeight / 2, (opacity << 24) | 0xFFFFFF, false);
            graphics.pose().popPose();
        }

        @Override
        public Codec<? extends IRadialIcon> codec() {
            return CODEC;
        }
    }
}
