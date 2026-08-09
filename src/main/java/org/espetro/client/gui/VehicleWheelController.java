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
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.espetro.network.NetworkManager;
import org.espetro.network.VehicleSupplyActionPacket;
import org.espetro.network.VehicleSupplySyncPacket;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hold-F AuraTip wheel for the vehicle currently under the crosshair. */
public final class VehicleWheelController {

    private static final String OWNER = "espetro_vehicle";
    private static final int OPEN_DELAY_TICKS = 6;
    private static final double INTERACT_RANGE = 5.0;
    private static final int WHEEL_INNER = 44;
    private static final int WHEEL_OUTER = 100;

    private static final ResourceLocation ROOT = id("vehicle_root");
    private static final ResourceLocation ACTION_ID = id("vehicle_action");
    private static final ResourceLocation ICON_AMMO_WHITE =
        id("textures/gui/squad/ammo_supply_white.png");
    private static final ResourceLocation ICON_AMMO_RED =
        id("textures/gui/squad/ammo_supply.png");
    private static final ResourceLocation ICON_CONSTRUCTION_WHITE =
        id("textures/gui/squad/vehicle_supply_load.png");
    private static final ResourceLocation ICON_CONSTRUCTION_RED =
        id("textures/gui/squad/vehicle_supply_unload.png");
    private static final ResourceLocation ICON_RESUPPLY =
        id("textures/gui/squad/ammo_crate.png");
    private static final String COLOR_LOAD = "#FFFFFFFF";
    private static final String COLOR_UNLOAD = "#FFFF4A4A";

    private static boolean initialized;
    private static boolean keyWasDown;
    private static boolean ownsOverlay;
    private static boolean consumedUntilRelease;
    private static boolean snapshotReady;
    private static boolean pendingMenuPublish;
    private static int heldTicks;
    private static UUID currentVehicleId;
    private static VehicleSupplySyncPacket cachedSupply;

    private static String holdAction;
    private static int holdProgress;
    private static boolean pendingReopen;

    private VehicleWheelController() {
    }

    public static boolean isWheelActive() {
        return ownsOverlay;
    }

    public static boolean isHolding() {
        return holdAction != null;
    }

    public static int getHoldProgress() {
        return holdProgress;
    }

    public static int getHoldColor() {
        return holdAction != null && holdAction.contains("CONSTRUCTION")
            ? 0xFFCCAA00 : 0xFFCC4444;
    }

    @Nullable
    public static VehicleSupplySyncPacket getCachedSupply() {
        return cachedSupply;
    }

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        Actions.register(ACTION_ID, params -> {
            String actionName = params.getString("action", "");
            if (actionName.isEmpty() || currentVehicleId == null) return;
            if (isTransferAction(actionName)) {
                holdAction = actionName;
                holdProgress = 0;
                sendSupplyAction(actionName);
                consumedUntilRelease = true;
                ownsOverlay = true;
                pendingReopen = true;
                return;
            }
            try {
                VehicleSupplyActionPacket.Action action =
                    VehicleSupplyActionPacket.Action.valueOf(actionName);
                if (action == VehicleSupplyActionPacket.Action.CHANGE_CLASS) {
                    RadioRadialController.markNextClassListAsVehicle(currentVehicleId);
                }
                NetworkManager.NET.sendToServer(
                    new VehicleSupplyActionPacket(currentVehicleId, action));
            } catch (IllegalArgumentException ignored) {
                return;
            }
            consumedUntilRelease = true;
            ownsOverlay = false;
        });
    }

    private static boolean isTransferAction(String action) {
        return action.equals("LOAD_AMMO") || action.equals("UNLOAD_AMMO")
            || action.equals("LOAD_CONSTRUCTION") || action.equals("UNLOAD_CONSTRUCTION");
    }

    private static void sendSupplyAction(String actionName) {
        try {
            NetworkManager.NET.sendToServer(new VehicleSupplyActionPacket(
                currentVehicleId, VehicleSupplyActionPacket.Action.valueOf(actionName)));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void publishMenu() {
        if (cachedSupply == null || !cachedSupply.hasAnyAction()) return;
        RadialMenuRegistry.setMenus(OWNER, List.of(buildRootMenu()));
        pendingMenuPublish = false;
    }

    private static cc.sighs.auratip.data.RadialMenuData buildRootMenu() {
        RadialMenuBuilder builder = new RadialMenuBuilder(ROOT)
            .radii(WHEEL_INNER, WHEEL_OUTER)
            .animationSpeed(1.25f)
            .ringColors(List.of("#E6141719", "#F02A2D2F"));

        if (cachedSupply.canTransferAmmo()) {
            builder = builder
                .slot("espetro.veh.load_ammo", ICON_AMMO_WHITE,
                    Actions.script(ACTION_ID, Map.of("action", "LOAD_AMMO")),
                    Component.literal("装载弹药"), COLOR_LOAD)
                .slot("espetro.veh.unload_ammo", ICON_AMMO_RED,
                    Actions.script(ACTION_ID, Map.of("action", "UNLOAD_AMMO")),
                    Component.literal("卸下弹药"), COLOR_UNLOAD);
        }
        if (cachedSupply.canTransferConstruction()) {
            builder = builder
                .slot("espetro.veh.load_construction", ICON_CONSTRUCTION_WHITE,
                    Actions.script(ACTION_ID, Map.of("action", "LOAD_CONSTRUCTION")),
                    Component.literal("装载建材"), COLOR_LOAD)
                .slot("espetro.veh.unload_construction", ICON_CONSTRUCTION_RED,
                    Actions.script(ACTION_ID, Map.of("action", "UNLOAD_CONSTRUCTION")),
                    Component.literal("卸下建材"), COLOR_UNLOAD);
        }
        if (cachedSupply.canResupplyInfantry()) {
            builder = builder.slot("espetro.veh.resupply_infantry", ICON_RESUPPLY,
                Actions.script(ACTION_ID, Map.of("action", "RESUPPLY_INFANTRY")),
                Component.literal("补给步兵"), COLOR_LOAD);
        }
        if (cachedSupply.isSupplyVehicle() || cachedSupply.isFightVehicle()) {
            builder = builder.slot("espetro.veh.change_class", ICON_AMMO_WHITE,
                Actions.script(ACTION_ID, Map.of("action", "CHANGE_CLASS")),
                Component.literal("更换职业"), COLOR_LOAD);
        }
        return builder.build();
    }

    /** Called on the client thread by the packet handler. */
    public static void updateSupply(VehicleSupplySyncPacket packet) {
        if (packet == null || packet.isRequest() || currentVehicleId == null
            || !currentVehicleId.equals(packet.getVehicleId())) return;
        String previousLayout = layoutSignature(cachedSupply);
        cachedSupply = packet;
        snapshotReady = packet.hasAnyAction();
        if (!previousLayout.equals(layoutSignature(packet))) {
            if (ownsOverlay || RadialMenuOverlay.INSTANCE.isActive()) {
                pendingMenuPublish = true;
            } else {
                publishMenu();
            }
        }
    }

    private static String layoutSignature(@Nullable VehicleSupplySyncPacket packet) {
        if (packet == null) return "";
        return (packet.canTransferAmmo() ? "A" : "-")
            + (packet.canTransferConstruction() ? "C" : "-")
            + (packet.canResupplyInfantry() ? "R" : "-")
            + (packet.isSupplyVehicle() ? "S" : "-");
    }

    public static void tick(Minecraft minecraft) {
        if (!initialized || minecraft == null || minecraft.player == null) {
            reset();
            return;
        }
        if (minecraft.screen != null) {
            closeOwnedOverlay();
            keyWasDown = false;
            heldTicks = 0;
            return;
        }

        long window = minecraft.getWindow().getWindow();
        boolean down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F) == GLFW.GLFW_PRESS;
        if (!down) {
            if (keyWasDown) closeOwnedOverlay();
            keyWasDown = false;
            heldTicks = 0;
            consumedUntilRelease = false;
            holdAction = null;
            holdProgress = 0;
            pendingReopen = false;
            currentVehicleId = null;
            cachedSupply = null;
            snapshotReady = false;
            if (pendingMenuPublish && !RadialMenuOverlay.INSTANCE.isActive()) publishMenu();
            return;
        }

        if (!keyWasDown) {
            currentVehicleId = findLookedAtVehicle(minecraft);
            snapshotReady = false;
            cachedSupply = null;
            if (currentVehicleId != null) {
                NetworkManager.NET.sendToServer(VehicleSupplySyncPacket.request(currentVehicleId));
            }
        }
        keyWasDown = true;
        if (currentVehicleId == null) return;

        if (consumedUntilRelease) {
            if (!RadialMenuOverlay.INSTANCE.isActive() && pendingReopen) {
                pendingReopen = false;
                publishMenu();
                RadialMenuClientApi.open(ROOT);
                ownsOverlay = true;
            }
            if (ownsOverlay && RadialMenuOverlay.INSTANCE.isActive()) tickHold(minecraft, window);
            return;
        }
        if (ownsOverlay || RadialMenuOverlay.INSTANCE.isActive()) return;

        heldTicks++;
        if (heldTicks >= OPEN_DELAY_TICKS && snapshotReady) {
            publishMenu();
            RadialMenuClientApi.open(ROOT);
            ownsOverlay = true;
        }
    }

    private static List<String> visibleActions() {
        List<String> actions = new ArrayList<>(5);
        if (cachedSupply != null && cachedSupply.canTransferAmmo()) {
            actions.add("LOAD_AMMO");
            actions.add("UNLOAD_AMMO");
        }
        if (cachedSupply != null && cachedSupply.canTransferConstruction()) {
            actions.add("LOAD_CONSTRUCTION");
            actions.add("UNLOAD_CONSTRUCTION");
        }
        if (cachedSupply != null && cachedSupply.canResupplyInfantry()) {
            actions.add("RESUPPLY_INFANTRY");
        }
        if (cachedSupply != null
            && (cachedSupply.isSupplyVehicle() || cachedSupply.isFightVehicle())) {
            actions.add("CHANGE_CLASS");
        }
        return actions;
    }

    private static void tickHold(Minecraft minecraft, long window) {
        int slot = getSlotUnderCursor(minecraft);
        List<String> actions = visibleActions();
        String action = slot >= 0 && slot < actions.size() ? actions.get(slot) : null;
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT)
            == GLFW.GLFW_PRESS;
        if (action == null || !isTransferAction(action) || !leftDown) {
            holdAction = null;
            holdProgress = 0;
            return;
        }
        if (!action.equals(holdAction)) holdProgress = 0;
        holdAction = action;
        holdProgress++;
        int interval = Math.max(1, cachedSupply == null ? 20 : cachedSupply.getTransferIntervalTicks());
        if (holdProgress >= interval) {
            holdProgress = 0;
            sendSupplyAction(action);
        }
    }

    private static int getSlotUnderCursor(Minecraft minecraft) {
        int slots = visibleActions().size();
        if (slots == 0) return -1;
        long window = minecraft.getWindow().getWindow();
        double[] rawX = new double[1];
        double[] rawY = new double[1];
        GLFW.glfwGetCursorPos(window, rawX, rawY);
        double x = rawX[0] * minecraft.getWindow().getGuiScaledWidth()
            / minecraft.getWindow().getWidth();
        double y = rawY[0] * minecraft.getWindow().getGuiScaledHeight()
            / minecraft.getWindow().getHeight();
        double dx = x - minecraft.getWindow().getGuiScaledWidth() / 2.0;
        double dy = y - minecraft.getWindow().getGuiScaledHeight() / 2.0;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < WHEEL_INNER || distance > WHEEL_OUTER) return -1;

        double angle = Math.toDegrees(Math.atan2(-dy, dx));
        if (angle < 0) angle += 360;
        double step = 360.0 / slots;
        int best = -1;
        double bestDifference = step / 2.0 + 0.01;
        for (int i = 0; i < slots; i++) {
            double center = 90.0 - i * step;
            if (center < 0) center += 360;
            double difference = Math.abs(angle - center);
            if (difference > 180) difference = 360 - difference;
            if (difference < bestDifference) {
                best = i;
                bestDifference = difference;
            }
        }
        return best;
    }

    @Nullable
    private static UUID findLookedAtVehicle(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) return null;
        Vec3 eye = minecraft.player.getEyePosition(1.0F);
        Vec3 look = minecraft.player.getLookAngle();
        Vec3 end = eye.add(look.scale(INTERACT_RANGE));
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
            minecraft.player, eye, end,
            minecraft.player.getBoundingBox().expandTowards(look.scale(INTERACT_RANGE)).inflate(1.0D),
            entity -> entity != minecraft.player && entity.isPickable(),
            INTERACT_RANGE * INTERACT_RANGE);
        if (hit == null) return null;
        Entity root = hit.getEntity().getRootVehicle();
        return root == null || root == minecraft.player ? null : root.getUUID();
    }

    private static void closeOwnedOverlay() {
        if (ownsOverlay && RadialMenuOverlay.INSTANCE.isActive()) {
            RadialMenuOverlay.INSTANCE.close();
        }
        ownsOverlay = false;
    }

    private static void reset() {
        closeOwnedOverlay();
        keyWasDown = false;
        heldTicks = 0;
        consumedUntilRelease = false;
        snapshotReady = false;
        currentVehicleId = null;
        cachedSupply = null;
        holdAction = null;
        holdProgress = 0;
        pendingReopen = false;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("espetro", path);
    }
}
