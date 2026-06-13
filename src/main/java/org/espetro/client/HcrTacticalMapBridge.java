package org.espetro.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.espetro.Espetro;

import java.lang.reflect.Method;

/**
 * Optional bridge to HCRpoints' tactical map HUD.
 *
 * Espetro must not compile against HCRpoints because HCRpoints now depends on Espetro.
 */
public final class HcrTacticalMapBridge {
    private static final String HUD_CLASS_NAME = "com.example.hcrpoints.hud.TacticalMapHUD";

    private static Class<?> hudClass;
    private static Method getInstanceMethod;
    private static Method renderEmbeddedMapMethod;
    private static Method increaseRenderRangeMethod;
    private static Method decreaseRenderRangeMethod;
    private static Boolean hcrpointsLoaded;
    private static boolean unavailableLogged;

    private HcrTacticalMapBridge() {
    }

    public static void renderEmbeddedMap(GuiGraphics graphics, int x, int y, int width, int height, float partialTick) {
        if (!isAvailable()) {
            renderFallback(graphics, x, y, width, height);
            return;
        }

        graphics.enableScissor(x, y, x + width, y + height);
        try {
            renderEmbeddedMapMethod().invoke(getHudInstance(), graphics, x, y, width, height, partialTick);
        } catch (Throwable e) {
            renderFallback(graphics, x, y, width, height);
            logUnavailable(e);
        } finally {
            graphics.disableScissor();
        }
    }

    public static void increaseRenderRange() {
        if (!isAvailable()) {
            return;
        }
        invokeRangeMethod(true);
    }

    public static void decreaseRenderRange() {
        if (!isAvailable()) {
            return;
        }
        invokeRangeMethod(false);
    }

    private static void invokeRangeMethod(boolean increase) {
        try {
            Method method = increase ? increaseRenderRangeMethod() : decreaseRenderRangeMethod();
            method.invoke(getHudInstance());
        } catch (Throwable e) {
            logUnavailable(e);
        }
    }

    private static Object getHudInstance() throws ReflectiveOperationException {
        return getInstanceMethod().invoke(null);
    }

    private static Class<?> hudClass() throws ClassNotFoundException {
        if (hudClass == null) {
            hudClass = Class.forName(HUD_CLASS_NAME);
        }
        return hudClass;
    }

    private static boolean isAvailable() {
        if (hcrpointsLoaded == null) {
            hcrpointsLoaded = ModList.get().isLoaded("hcrpoints");
        }
        return hcrpointsLoaded;
    }

    private static Method getInstanceMethod() throws ReflectiveOperationException {
        if (getInstanceMethod == null) {
            getInstanceMethod = hudClass().getMethod("getInstance");
        }
        return getInstanceMethod;
    }

    private static Method renderEmbeddedMapMethod() throws ReflectiveOperationException {
        if (renderEmbeddedMapMethod == null) {
            renderEmbeddedMapMethod = hudClass().getMethod(
                "renderEmbeddedMap",
                GuiGraphics.class,
                int.class,
                int.class,
                int.class,
                int.class,
                float.class
            );
        }
        return renderEmbeddedMapMethod;
    }

    private static Method increaseRenderRangeMethod() throws ReflectiveOperationException {
        if (increaseRenderRangeMethod == null) {
            increaseRenderRangeMethod = hudClass().getMethod("increaseRenderRange");
        }
        return increaseRenderRangeMethod;
    }

    private static Method decreaseRenderRangeMethod() throws ReflectiveOperationException {
        if (decreaseRenderRangeMethod == null) {
            decreaseRenderRangeMethod = hudClass().getMethod("decreaseRenderRange");
        }
        return decreaseRenderRangeMethod;
    }

    private static void renderFallback(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xCC202020);
        graphics.renderOutline(x, y, width, height, 0xFF000000);

        String text = "\u00a77战术地图不可用";
        int textWidth = Minecraft.getInstance().font.width(text);
        graphics.drawString(
            Minecraft.getInstance().font,
            Component.literal(text),
            x + (width - textWidth) / 2,
            y + (height - 8) / 2,
            0xAAAAAA,
            false
        );
    }

    private static void logUnavailable(Throwable e) {
        if (unavailableLogged) {
            return;
        }
        unavailableLogged = true;
        Espetro.LOGGER.warn("HCRpoints 战术地图桥接不可用，部署界面将显示占位地图: {}", e.toString());
    }
}
