package org.espetro.client.gui;

import cc.sighs.auratip.api.action.Actions;
import cc.sighs.auratip.api.client.RadialMenuClientApi;
import cc.sighs.auratip.api.radiamenu.RadialMenuBuilder;
import cc.sighs.auratip.api.radiamenu.RadialMenuRegistry;
import cc.sighs.auratip.client.render.RadialMenuOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.espetro.network.NetworkManager;
import org.espetro.network.VehicleSupplyActionPacket;
import org.espetro.network.VehicleSupplySyncPacket;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * 载具轮盘控制器（按住F呼出，AuraTip 轮盘）。
 *
 * <p>装卸按钮支持按住持续交互：每20tick（1秒）发送一次包。</p>
 */
public final class VehicleWheelController {

    private static final String OWNER = "espetro_vehicle";
    private static final int OPEN_DELAY_TICKS = 6;
    private static final int HOLD_INTERVAL = 20; // tick per second
    private static final double SEARCH_RANGE = 10.0;
    private static final int TOTAL_SLOTS = 6;
    // 轮盘参数（与 RadialMenuBuilder 保持一致）
    private static final int WHEEL_INNER = 44;
    private static final int WHEEL_OUTER = 100;

    private static final ResourceLocation ROOT = id("vehicle_root");
    private static final ResourceLocation ACTION_ID = id("vehicle_action");
    private static final ResourceLocation ICON_LOAD_AMMO = id("textures/gui/squad/fob_status.png");
    private static final ResourceLocation ICON_UNLOAD = id("textures/gui/squad/deposit_supply.png");
    private static final ResourceLocation ICON_RESUPPLY = id("textures/gui/squad/ammo_resupply.png");
    private static final ResourceLocation ICON_CLASS = id("textures/gui/squad/class_select.png");

    // 装卸 slot 名称
    public static final String HOLD_LOAD_AMMO = "LOAD_AMMO";
    public static final String HOLD_UNLOAD_AMMO = "UNLOAD_AMMO";
    public static final String HOLD_LOAD_CONSTR = "LOAD_CONSTRUCTION";
    public static final String HOLD_UNLOAD_CONSTR = "UNLOAD_CONSTRUCTION";

    private static boolean initialized;
    private static boolean keyWasDown;
    private static boolean ownsOverlay;
    private static boolean consumedUntilRelease;
    private static int heldTicks;
    private static UUID currentVehicleId;
    private static VehicleSupplySyncPacket cachedSupply;

    // 按住装卸跟踪
    private static String holdAction;       // 当前按住的 action name（null = 无）
    private static int holdProgress;         // 0..20
    private static int holdTotalInteractions; // 本次按住已完成的交互次数
    private static boolean pendingReopen;    // 等AuraTip关闭后重新打开轮盘

    private VehicleWheelController() {}

    public static boolean isWheelActive() {
        return ownsOverlay || RadialMenuOverlay.INSTANCE.isActive();
    }

    /** 是否正在按住装卸按钮 */
    public static boolean isHolding() {
        return holdAction != null;
    }

    /** 按住进度 (0..20) */
    public static int getHoldProgress() {
        return holdProgress;
    }

    /** 颜色: 弹药=红色, 建材=黄色 */
    public static int getHoldColor() {
        if (holdAction == null) return 0xFFCC4444;
        return holdAction.contains("AMMO") ? 0xFFCC4444 : 0xFFCCAA00;
    }

    @Nullable
    public static VehicleSupplySyncPacket getCachedSupply() {
        return cachedSupply;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("espetro", path);
    }

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        // 装卸动作 → 转为按住模式，不直接发包
        Actions.register(ACTION_ID, params -> {
            String actionName = params.getString("action", "");
            if (actionName.isEmpty() || currentVehicleId == null) return;

            if (HOLD_LOAD_AMMO.equals(actionName) || HOLD_UNLOAD_AMMO.equals(actionName)
                || HOLD_LOAD_CONSTR.equals(actionName) || HOLD_UNLOAD_CONSTR.equals(actionName)) {
                // 进入按住模式：仅设状态，由 tick() 负责重建轮盘
                holdAction = actionName;
                holdProgress = 0;
                holdTotalInteractions = 0;
                sendSupplyAction(actionName);
                consumedUntilRelease = true;
                ownsOverlay = true;
                pendingReopen = true;
                return;
            }

            // RESUPPLY / CHANGE_CLASS → 即时单次
            try {
                VehicleSupplyActionPacket.Action action =
                    VehicleSupplyActionPacket.Action.valueOf(actionName);
                if (action == VehicleSupplyActionPacket.Action.CHANGE_CLASS) {
                    RadioRadialController.markNextClassListAsVehicle(currentVehicleId);
                }
                NetworkManager.NET.sendToServer(
                    new VehicleSupplyActionPacket(currentVehicleId, action));
            } catch (IllegalArgumentException ignored) {}
            consumedUntilRelease = true;
            ownsOverlay = false;
        });

        publishMenus();
    }

    private static void sendSupplyAction(String actionName) {
        try {
            VehicleSupplyActionPacket.Action action =
                VehicleSupplyActionPacket.Action.valueOf(actionName);
            NetworkManager.NET.sendToServer(
                new VehicleSupplyActionPacket(currentVehicleId, action));
            holdTotalInteractions++;
        } catch (IllegalArgumentException ignored) {}
    }

    private static void publishMenus() {
        List<cc.sighs.auratip.data.RadialMenuData> menus = new ArrayList<>();
        menus.add(buildRootMenu());
        RadialMenuRegistry.setMenus(OWNER, menus);
    }

    private static cc.sighs.auratip.data.RadialMenuData buildRootMenu() {
        return new RadialMenuBuilder(ROOT)
            .radii(WHEEL_INNER, WHEEL_OUTER)
            .animationSpeed(1.25f)
            .ringColors(List.of("#E6141719", "#F02A2D2F"))
            .slot("espetro.veh.load_ammo", ICON_LOAD_AMMO,
                Actions.script(ACTION_ID, Map.of("action", HOLD_LOAD_AMMO)),
                Component.literal("装载弹药"), "#FFCC4444")
            .slot("espetro.veh.unload_ammo", ICON_UNLOAD,
                Actions.script(ACTION_ID, Map.of("action", HOLD_UNLOAD_AMMO)),
                Component.literal("卸载弹药"), "#FFCC4444")
            .slot("espetro.veh.load_constr", ICON_LOAD_AMMO,
                Actions.script(ACTION_ID, Map.of("action", HOLD_LOAD_CONSTR)),
                Component.literal("装载建材"), "#FFCCAA00")
            .slot("espetro.veh.unload_constr", ICON_UNLOAD,
                Actions.script(ACTION_ID, Map.of("action", HOLD_UNLOAD_CONSTR)),
                Component.literal("卸载建材"), "#FFCCAA00")
            .slot("espetro.veh.resupply", ICON_RESUPPLY,
                Actions.script(ACTION_ID, Map.of("action", "RESUPPLY_INFANTRY")),
                Component.literal("补给步兵"), "#FF4488CC")
            .slot("espetro.veh.class", ICON_CLASS,
                Actions.script(ACTION_ID, Map.of("action", "CHANGE_CLASS")),
                Component.literal("更换职业"), "#FF44AA44")
            .build();
    }

    // ==================== Tick ====================

    public static void updateSupply(VehicleSupplySyncPacket pkt) {
        if (pkt.isRequest()) return;
        cachedSupply = pkt;
        publishMenus();
        // 仅在轮盘已激活时刷新显示，避免远程同步包意外弹出轮盘
        if (!ownsOverlay && !RadialMenuOverlay.INSTANCE.isActive()) return;
        RadialMenuOverlay.INSTANCE.close();
        RadialMenuClientApi.open(ROOT);
        ownsOverlay = true;
    }

    public static void tick(Minecraft mc) {
        if (!initialized) return;
        if (mc == null || mc.player == null) { reset(); return; }

        if (mc.screen != null) {
            if (ownsOverlay) { RadialMenuOverlay.INSTANCE.close(); ownsOverlay = false; }
            keyWasDown = false; heldTicks = 0; consumedUntilRelease = false;
            holdAction = null; holdProgress = 0;
            pendingReopen = false;
            return;
        }

        long handle = mc.getWindow().getWindow();
        boolean fDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F) == GLFW.GLFW_PRESS;

        // F 释放 → 关闭
        if (!fDown) {
            if (keyWasDown && ownsOverlay) {
                RadialMenuOverlay.INSTANCE.close();
                ownsOverlay = false;
            }
            keyWasDown = false; heldTicks = 0; consumedUntilRelease = false;
            holdAction = null; holdProgress = 0;
            pendingReopen = false;
            currentVehicleId = null;
            return;
        }

        // F 刚按下 → 查找载具
        if (!keyWasDown) {
            currentVehicleId = findNearbyVehicle(mc);
            if (currentVehicleId == null) { keyWasDown = true; return; }
            NetworkManager.NET.sendToServer(
                new VehicleSupplySyncPacket(currentVehicleId, -1, -1, -1, false));
        }
        keyWasDown = true;

        if (consumedUntilRelease) {
            // AuraTip 关闭后需要重新打开轮盘（按住装卸模式）
            if (!RadialMenuOverlay.INSTANCE.isActive() && pendingReopen) {
                pendingReopen = false;
                publishMenus();
                RadialMenuClientApi.open(ROOT);
                return;
            }
            // 轮盘已打开，处理按住装卸
            if (ownsOverlay || RadialMenuOverlay.INSTANCE.isActive()) {
                tickHold(mc, handle);
            }
            return;
        }

        if (ownsOverlay || RadialMenuOverlay.INSTANCE.isActive()) return;

        heldTicks++;
        if (heldTicks >= OPEN_DELAY_TICKS) {
            RadialMenuClientApi.open(ROOT);
            ownsOverlay = true;
        }
    }

    /** 处理装卸按钮的按住逻辑 */
    private static void tickHold(Minecraft mc, long handle) {
        int slotIdx = getSlotUnderCursor(mc);
        boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        // 判断当前光标是否位于装卸 slot 上
        boolean onHoldSlot = false;
        if (slotIdx >= 0 && slotIdx < 4) { // 前4个slot是装卸
            onHoldSlot = true;
        }

        if (!onHoldSlot || !leftDown) {
            // 不再按住装卸 → 清除状态
            holdAction = null;
            holdProgress = 0;
            return;
        }

        // 确定当前 action 名称
        String currentSlotAction = switch (slotIdx) {
            case 0 -> HOLD_LOAD_AMMO;
            case 1 -> HOLD_UNLOAD_AMMO;
            case 2 -> HOLD_LOAD_CONSTR;
            case 3 -> HOLD_UNLOAD_CONSTR;
            default -> null;
        };

        // 如果切换了 slot，重置
        if (holdAction != null && !holdAction.equals(currentSlotAction)) {
            holdProgress = 0;
            holdTotalInteractions = 0;
        }
        holdAction = currentSlotAction;
        holdProgress++;

        if (holdProgress >= HOLD_INTERVAL) {
            holdProgress = 0;
            sendSupplyAction(holdAction);
        }
    }

    /** 获取光标所在的轮盘 slot 索引（0..TOTAL_SLOTS-1），-1 表示不在任何 slot 上 */
    private static int getSlotUnderCursor(Minecraft mc) {
        long handle = mc.getWindow().getWindow();
        double[] rawX = new double[1], rawY = new double[1];
        GLFW.glfwGetCursorPos(handle, rawX, rawY);

        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();
        int winW = mc.getWindow().getWidth();
        int winH = mc.getWindow().getHeight();

        double guiX = rawX[0] * guiW / winW;
        double guiY = rawY[0] * guiH / winH;

        double cx = guiW / 2.0;
        double cy = guiH / 2.0;
        double dx = guiX - cx;
        double dy = guiY - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist < WHEEL_INNER || dist > WHEEL_OUTER) return -1;

        // angle: -180..180, 0 = right, 90 = up (screen coords, Y inverted)
        double angleDeg = Math.toDegrees(Math.atan2(-dy, dx));
        if (angleDeg < 0) angleDeg += 360;
        // AuraTip 第一个 slot 从顶部 (90°) 开始顺时针
        // slot 0: 90°, slot 1: 30°, slot 2: -30°(=330°), slot 3: -90°(=270°), slot 4: -150°(=210°), slot 5: -210°(=150°)
        // 即: 90, 30, 330, 270, 210, 150 (顺时针)
        double[] slotAngles = {90, 30, 330, 270, 210, 150};
        int best = -1;
        double bestDiff = 60;
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            double diff = Math.abs(angleDeg - slotAngles[i]);
            if (diff > 180) diff = 360 - diff;
            if (diff < bestDiff && diff < 30) { // 每段 60°，±30°
                best = i;
                bestDiff = diff;
            }
        }
        return best;
    }

    private static void reset() {
        keyWasDown = false; heldTicks = 0; consumedUntilRelease = false;
        ownsOverlay = false;
        holdAction = null; holdProgress = 0;
        pendingReopen = false;
    }

    // ==================== 客户端载具查找 ====================

    private static UUID findNearbyVehicle(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        Entity ridden = mc.player.getVehicle();
        if (ridden != null && isVehicleEntity(ridden)) return ridden.getUUID();
        Vec3 pos = mc.player.position();
        AABB box = new AABB(pos.x - SEARCH_RANGE, pos.y - SEARCH_RANGE, pos.z - SEARCH_RANGE,
            pos.x + SEARCH_RANGE, pos.y + SEARCH_RANGE, pos.z + SEARCH_RANGE);
        double best = Double.MAX_VALUE;
        UUID bestId = null;
        for (Entity e : mc.level.getEntities(mc.player, box, VehicleWheelController::isVehicleEntity)) {
            double d = e.distanceToSqr(mc.player);
            if (d < best) { best = d; bestId = e.getUUID(); }
        }
        return bestId;
    }

    private static boolean isVehicleEntity(Entity e) {
        for (String tag : e.getTags()) {
            if (tag.startsWith("espetro_team_")) return true;
        }
        String name = e.getType().getDescriptionId();
        return name.contains("dragonrise") || name.contains("fcp") || name.contains("vehicle");
    }
}
