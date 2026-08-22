package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import org.espetro.network.FortificationProgressPacket;
import org.espetro.client.aui.GuiElement;

/** Persistent MUtil construction bar; state changes only when a server packet arrives. */
public final class FortificationProgressHud {
    private static final long VISIBLE_MS = 3_000L;
    private static volatile State state = State.hidden();

    private FortificationProgressHud() {
    }

    public static void update(FortificationProgressPacket packet) {
        state = new State(packet.displayName(), Math.max(0, packet.progress()),
            Math.max(1, packet.required()), packet.building(), Util.getMillis() + VISIBLE_MS);
    }

    public static GuiElement createElement() {
        return new ProgressElement();
    }

    private record State(String name, int progress, int required, boolean building, long hideAt) {
        static State hidden() { return new State("", 0, 1, true, 0L); }
    }

    private static final class ProgressElement extends GuiElement {
        private ProgressElement() {
            super(8, 34, 144, 18);
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                         int mouseX, int mouseY, float partialTick) {
            State snapshot = state;
            if (snapshot.hideAt <= Util.getMillis()) return;
            Minecraft mc = Minecraft.getInstance();
            int left = getX();
            int top = getY();
            graphics.fill(left, top, left + 144, top + 18, 0xD0111111);
            graphics.fill(left, top, left + 144, top + 1, 0xB05E646C);
            graphics.fill(left, top + 17, left + 144, top + 18, 0xB05E646C);
            int fill = Mth.clamp((int) Math.round(140.0D * snapshot.progress / snapshot.required), 0, 140);
            int color = snapshot.building ? 0xFFE3B52B : 0xFFE34B3F;
            graphics.fill(left + 2, top + 13, left + 142, top + 16, 0xFF333333);
            if (fill > 0) graphics.fill(left + 2, top + 13, left + 2 + fill, top + 16, color);
            int percent = Mth.clamp((int) Math.round(100.0D * snapshot.progress / snapshot.required), 0, 100);
            String text = snapshot.name + " " + percent + "%  " + snapshot.progress + "/" + snapshot.required;
            if (mc.font.width(text) > 138) text = snapshot.name + " " + percent + "%";
            graphics.drawString(mc.font, text, left + 3, top + 3, 0xFFFFFF, false);
        }
    }
}
