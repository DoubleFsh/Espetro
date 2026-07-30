package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.espetro.Espetro;

import java.util.List;
import java.util.Map;

/**
 * 右侧 AuraTip 短提示；未安装 AuraTip 时降级为聊天/ActionBar，保证不崩溃。
 * <p>
 * 打开部署等主 GUI 时，AuraTip 原 HUD 层会被挡住；由 {@link AuraTipAboveScreen}
 * 在 Screen 渲染后再次绘制，保证拒绝原因可见。
 * <p>
 * 布局：停靠 {@code RIGHT_CENTER}；从屏幕右缘外滑入（AuraTip 的
 * {@code slide_in_left} = 从最终位置右侧偏移进入，即自右缘弹出）。
 */
public final class EspetroTipNotifier {

    private static final long DEBOUNCE_MS = 1500L;
    /** AuraTip behavior.duration 单位为 tick（20 tick ≈ 1s）。 */
    private static final int TIP_DURATION_TICKS = 50; // ≈ 2.5s
    private static final int TIP_WIDTH = 248;
    private static final int TIP_HEIGHT = 88;
    private static final String POSITION_PRESET = "RIGHT_CENTER";
    /**
     * 从最终位置右侧（屏外）滑入。配合 RIGHT_CENTER 即「自屏幕右边弹出」。
     * 注意：不要用 slide_in_right（那是从左侧偏移滑向最终位置）。
     */
    private static final ResourceLocation SLIDE_FROM_RIGHT =
        ResourceLocation.fromNamespaceAndPath("auratip", "slide_in_left");

    private static String lastKey = "";
    private static long lastShownAt;

    private EspetroTipNotifier() {
    }

    public static void showDenial(String title, String body) {
        show(title, body, true, "denial:" + title + ":" + body);
    }

    public static void showInfo(String title, String body) {
        show(title, body, false, "info:" + title + ":" + body);
    }

    public static void showRaw(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String plain = message.replace('\u00a7', '&');
        // 去掉简单格式码前缀用于标题
        String stripped = message.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "");
        show("无法操作", stripped, true, "raw:" + plain);
    }

    private static void show(String title, String body, boolean error, String debounceKey) {
        long now = System.currentTimeMillis();
        if (debounceKey.equals(lastKey) && now - lastShownAt < DEBOUNCE_MS) {
            return;
        }
        lastKey = debounceKey;
        lastShownAt = now;

        if (tryAuraTip(title, body, error)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String line = "\u00a7" + (error ? "c" : "e") + title + "\u00a7r\n\u00a7f" + body;
            mc.player.displayClientMessage(Component.literal(line), false);
        }
    }

    private static boolean tryAuraTip(String title, String body, boolean error) {
        if (!ModList.get().isLoaded("auratip")) {
            return false;
        }
        try {
            Class<?> tipClientApi = Class.forName("cc.sighs.auratip.api.client.TipClientApi");
            Class<?> tipBuilderClz = Class.forName("cc.sighs.auratip.api.tip.TipBuilder");
            Class<?> bgTypeClz = Class.forName(
                "cc.sighs.auratip.data.TipData$VisualSettings$BackgroundType");

            Object builder = tipBuilderClz
                .getConstructor(ResourceLocation.class)
                .newInstance(ResourceLocation.fromNamespaceAndPath(
                    Espetro.MOD_ID, "denial_" + (System.nanoTime() & 0xFFFF)));

            // visual：固定 RIGHT_CENTER + 自右缘滑入
            Object visualConsumer = (java.util.function.Consumer<Object>) visual -> {
                try {
                    visual.getClass().getMethod("size", int.class, int.class)
                        .invoke(visual, TIP_WIDTH, TIP_HEIGHT);
                    visual.getClass().getMethod("positionPreset", String.class)
                        .invoke(visual, POSITION_PRESET);
                    try {
                        visual.getClass().getMethod("animationStyle", ResourceLocation.class)
                            .invoke(visual, SLIDE_FROM_RIGHT);
                    } catch (ReflectiveOperationException ignored) {
                    }
                    try {
                        visual.getClass().getMethod("animationSpeed", float.class)
                            .invoke(visual, 1.15f);
                    } catch (ReflectiveOperationException ignored) {
                    }
                    // 加大屏外偏移，确保从右缘外完整滑入
                    try {
                        visual.getClass().getMethod("animParam", String.class, Object.class)
                            .invoke(visual, "extra_padding", 48.0f);
                    } catch (ReflectiveOperationException ignored) {
                        try {
                            visual.getClass().getMethod("animParams", Map.class)
                                .invoke(visual, Map.of("extra_padding", 48.0f));
                        } catch (ReflectiveOperationException ignored2) {
                        }
                    }
                    // 不设置 animationFrom/To，使用 offset 动画，避免覆盖 RIGHT_CENTER 停靠点
                    visual.getClass().getMethod("themeColor", String.class)
                        .invoke(visual, error ? "#FFE05A5A" : "#FFE0B85A");
                    visual.getClass().getMethod("stripeWidth", int.class).invoke(visual, 3);
                    Object solid = Enum.valueOf(
                        (Class<Enum>) bgTypeClz.asSubclass(Enum.class), "SOLID");
                    visual.getClass()
                        .getMethod("background", bgTypeClz, List.class, int.class)
                        .invoke(visual, solid, List.of("#EE111416"), 0);
                    visual.getClass().getMethod("backgroundRounded", boolean.class)
                        .invoke(visual, false);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            };
            tipBuilderClz.getMethod("visual", java.util.function.Consumer.class)
                .invoke(builder, visualConsumer);

            Object behaviorConsumer = (java.util.function.Consumer<Object>) behavior -> {
                try {
                    behavior.getClass().getMethod("duration", int.class)
                        .invoke(behavior, TIP_DURATION_TICKS);
                    try {
                        behavior.getClass().getMethod("pauseOnHover", boolean.class)
                            .invoke(behavior, true);
                    } catch (ReflectiveOperationException ignored) {
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            };
            tipBuilderClz.getMethod("behavior", java.util.function.Consumer.class)
                .invoke(builder, behaviorConsumer);

            Object pageConsumer = (java.util.function.Consumer<Object>) page -> {
                try {
                    page.getClass().getMethod("title", Component.class)
                        .invoke(page, Component.literal(title == null ? "" : title));
                    page.getClass().getMethod("content", Component.class)
                        .invoke(page, Component.literal(body == null ? "" : body));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            };
            tipBuilderClz.getMethod("page", int.class, java.util.function.Consumer.class)
                .invoke(builder, 0, pageConsumer);

            Object tip = tipBuilderClz.getMethod("build").invoke(builder);
            try {
                tipClientApi.getMethod("close").invoke(null);
            } catch (ReflectiveOperationException ignored) {
            }
            tipClientApi.getMethod("enqueue", List.class, Map.class)
                .invoke(null, List.of(tip), Map.of());
            return true;
        } catch (Throwable t) {
            Espetro.LOGGER.debug("AuraTip 提示失败，降级聊天: {}", t.toString());
            return false;
        }
    }
}
