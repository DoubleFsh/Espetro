package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/** Reset GUI GL state after custom HUD so later overlays (TACZ bar, chat) stay valid. */
final class HudRenderState {
    private HudRenderState() {
    }

    static void begin(GuiGraphics graphics) {
        if (graphics != null) {
            graphics.flush();
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        if (graphics != null) {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    static void restore(GuiGraphics graphics) {
        if (graphics != null) {
            graphics.flush();
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(true);
    }
}
