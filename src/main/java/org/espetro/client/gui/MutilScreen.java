package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import se.mickelus.mutil.gui.GuiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

abstract class MutilScreen extends Screen {

    protected GuiElement root;
    private boolean rootRebuildPending;
    private boolean rebuildingRoot;
    /** 教程预览：打开阶段 GUI 但禁用业务交互与发包。 */
    protected boolean tutorialPreviewMode;

    // ==================== 动态更新框架 ====================
    // 结构签名：数据包到达时先比签名；相同走原地更新，不同才 rebuild。
    private Object structureSignature;
    // 每秒节流闸：本地倒计时/冷却标签统一走它，杜绝每 tick 改字符串。
    private long lastThrottleEpochSec = -1;
    // 动态绑定用独立计数器：不与子类可见的 onceEverySecond() 共享，避免互相消耗闸值。
    private long lastBindingEpochSec = -1;
    // 动态标签注册表：buildMutilRoot 里 bindDynamic 注册，tick 每秒统一刷新。
    private final List<DynamicBinding> dynamicBindings = new ArrayList<>();

    private record DynamicBinding(EspetroMutilWidgets.Text widget, Supplier<String> supplier) {
    }

    /**
     * 比较结构签名：不同则记录并请求延迟 rebuild，返回 true；
     * 相同返回 false，调用方应转为原地更新已保留的 widget。
     */
    protected final boolean updateStructure(Object newSignature) {
        if (Objects.equals(structureSignature, newSignature)) {
            return false;
        }
        structureSignature = newSignature;
        rebuildMutilRoot();
        return true;
    }

    /** 当前记录的结构签名（rebuild 后仍保留，供子类比对）。 */
    protected final Object getStructureSignature() {
        return structureSignature;
    }

    /** 每客户端 tick 至多每秒返回一次 true；用于节流倒计时文案更新。 */
    protected final boolean onceEverySecond() {
        long epochSec = System.currentTimeMillis() / 1000L;
        if (epochSec == lastThrottleEpochSec) {
            return false;
        }
        lastThrottleEpochSec = epochSec;
        return true;
    }

    /**
     * 注册动态文本：每秒自动 setText(supplier.get())。
     * 必须在 buildMutilRoot 内调用（rebuild 会清空注册表后重新构建）。
     */
    protected final EspetroMutilWidgets.Text bindDynamic(
            EspetroMutilWidgets.Text widget, Supplier<String> supplier) {
        dynamicBindings.add(new DynamicBinding(widget, supplier));
        return widget;
    }

    private void refreshDynamicBindings() {
        for (DynamicBinding binding : dynamicBindings) {
            String next = binding.supplier.get();
            if (next != null) {
                binding.widget.setText(next);
            }
        }
    }

    protected MutilScreen(Component title) {
        super(title);
    }

    public final void setTutorialPreviewMode(boolean tutorialPreviewMode) {
        this.tutorialPreviewMode = tutorialPreviewMode;
    }

    public final boolean isTutorialPreviewMode() {
        return tutorialPreviewMode;
    }

    @Override
    protected void init() {
        super.init();
        rebuildMutilRootNow();
    }

    /**
     * Coalesces structural GUI updates until the next client tick. Network packets
     * can arrive while MUtil is traversing the element tree; replacing that tree
     * immediately causes visible flicker and occasionally drops focus state.
     */
    protected final void rebuildMutilRoot() {
        rootRebuildPending = true;
    }

    private void rebuildMutilRootNow() {
        if (rebuildingRoot) {
            return;
        }
        rebuildingRoot = true;
        dynamicBindings.clear();
        GuiElement newRoot = new GuiElement(0, 0, this.width, this.height);
        buildMutilRoot(newRoot);
        this.root = newRoot;
        rebuildingRoot = false;
    }

    @Override
    public void tick() {
        super.tick();
        if (rootRebuildPending) {
            rootRebuildPending = false;
            rebuildMutilRootNow();
        }
        if (root != null) {
            root.updateAnimations();
        }
        if (!dynamicBindings.isEmpty()) {
            long epochSec = System.currentTimeMillis() / 1000L;
            if (epochSec != lastBindingEpochSec) {
                lastBindingEpochSec = epochSec;
                refreshDynamicBindings();
            }
        }
    }

    protected abstract void buildMutilRoot(GuiElement root);

    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    protected void renderAfterMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // GuiGraphics batches vertices. Flush before touching RenderSystem so the
        // blend state cannot leak backward into a batch produced by another HUD.
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.pose().pushPose();
        try {
            renderBeforeMutil(graphics, mouseX, mouseY, partialTick);
            if (root != null) {
                root.updateFocusState(0, 0, mouseX, mouseY);
                root.draw(graphics, 0, 0, this.width, this.height, mouseX, mouseY, partialTick);

                var tooltip = root.getTooltipLines();
                if (tooltip != null && !tooltip.isEmpty()) {
                    graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
                }
            }
            renderAfterMutil(graphics, mouseX, mouseY, partialTick);
            // GuiGraphics batches quads. Flush before another screen or HUD
            // renderer can mutate global RenderSystem state for this frame.
            graphics.flush();
        } finally {
            graphics.flush();
            graphics.pose().popPose();
            graphics.setColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tutorialPreviewMode) {
            // 业务按钮 no-op；左下「退出教程」由 TutorialHudOverlay 处理。
            if (TutorialHudOverlay.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            return true;
        }
        if (root != null) {
            root.updateFocusState(0, 0, (int) mouseX, (int) mouseY);
            if (root.onMouseClick((int) mouseX, (int) mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (tutorialPreviewMode) {
            return true;
        }
        if (root != null) {
            root.onMouseRelease((int) mouseX, (int) mouseY, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (tutorialPreviewMode) {
            return true;
        }
        if (root != null && root.onMouseScroll(mouseX, mouseY, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (TutorialClientController.handleKeyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (tutorialPreviewMode) {
            // Esc 默认不退出教程；Enter 由 TutorialClientController 处理。
            return true;
        }
        if (root != null && root.onKeyPress(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (tutorialPreviewMode) {
            return true;
        }
        if (root != null && root.onKeyRelease(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (tutorialPreviewMode) {
            return true;
        }
        if (root != null && root.onCharType(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }
}
