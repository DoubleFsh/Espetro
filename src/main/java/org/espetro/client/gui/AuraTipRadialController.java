package org.espetro.client.gui;

import cc.sighs.auratip.api.action.Actions;
import cc.sighs.auratip.api.client.RadialMenuClientApi;
import cc.sighs.auratip.api.radiamenu.RadialMenuBuilder;
import cc.sighs.auratip.api.radiamenu.RadialMenuRegistry;
import cc.sighs.auratip.client.render.RadialMenuOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.NetworkManager;
import org.espetro.network.RadialActionPacket;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;

/**
 * Hold-key state machine for Espetro's AuraTip tactical radial.
 */
public final class AuraTipRadialController {

    private static final String OWNER = "espetro";
    private static final int OPEN_DELAY_TICKS = 6;

    private static final ResourceLocation ROOT_MENU = id("tactical_root");
    private static final ResourceLocation DEPLOY_MENU = id("tactical_deploy");
    private static final ResourceLocation LOGISTICS_MENU = id("tactical_logistics");
    private static final ResourceLocation OPEN_SUBMENU_ACTION = id("open_tactical_submenu");
    private static final ResourceLocation EXECUTE_ACTION = id("execute_tactical_action");

    private static final ResourceLocation RADIO =
        id("textures/gui/squad/radio.png");
    private static final ResourceLocation RALLY =
        id("textures/gui/squad/rally.png");
    private static final ResourceLocation CONSTRUCTION =
        id("textures/gui/squad/construction_supply.png");
    private static final ResourceLocation AMMO =
        id("textures/gui/squad/ammo_supply.png");

    private static boolean initialized;
    private static boolean keyWasDown;
    private static boolean ownsOverlay;
    private static boolean submenuActive;
    private static boolean consumedUntilRelease;
    private static int heldTicks;
    private static ResourceLocation pendingMenu;

    private AuraTipRadialController() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        Actions.register(OPEN_SUBMENU_ACTION, params -> {
            String menu = params.getString("menu", "");
            pendingMenu = switch (menu) {
                case "deploy" -> DEPLOY_MENU;
                case "logistics" -> LOGISTICS_MENU;
                default -> null;
            };
            submenuActive = false;
        });
        Actions.register(EXECUTE_ACTION, params -> {
            try {
                RadialActionPacket.Action action = RadialActionPacket.Action.valueOf(
                    params.getString("action", ""));
                NetworkManager.sendRadialAction(action);
            } catch (IllegalArgumentException ignored) {
                return;
            }
            consumedUntilRelease = true;
            ownsOverlay = false;
            submenuActive = false;
            pendingMenu = null;
        });

        RadialMenuRegistry.setMenus(OWNER, List.of(
            rootMenu(),
            deployMenu(),
            logisticsMenu()
        ));
    }

    public static void tick(Minecraft minecraft, KeyMapping key) {
        if (!initialized || minecraft == null || key == null || minecraft.player == null) {
            reset(false);
            return;
        }

        boolean down = key.isDown();
        if (!down) {
            if (keyWasDown) {
                finishSelection(minecraft);
            }
            keyWasDown = false;
            heldTicks = 0;
            consumedUntilRelease = false;
            return;
        }

        keyWasDown = true;
        if (consumedUntilRelease) {
            return;
        }
        if (minecraft.screen != null) {
            closeOwnedOverlay();
            heldTicks = 0;
            return;
        }

        if (pendingMenu != null) {
            if (!RadialMenuOverlay.INSTANCE.isActive()) {
                ResourceLocation next = pendingMenu;
                pendingMenu = null;
                RadialMenuClientApi.open(next);
                ownsOverlay = true;
                submenuActive = true;
            }
            return;
        }

        if (ownsOverlay || RadialMenuOverlay.INSTANCE.isActive()) {
            return;
        }

        heldTicks++;
        if (heldTicks >= OPEN_DELAY_TICKS) {
            RadialMenuClientApi.open(ROOT_MENU);
            ownsOverlay = true;
            submenuActive = false;
        }
    }

    private static void finishSelection(Minecraft minecraft) {
        if (!ownsOverlay) {
            reset(false);
            return;
        }

        if (submenuActive && RadialMenuOverlay.INSTANCE.isActive()) {
            double mouseX = minecraft.mouseHandler.xpos()
                * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth();
            double mouseY = minecraft.mouseHandler.ypos()
                * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight();
            RadialMenuOverlay.INSTANCE.mouseClicked(
                mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        } else {
            closeOwnedOverlay();
        }
        reset(true);
    }

    private static void closeOwnedOverlay() {
        if (ownsOverlay && RadialMenuOverlay.INSTANCE.isActive()) {
            RadialMenuOverlay.INSTANCE.close();
        }
        ownsOverlay = false;
        submenuActive = false;
        pendingMenu = null;
    }

    private static void reset(boolean keepConsumed) {
        heldTicks = 0;
        ownsOverlay = false;
        submenuActive = false;
        pendingMenu = null;
        if (!keepConsumed) {
            consumedUntilRelease = false;
        }
    }

    private static cc.sighs.auratip.data.RadialMenuData rootMenu() {
        return base(ROOT_MENU)
            .slot("espetro.deploy", RADIO,
                Actions.script(OPEN_SUBMENU_ACTION, Map.of("menu", "deploy")),
                Component.translatable("radial.espetro.deploy"), "#FFD5B25C")
            .slot("espetro.logistics", CONSTRUCTION,
                Actions.script(OPEN_SUBMENU_ACTION, Map.of("menu", "logistics")),
                Component.translatable("radial.espetro.logistics"), "#FF6EA07A")
            .build();
    }

    private static cc.sighs.auratip.data.RadialMenuData deployMenu() {
        return base(DEPLOY_MENU)
            .slot("espetro.radio", RADIO, action(RadialActionPacket.Action.DEPLOY_RADIO),
                Component.translatable("radial.espetro.radio"), "#FFD5B25C")
            .slot("espetro.rally", RALLY, action(RadialActionPacket.Action.DEPLOY_RALLY),
                Component.translatable("radial.espetro.rally"), "#FF7DAE82")
            .build();
    }

    private static cc.sighs.auratip.data.RadialMenuData logisticsMenu() {
        return base(LOGISTICS_MENU)
            .slot("espetro.deposit", CONSTRUCTION,
                action(RadialActionPacket.Action.DEPOSIT_SUPPLIES),
                Component.translatable("radial.espetro.deposit"), "#FFD5B25C")
            .slot("espetro.fob_status", AMMO, action(RadialActionPacket.Action.FOB_STATUS),
                Component.translatable("radial.espetro.fob_status"), "#FF6B9DB5")
            .build();
    }

    private static RadialMenuBuilder base(ResourceLocation menuId) {
        return new RadialMenuBuilder(menuId)
            .radii(44, 96)
            .animationSpeed(1.25f)
            .ringColors(List.of("#E6141719", "#F02A2D2F"));
    }

    private static cc.sighs.auratip.data.action.Action action(
            RadialActionPacket.Action action) {
        return Actions.script(EXECUTE_ACTION, Map.of("action", action.name()));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("espetro", path);
    }
}
