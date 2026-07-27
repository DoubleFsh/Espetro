package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import se.mickelus.mutil.gui.GuiElement;
import se.mickelus.mutil.gui.GuiRect;

import java.util.regex.Pattern;

final class EspetroMutilWidgets {

    // 阶段 UI 默认透明：玩家在高空旁观+失明时背后已近似全黑，不再铺不透明遮罩防闪。
    // BACKDROP 仍保留常量供主城等少数 opt-in 场景使用，但 drawScreenShade 默认不绘制。
    static final int BACKDROP = 0xFF121517;
    static final int PANEL = 0x00191C1E;
    static final int PANEL_SOFT = 0x00212527;
    static final int BORDER = 0xFF5B6260;
    static final int BORDER_ACTIVE = 0xFFE8B85C;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFD4D8E0;
    static final int DIM = 0xFFAEB4BF;
    static final int GOLD = 0xFFFFC766;
    static final int PURPLE = 0xFFD48CFF;
    static final int SQUAD_BLUE = 0xFF67A7FF;
    static final int ATTACK = 0xFFFF5E56;
    static final int DEFEND = 0xFF5F8DFF;
    static final int POSITIVE = 0xFF75D58A;
    static final int WARNING = 0xFFFFB44C;
    static final int NEGATIVE = 0xFFFF6666;
    static final int PHASE_HEADER_HEIGHT = 42;

    private static final Pattern FORMAT_CODE = Pattern.compile("(?i)\u00a7[0-9A-FK-OR]");

    private EspetroMutilWidgets() {
    }

    static String stripFormatting(String text) {
        return text == null ? "" : FORMAT_CODE.matcher(text).replaceAll("");
    }

    static GuiRect rect(int x, int y, int width, int height, int color) {
        return new GuiRect(x, y, width, height, color);
    }

    /** 默认不绘制全屏遮罩；个别 Screen 需要暗角时自行 opt-in fill。 */
    static void drawScreenShade(GuiGraphics graphics, int width, int height) {
        // no-op: MatchHold 失明黑底替代全屏 BACKDROP
    }

    static Panel panel(int x, int y, int width, int height) {
        return new Panel(x, y, width, height, PANEL, BORDER);
    }

    static Panel panel(int x, int y, int width, int height, int color, int borderColor) {
        return new Panel(x, y, width, height, color, borderColor);
    }

    static Text text(int x, int y, String value, int color) {
        return new Text(x, y, 0, value, color, false);
    }

    static Text text(int x, int y, int width, String value, int color) {
        return new Text(x, y, width, value, color, false);
    }

    static Text centeredText(int x, int y, int width, String value, int color) {
        return new Text(x, y, width, value, color, true);
    }

    static TextBlock textBlock(int x, int y, int width, String value, int color) {
        return new TextBlock(x, y, width, value, color);
    }

    static ActionButton button(int x, int y, int width, int height, String label, Runnable action) {
        return new ActionButton(x, y, width, height, label, action);
    }

    static ActionButton textButton(int x, int y, String label, Runnable action) {
        return button(x, y, textButtonWidth(label), 13, label, action);
    }

    static int textButtonWidth(String label) {
        return Minecraft.getInstance().font.width(stripFormatting(label)) + 12;
    }

    static int teamColor(String team) {
        return "ATTACK".equals(team) ? ATTACK : DEFEND;
    }

    static String teamName(String team) {
        return "ATTACK".equals(team) ? "进攻方" : "防守方";
    }

    static String teamPrefix(String team) {
        return "ATTACK".equals(team) ? "\u00a7c" : "\u00a79";
    }

    /** Adds a three-line phase/status header at the very top of a screen. */
    static int addPhaseHeader(GuiElement root, int screenWidth, String title,
                              String status, String detail, int accentColor) {
        addMutablePhaseHeader(root, screenWidth, title, status, detail, accentColor);
        return PHASE_HEADER_HEIGHT;
    }

    /**
     * Adds a phase header whose strings can be refreshed in place.  Periodic phase
     * packets should update this object instead of replacing the whole GUI tree.
     * 无灰底、无左侧色条、无底部分割线——仅三行居中文字。
     */
    static PhaseHeader addMutablePhaseHeader(GuiElement root, int screenWidth, String title,
                                             String status, String detail, int accentColor) {
        Text titleText = centeredText(6, 4, Math.max(1, screenWidth - 12),
            title == null ? "" : title, TEXT);
        Text statusText = centeredText(6, 16, Math.max(1, screenWidth - 12),
            status == null ? "" : status, MUTED);
        Text detailText = centeredText(6, 28, Math.max(1, screenWidth - 12),
            detail == null ? "" : detail, DIM);
        root.addChild(titleText);
        root.addChild(statusText);
        root.addChild(detailText);
        return new PhaseHeader(titleText, statusText, detailText);
    }

    static final class PhaseHeader {
        private final Text title;
        private final Text status;
        private final Text detail;
        private String lastTitle;
        private String lastStatus;
        private String lastDetail;

        private PhaseHeader(Text title, Text status, Text detail) {
            this.title = title;
            this.status = status;
            this.detail = detail;
        }

        void setTitle(String value) {
            String next = value == null ? "" : value;
            if (java.util.Objects.equals(lastTitle, next)) {
                return;
            }
            lastTitle = next;
            title.setText(next);
        }

        void setStatus(String value) {
            String next = value == null ? "" : value;
            if (java.util.Objects.equals(lastStatus, next)) {
                return;
            }
            lastStatus = next;
            status.setText(next);
        }

        void setDetail(String value) {
            String next = value == null ? "" : value;
            if (java.util.Objects.equals(lastDetail, next)) {
                return;
            }
            lastDetail = next;
            detail.setText(next);
        }
    }

    static String trimToWidth(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        String plain = stripFormatting(value);
        if (Minecraft.getInstance().font.width(plain) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int suffixWidth = Minecraft.getInstance().font.width(suffix);
        return Minecraft.getInstance().font.plainSubstrByWidth(
            plain, Math.max(0, maxWidth - suffixWidth)) + suffix;
    }

    static void drawScaledString(GuiGraphics graphics, String value, int x, int y,
                                 int color, float scale) {
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(Minecraft.getInstance().font, Component.literal(value),
            Math.round(x / scale), Math.round(y / scale), color, false);
        graphics.pose().popPose();
    }

    static class Panel extends GuiElement {
        private int color;
        private int borderColor;

        Panel(int x, int y, int width, int height, int color, int borderColor) {
            super(x, y, width, height);
            this.color = color;
            this.borderColor = borderColor;
        }

        Panel setColor(int color) {
            this.color = color;
            return this;
        }

        Panel setBorderColor(int borderColor) {
            this.borderColor = borderColor;
            return this;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }

            int bx = x + getX();
            int by = y + getY();
            if (hasAlpha(color)) {
                graphics.fill(bx, by, bx + getWidth(), by + getHeight(), color);
            }
            if (hasAlpha(borderColor)) {
                graphics.renderOutline(bx, by, getWidth(), getHeight(), borderColor);
            }
            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }
    }

    static class Text extends GuiElement {
        private String value;
        private int color;
        private final boolean centered;
        private final boolean fixedWidth;
        private float textScale = 1.0f;

        Text(int x, int y, int width, String value, int color, boolean centered) {
            super(x, y, width > 0 ? width : Minecraft.getInstance().font.width(stripFormatting(value)),
                Minecraft.getInstance().font.lineHeight);
            this.value = value == null ? "" : value;
            this.color = color;
            this.centered = centered;
            this.fixedWidth = width > 0;
        }

        void setText(String value) {
            String next = value == null ? "" : value;
            if (next.equals(this.value)) {
                return; // 等值短路：避免无谓的宽度重算与视觉抖动
            }
            this.value = next;
            if (!fixedWidth) {
                setWidth(Math.max(1, Math.round(
                    Minecraft.getInstance().font.width(stripFormatting(this.value)) * textScale)));
            }
        }

        void setColor(int color) {
            this.color = color;
        }

        Text setTextScale(float scale) {
            this.textScale = Math.max(0.5f, Math.min(1.0f, scale));
            setHeight(Math.max(1, Math.round(Minecraft.getInstance().font.lineHeight * textScale)));
            if (!fixedWidth) {
                setWidth(Math.max(1, Math.round(
                    Minecraft.getInstance().font.width(stripFormatting(value)) * textScale)));
            }
            return this;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }

            String drawnValue = fixedWidth
                ? trimToWidth(value, Math.max(8, (int) (getWidth() / textScale)))
                : value;
            int drawnWidth = Math.round(
                Minecraft.getInstance().font.width(stripFormatting(drawnValue)) * textScale);
            int tx = x + getX();
            if (centered) {
                tx += Math.max(0, (getWidth() - drawnWidth) / 2);
            }
            drawScaledString(graphics, drawnValue, tx, y + getY(), color, textScale);
            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }
    }

    static class TextBlock extends GuiElement {
        private String value;
        private int color;

        TextBlock(int x, int y, int width, String value, int color) {
            super(x, y, width, Minecraft.getInstance().font.lineHeight);
            this.value = value == null ? "" : value;
            this.color = color;
            updateHeight();
        }

        void setText(String value) {
            String next = value == null ? "" : value;
            if (next.equals(this.value)) {
                return;
            }
            this.value = next;
            updateHeight();
        }

        void setColor(int color) {
            this.color = color;
        }

        private void updateHeight() {
            setHeight(Minecraft.getInstance().font.wordWrapHeight(stripFormatting(value), getWidth()));
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }

            graphics.drawWordWrap(Minecraft.getInstance().font, Component.literal(value),
                x + getX(), y + getY(), getWidth(), color);
            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }
    }

    static class ActionButton extends GuiElement {
        private final Runnable action;
        private String label;
        private boolean enabled = true;
        private boolean selected = false;
        // 默认透明底 + 边框区分 hover/selected，避免大面积半透明 fill 当「背景墙」。
        private int normalColor = 0x00000000;
        private int hoverColor = 0x4027353A;
        private int selectedColor = 0x403A3420;
        private int disabledColor = 0x00000000;
        private int borderColor = 0x805B6260;
        private int textColor = TEXT;
        private float textScale = 1.0f;

        ActionButton(int x, int y, int width, int height, String label, Runnable action) {
            super(x, y, width, height);
            this.label = label == null ? "" : label;
            this.action = action;
        }

        ActionButton setLabel(String label) {
            String next = label == null ? "" : label;
            if (!next.equals(this.label)) {
                this.label = next;
            }
            return this;
        }

        ActionButton setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        ActionButton setSelected(boolean selected) {
            this.selected = selected;
            return this;
        }

        ActionButton setColors(int normalColor, int hoverColor, int selectedColor) {
            this.normalColor = normalColor;
            this.hoverColor = hoverColor;
            this.selectedColor = selectedColor;
            return this;
        }

        ActionButton setDisabledColor(int disabledColor) {
            this.disabledColor = disabledColor;
            return this;
        }

        ActionButton setBorderColor(int borderColor) {
            this.borderColor = borderColor;
            return this;
        }

        ActionButton setTextColor(int textColor) {
            this.textColor = textColor;
            return this;
        }

        ActionButton setTextScale(float scale) {
            this.textScale = Math.max(0.5f, Math.min(1.0f, scale));
            return this;
        }

        @Override
        public boolean onMouseClick(int mouseX, int mouseY, int button) {
            if (button != 0 || !enabled || !isVisible() || !hasFocus()) {
                return false;
            }

            if (action != null) {
                action.run();
            }
            return true;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }

            int bx = x + getX();
            int by = y + getY();
            int fillColor = !enabled ? disabledColor : selected ? selectedColor : hasFocus() ? hoverColor : normalColor;
            int outline = selected ? 0x90E8B85C : hasFocus() && enabled ? 0x60C2C8D5 : borderColor;

            if (hasAlpha(fillColor)) {
                graphics.fill(bx, by, bx + getWidth(), by + getHeight(), fillColor);
            }
            if (hasAlpha(outline)) {
                graphics.renderOutline(bx, by, getWidth(), getHeight(), outline);
            }

            int logicalTextWidth = Math.max(8, (int) ((getWidth() - 6) / textScale));
            String drawnLabel = trimToWidth(label, logicalTextWidth);
            int labelWidth = Math.round(
                Minecraft.getInstance().font.width(stripFormatting(drawnLabel)) * textScale);
            int textHeight = Math.max(1,
                Math.round(Minecraft.getInstance().font.lineHeight * textScale));
            int color = enabled ? textColor : DIM;
            drawScaledString(graphics, drawnLabel,
                bx + Math.max(3, (getWidth() - labelWidth) / 2),
                by + Math.max(1, (getHeight() - textHeight) / 2),
                color, textScale);

            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }

        private static String trimToWidth(String value, int maxWidth) {
            if (Minecraft.getInstance().font.width(stripFormatting(value)) <= maxWidth) {
                return value;
            }

            String plain = stripFormatting(value);
            String suffix = "...";
            int suffixWidth = Minecraft.getInstance().font.width(suffix);
            return Minecraft.getInstance().font.plainSubstrByWidth(plain, Math.max(0, maxWidth - suffixWidth)) + suffix;
        }
    }

    private static boolean hasAlpha(int color) {
        return (color & 0xFF000000) != 0;
    }

    /**
     * 渲染一张纹理图片
     */
    static class TextureImage extends GuiElement {
        private final ResourceLocation texture;
        private float alpha = 1f;

        TextureImage(int x, int y, int width, int height, ResourceLocation texture) {
            super(x, y, width, height);
            this.texture = texture;
        }

        TextureImage setAlpha(float alpha) {
            this.alpha = alpha;
            return this;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                         int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) return;

            graphics.setColor(1f, 1f, 1f, alpha);
            graphics.blit(texture, x + getX(), y + getY(), 0, 0, getWidth(), getHeight(),
                getWidth(), getHeight());
            graphics.setColor(1f, 1f, 1f, 1f);
            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }
    }

    /**
     * 可点击的图片按钮（带 hover 高亮边框，等比例缩放）
     */
    static class ImageButton extends GuiElement {
        private final ResourceLocation texture;
        private final Runnable action;
        private final int texW, texH;
        private int borderColor = 0x00000000;
        private int hoverBorderColor = 0x80FFFFFF;
        /** 选中态边框；非 0 时优先于普通 border，且 hover 时仍保留。 */
        private int selectedBorderColor = 0x00000000;

        ImageButton(int x, int y, int maxWidth, int maxHeight, ResourceLocation texture, Runnable action) {
            super(x, y, maxWidth, maxHeight);
            this.texture = texture;
            this.action = action;
            // 获取纹理实际尺寸，计算等比例缩放后的显示区域
            var tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
            // 无法在构造时获取纹理尺寸（可能尚未加载），用 blit 方式自动等比
            this.texW = maxWidth;
            this.texH = maxHeight;
        }

        ImageButton(int x, int y, int maxWidth, int maxHeight, int texW, int texH,
                    ResourceLocation texture, Runnable action) {
            super(x, y, maxWidth, maxHeight);
            this.texture = texture;
            this.action = action;
            this.texW = texW;
            this.texH = texH;
        }

        ImageButton setBorderColor(int color) { this.borderColor = color; return this; }
        ImageButton setHoverBorderColor(int color) { this.hoverBorderColor = color; return this; }
        ImageButton setSelectedBorderColor(int color) { this.selectedBorderColor = color; return this; }

        @Override
        public boolean onMouseClick(int mouseX, int mouseY, int button) {
            if (button != 0 || !isVisible() || !hasFocus()) return false;
            if (action != null) action.run();
            return true;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                         int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) return;

            int bx = x + getX(), by = y + getY();
            int maxW = getWidth(), maxH = getHeight();

            // 等比例缩放：按最大边适配
            float scale = Math.min((float) maxW / texW, (float) maxH / texH);
            int drawW = (int) (texW * scale);
            int drawH = (int) (texH * scale);
            // 居中
            int drawX = bx + (maxW - drawW) / 2;
            int drawY = by + (maxH - drawH) / 2;

            // 选中边框常驻；hover 时用更亮的 hover 色但仍框住整张图。
            int outlineColor;
            if (hasAlpha(selectedBorderColor)) {
                outlineColor = hasFocus() && hasAlpha(hoverBorderColor)
                    ? hoverBorderColor : selectedBorderColor;
            } else {
                outlineColor = hasFocus() ? hoverBorderColor : borderColor;
            }
            if (hasAlpha(outlineColor)) {
                // 外扩 2px，选中态更明显
                int pad = hasAlpha(selectedBorderColor) ? 2 : 1;
                graphics.renderOutline(drawX - pad, drawY - pad, drawW + pad * 2, drawH + pad * 2, outlineColor);
            }

            // 目标区域与源纹理尺寸必须分别传入。将 drawW/drawH 当成
            // 纹理尺寸会让透明边缘越界重复采样，尤其会污染非方形 PNG。
            graphics.blit(texture, drawX, drawY, drawW, drawH,
                0f, 0f, texW, texH, texW, texH);

            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }
    }
}
