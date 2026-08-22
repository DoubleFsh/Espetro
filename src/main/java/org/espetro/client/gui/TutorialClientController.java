package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.espetro.network.NetworkManager;
import org.espetro.network.TutorialActionPacket;
import org.espetro.tutorial.TutorialStep;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端教程控制器：只读阶段 GUI 预览 + HUD 叠加 + Enter 下一步。
 * 服务端 {@link org.espetro.tutorial.TutorialManager} 仍为进度权威。
 */
public final class TutorialClientController {

    private static boolean active;
    private static boolean allowSkip = true;
    private static String stepId = "";
    private static int index;
    private static int total;
    private static boolean actionPending;
    private static boolean enterWasDown;

    private TutorialClientController() {
    }

    public static boolean isActive() {
        return active;
    }

    public static String getStepId() {
        return stepId;
    }

    public static int getIndex() {
        return index;
    }

    public static int getTotal() {
        return total;
    }

    public static boolean isAllowSkip() {
        return allowSkip;
    }

    public static void show(String newStepId, int newIndex, int newTotal, boolean newAllowSkip) {
        active = true;
        actionPending = false;
        stepId = newStepId == null ? "" : newStepId;
        index = newIndex;
        total = newTotal;
        allowSkip = newAllowSkip;
        TutorialHudOverlay.onStepChanged();
        openPreviewForStep(TutorialStep.byId(stepId));
    }

    public static void clear() {
        boolean wasActive = active;
        active = false;
        actionPending = true;
        enterWasDown = false;
        stepId = "";
        index = 0;
        total = 0;
        TutorialHudOverlay.clear();
        if (wasActive) {
            closePreviewScreen();
        }
    }

    /** 客户端 tick：无 Screen 时仍可用 Enter 推进（聊天打开时不拦截）。 */
    public static void tick() {
        if (!active || actionPending) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        if (mc.screen instanceof ChatScreen) {
            return;
        }
        // 无 Screen 时 Enter 在 keyPressed 路径可能到不了 EspetroMenuScreen；边沿兜底。
        long window = mc.getWindow().getWindow();
        boolean enterDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;
        if (enterDown && !enterWasDown && mc.screen == null) {
            requestNext();
        }
        enterWasDown = enterDown;
    }

    public static boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active || actionPending) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof ChatScreen) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            requestNext();
            return true;
        }
        return false;
    }

    public static void requestNext() {
        if (!active || actionPending) {
            return;
        }
        actionPending = true;
        NetworkManager.NET.sendToServer(TutorialActionPacket.next(stepId));
    }

    public static void requestSkipAll() {
        if (!active) {
            return;
        }
        actionPending = true;
        NetworkManager.NET.sendToServer(TutorialActionPacket.skipAll());
    }

    private static void closePreviewScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof EspetroMenuScreen screen && screen.isTutorialPreviewMode()) {
            mc.setScreen(null);
        }
    }

    private static void openPreviewForStep(TutorialStep step) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || step == null) {
            return;
        }
        Screen preview = TutorialPreviewFactory.create(step);
        if (preview == null) {
            // 仅 HUD 步骤：关闭旧预览屏
            if (mc.screen instanceof EspetroMenuScreen screen && screen.isTutorialPreviewMode()) {
                mc.setScreen(null);
            }
            return;
        }
        if (preview instanceof EspetroMenuScreen mutil) {
            mutil.setTutorialPreviewMode(true);
        }
        mc.setScreen(preview);
    }
}
