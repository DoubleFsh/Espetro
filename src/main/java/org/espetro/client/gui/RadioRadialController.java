package org.espetro.client.gui;

import cc.sighs.auratip.api.action.Actions;
import cc.sighs.auratip.api.client.RadialMenuClientApi;
import cc.sighs.auratip.api.radiamenu.RadialMenuBuilder;
import cc.sighs.auratip.api.radiamenu.RadialMenuRegistry;
import cc.sighs.auratip.api.radiamenu.icon.IRadialIcon;
import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.NetworkManager;
import org.espetro.network.RadioRadialPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 右击己方弹药箱 → AuraTip：
 * 根菜单提供「补给步兵」与「更换职业」；更换职业进入职业列表轮盘。
 */
public final class RadioRadialController {

    private static final String OWNER = "espetro_radio";
    private static final ResourceLocation ROOT = id("radio_root");
    private static final ResourceLocation CLASS_MENU = id("radio_classes");
    private static final ResourceLocation NAVIGATE = id("radio_navigate");
    private static final ResourceLocation PICK_CLASS = id("radio_pick_class");
    private static final ResourceLocation DO_ACTION = id("radio_action");

    private static final ResourceLocation ICON_CLASS =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/roles/rifleman.png");
    private static final ResourceLocation ICON_RESUPPLY =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/ammo_supply.png");
    private static final IRadialIcon ICON_BACK = ReturnArrowIcon.INSTANCE;
    private static final ResourceLocation ICON_UNAVAILABLE =
        ResourceLocation.fromNamespaceAndPath(
            "espetro", "textures/gui/commander_skills/unavailable.png");

    private static boolean initialized;
    private static BlockPos lastRadioPos = BlockPos.ZERO;
    private static java.util.UUID pendingVehicleId; // 车辆换职业时非 null
    private static List<RadioRadialPacket.ClassEntry> cachedClasses = List.of();

    /** 由车辆轮盘调用，在下一次职业列表到达时标记为车辆换职 */
    public static void markNextClassListAsVehicle(java.util.UUID vehicleId) {
        pendingVehicleId = vehicleId;
    }

    private RadioRadialController() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        Actions.register(NAVIGATE, params -> {
            String target = params.getString("target", "");
            if ("root".equals(target)) {
                replaceRoot();
            } else if ("classes".equals(target)) {
                replaceMenu(buildClassMenuData());
            } else if (target.startsWith("variants:")) {
                String classId = target.substring("variants:".length());
                cachedClasses.stream().filter(entry -> entry.classId.equals(classId)).findFirst()
                    .ifPresent(entry -> replaceMenu(buildVariantMenuData(entry)));
            }
        });
        Actions.register(PICK_CLASS, params -> {
            String classId = params.getString("classId", "");
            String variantId = params.getString("variantId", "");
            boolean enabled = "true".equals(params.getString("enabled", "false"));
            if (!enabled) {
                String denial = params.getString("denial", "当前无法选择该职业。");
                EspetroTipNotifier.showDenial("无法选择职业", denial);
                return;
            }
            if (!classId.isEmpty()) {
                String faction = ClientGameState.getPlayerFactionId();
                if (pendingVehicleId != null) {
                    // 载具换职业
                    NetworkManager.NET.sendToServer(
                        org.espetro.network.ClassSelectPacket.fromVehicle(
                            faction != null ? faction : "", classId, variantId,
                            pendingVehicleId));
                    pendingVehicleId = null;
                } else {
                    // Radio 换职业
                    NetworkManager.sendRadioClassSelect(
                        faction != null ? faction : "", classId, variantId, lastRadioPos);
                }
            }
        });
        Actions.register(DO_ACTION, params -> {
            String action = params.getString("action", "");
            if ("RESUPPLY".equals(action)) {
                NetworkManager.NET.sendToServer(new org.espetro.network.RequestResupplyCatalogPacket(
                    org.espetro.logistics.resupply.ResupplySourceRef.radio(lastRadioPos)));
            }
        });
        publishMenus();
    }

    /** 客户端右击弹药箱成功后调用：向服务端要职业列表。 */
    public static void requestOpen(BlockPos radioPos) {
        if (!initialized) {
            initialize();
        }
        lastRadioPos = radioPos != null ? radioPos.immutable() : BlockPos.ZERO;
        pendingVehicleId = null; // 清除车辆上下文
        NetworkManager.sendRadioOpen(lastRadioPos);
    }

    /** 收到 S2C 职业列表后打开弹药箱根轮盘（补给步兵 / 更换职业）。 */
    public static void onClassList(net.minecraft.core.BlockPos sourcePos,
                                   List<RadioRadialPacket.ClassEntry> classes) {
        cachedClasses = classes != null ? List.copyOf(classes) : List.of();
        if (sourcePos != null) {
            lastRadioPos = sourcePos.immutable();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        boolean vehicleClassMenu = pendingVehicleId != null;
        if (RadialMenuClientApi.isActive()) {
            replaceMenu(vehicleClassMenu ? buildClassMenuData() : rootMenu());
        } else {
            publishMenus();
            RadialMenuClientApi.open(vehicleClassMenu ? CLASS_MENU : ROOT);
        }
    }

    /** 客户端 END tick：处理 AuraTip 的关闭动画和延迟二/三级菜单导航。 */
    public static void tick(Minecraft mc) {
        // AuraTip replace() makes navigation synchronous; retained for the existing tick hook.
    }

    private static void publishMenus() {
        List<cc.sighs.auratip.data.RadialMenuData> menus =
            new ArrayList<>(2 + cachedClasses.size());
        menus.add(rootMenu());
        menus.add(buildClassMenuData());
        for (RadioRadialPacket.ClassEntry entry : cachedClasses) {
            if (entry != null && entry.variants.size() > 1) {
                menus.add(buildVariantMenuData(entry));
            }
        }
        RadialMenuRegistry.setMenus(OWNER, menus);
    }

    private static cc.sighs.auratip.data.RadialMenuData rootMenu() {
        return new RadialMenuBuilder(ROOT)
            .radii(44, 96)
            .animationSpeed(1.25f)
            .ringColors(List.of("#E6141719", "#F02A2D2F"))
            .persistentSlot("espetro.radio.resupply", ICON_RESUPPLY,
                Actions.script(DO_ACTION, Map.of("action", "RESUPPLY")),
                Component.literal("补给步兵"), "#FFFFFFFF", "#FFFFD54F")
            .persistentSlot("espetro.radio.change_class", ICON_CLASS,
                Actions.script(NAVIGATE, Map.of("target", "classes")),
                Component.literal("更换职业"), "#FFFFFFFF", "#FFFFD54F")
            .build();
    }

    public static void replaceRoot() {
        replaceMenu(rootMenu());
    }

    private static void replaceMenu(cc.sighs.auratip.data.RadialMenuData data) {
        if (!RadialMenuClientApi.replace(data)) {
            publishMenus();
            RadialMenuClientApi.open(data.id());
        }
    }

    private static cc.sighs.auratip.data.RadialMenuData buildClassMenuData() {
        var builder = new RadialMenuBuilder(CLASS_MENU)
            .radii(44, 100)
            .animationSpeed(1.25f)
            .ringColors(List.of("#E6141719", "#F02A2D2F"))
            .persistentSlot("espetro.radio.back", ICON_BACK,
                Actions.script(NAVIGATE, Map.of("target", "root")),
                Component.literal("↩"), "#FF888888");
        if (cachedClasses.isEmpty()) {
            builder = builder.slot("espetro.radio.no_class", ICON_UNAVAILABLE,
                Actions.script(NAVIGATE, Map.of("target", "root")),
                Component.literal("§7无可用职业"), "#FF4A3030");
        } else {
            for (RadioRadialPacket.ClassEntry e : cachedClasses) {
                ResourceLocation icon = resolveClassIcon(e);
                String slotName = "espetro.radio.class." + sanitizeSlotId(e.classId);
                String displayName = e.name != null && !e.name.isBlank() ? e.name : e.classId;
                String count = e.showCount
                    ? " " + (e.enabled ? "§a" : e.cooldownBlocked ? "§7" : "§c")
                        + "[" + e.currentCount + "/" + e.maxCount + "]"
                    : "";
                String nameColor = e.enabled ? "§f" : e.cooldownBlocked ? "§7" : "§c";
                String highlight = e.enabled ? "#FF8CB4D5"
                    : e.cooldownBlocked ? "#FF44484D" : "#FF4A3030";
                if (e.variants.size() > 1 && e.enabled) {
                    builder = builder.persistentSlot(slotName, icon,
                        Actions.script(NAVIGATE,
                            Map.of("target", "variants:" + e.classId)),
                        Component.literal(nameColor + displayName + count), highlight);
                } else {
                    builder = builder.slot(slotName, icon,
                        pickAction(e.classId, e.defaultVariantId, e.enabled, e.denialMessage),
                        Component.literal(nameColor + displayName + count), highlight);
                }
            }
        }
        return builder.build();
    }

    private static cc.sighs.auratip.data.RadialMenuData buildVariantMenuData(
            RadioRadialPacket.ClassEntry entry) {
        var builder = new RadialMenuBuilder(variantMenuId(entry.classId))
            .radii(44, 100)
            .animationSpeed(1.25f)
            .ringColors(List.of("#E6141719", "#F02A2D2F"))
            .persistentSlot("espetro.radio.variant.back", ICON_BACK,
                Actions.script(NAVIGATE, Map.of("target", "classes")),
                Component.literal("↩"), "#FF888888");
        ResourceLocation icon = resolveClassIcon(entry);
        for (RadioRadialPacket.VariantEntry variant : entry.variants) {
            String label = variant.name != null && !variant.name.isBlank()
                ? variant.name : variant.variantId;
            String count = variant.strictCount
                ? " " + (variant.enabled ? "§a" : "§c")
                    + "[" + variant.currentCount + "/" + variant.maxCount + "]"
                : " §a" + variant.currentCount + "人";
            String nameColor = variant.enabled ? "§f" : "§c";
            builder = builder.slot(
                "espetro.radio.variant." + sanitizeSlotId(entry.classId)
                    + "." + sanitizeSlotId(variant.variantId),
                icon,
                pickAction(entry.classId, variant.variantId,
                    variant.enabled, variant.denialMessage),
                Component.literal(nameColor + label + count),
                variant.enabled ? "#FF8CB4D5" : "#FF4A3030");
        }
        return builder.build();
    }

    private static cc.sighs.auratip.data.action.Action pickAction(
            String classId, String variantId, boolean enabled, String denial) {
        return Actions.script(PICK_CLASS, Map.of(
            "classId", classId != null ? classId : "",
            "variantId", variantId != null ? variantId : "",
            "enabled", Boolean.toString(enabled),
            "denial", denial != null ? denial : ""));
    }

    /**
     * 职业图标：磁盘 IconImage 优先（DynamicTexture），否则 roles 短名。
     * 绝不能把绝对路径塞进 ResourceLocation.fromNamespaceAndPath。
     */
    private static ResourceLocation resolveClassIcon(RadioRadialPacket.ClassEntry e) {
        try {
            ResourceLocation loc = RoleIconResources.resolve(
                e != null ? e.iconImage : null,
                e != null ? e.icon : null);
            if (loc != null) {
                return loc;
            }
            // slug 资源不存在时仍尝试 basename（路径当 slug 失败时）
            if (e != null) {
                loc = RoleIconResources.resolveForScoreboard(
                    e.iconImage, e.icon, e.classId);
                if (loc != null) {
                    return loc;
                }
            }
        } catch (Throwable t) {
            org.espetro.Espetro.LOGGER.debug("RadioRadial icon resolve failed: {}", t.toString());
        }
        return ICON_CLASS;
    }

    private static String sanitizeSlotId(String classId) {
        if (classId == null || classId.isBlank()) {
            return "unknown";
        }
        return classId.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static ResourceLocation variantMenuId(String classId) {
        String safe = sanitizeSlotId(classId).toLowerCase(Locale.ROOT);
        return id("radio_variants/" + safe);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("espetro", path);
    }

    /** 无需额外 PNG：直接用 Minecraft 字体绘制回车箭头，随 AuraTip 动画缩放。 */
    private static final class ReturnArrowIcon implements IRadialIcon {
        private static final ReturnArrowIcon INSTANCE = new ReturnArrowIcon();
        private static final Codec<ReturnArrowIcon> CODEC = Codec.unit(INSTANCE);

        @Override
        public void render(GuiGraphics graphics, int x, int y, float scale, float alpha) {
            if (scale <= 0.01f || alpha <= 0.01f) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            String glyph = "↩";
            float glyphScale = 1.75f * scale;
            int opacity = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
            int color = (opacity << 24) | 0x00FFFFFF;
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(glyphScale, glyphScale, 1.0f);
            graphics.drawString(
                mc.font, glyph, -mc.font.width(glyph) / 2,
                -mc.font.lineHeight / 2, color, false);
            graphics.pose().popPose();
        }

        @Override
        public Codec<? extends IRadialIcon> codec() {
            return CODEC;
        }
    }
}
