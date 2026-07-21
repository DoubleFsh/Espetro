package org.espetro.client.gui;

import cc.sighs.auratip.api.client.TipClientApi;
import cc.sighs.auratip.api.tip.TipBuilder;
import cc.sighs.auratip.client.render.TipOverlay;
import cc.sighs.auratip.data.TipData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.NetworkManager;
import org.espetro.network.TutorialActionPacket;

import java.util.List;
import java.util.Map;

/**
 * AuraTip-native tutorial controller with Espetro's server-authoritative progress.
 */
public final class TutorialOverlay {

    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 150;
    private static final int MAX_BODY_LINES = 11;

    private static boolean visible;
    private static boolean actionPending;
    private static boolean auraWasActive;
    private static String stepId = "";

    private TutorialOverlay() {
    }

    public static void show(String newStepId, int newIndex, int newTotal, boolean newAllowSkip) {
        Minecraft minecraft = Minecraft.getInstance();
        stepId = newStepId == null ? "" : newStepId;
        visible = true;
        actionPending = false;
        int panelWidth = Math.min(PANEL_WIDTH,
            Math.max(220, minecraft.getWindow().getGuiScaledWidth() - 16));

        TipOverlay.INSTANCE.closeImmediately();
        Component title = Component.translatable("tutorial.step." + stepId + ".title")
            .append(Component.literal("  " + newIndex + " / " + newTotal));
        Component body = Component.translatable("tutorial.step." + stepId + ".body");
        String closeHint = newAllowSkip
            ? "\n\n关闭提示后继续下一条；/espetro tutorial skip 可跳过全部教程"
            : "\n\n关闭提示后继续下一条教程";
        Component wrappedBody = minecraft.font == null
            ? Component.literal(body.getString() + closeHint)
            : Component.literal(wrap(minecraft.font, body.getString() + closeHint, panelWidth - 24));

        TipData tip = new TipBuilder(ResourceLocation.fromNamespaceAndPath(
                "espetro", "tutorial_" + stepId))
            .visual(visual -> visual
                .size(panelWidth, PANEL_HEIGHT)
                .positionPreset("BOTTOM_CENTER")
                .animationStyle(ResourceLocation.fromNamespaceAndPath(
                    "auratip", "slide_in_bottom"))
                .animationSpeed(1.35f)
                .themeColor("#FFE0B85A")
                .stripeWidth(3)
                .stripeLengthFactor(1.0f)
                .background(TipData.VisualSettings.BackgroundType.SOLID,
                    List.of("#EE111416"), 0)
                .backgroundRounded(false))
            .behavior(behavior -> behavior
                .duration(-1)
                .pauseOnHover(true)
                .allowPaging(false))
            .page(0, page -> page
                .title(title)
                .titleDivider(1, 4, 5, 1.0f, "#99E0B85A")
                .content(wrappedBody))
            .build();

        TipClientApi.enqueue(List.of(tip), Map.of());
        auraWasActive = TipOverlay.INSTANCE.isActive();
    }

    public static void clear() {
        visible = false;
        actionPending = true;
        stepId = "";
        auraWasActive = false;
        TipOverlay.INSTANCE.closeImmediately();
    }

    public static void tick() {
        if (!visible) {
            return;
        }
        boolean active = TipOverlay.INSTANCE.isActive();
        if (active) {
            auraWasActive = true;
            return;
        }
        if (auraWasActive && !actionPending) {
            actionPending = true;
            visible = false;
            NetworkManager.NET.sendToServer(TutorialActionPacket.dismiss(stepId));
        }
    }

    public static boolean isVisible() {
        return visible;
    }

    public static String getStepId() {
        return stepId;
    }

    private static String wrap(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        StringBuilder line = new StringBuilder();
        int lines = 1;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            String character = new String(Character.toChars(codePoint));
            if (codePoint == '\n' || font.width(line.toString() + character) > maxWidth) {
                result.append(line).append('\n');
                line.setLength(0);
                lines++;
                if (lines > MAX_BODY_LINES) {
                    result.append("...");
                    return result.toString();
                }
                if (codePoint == '\n') {
                    continue;
                }
            }
            line.append(character);
        }
        return result.append(line).toString();
    }
}
