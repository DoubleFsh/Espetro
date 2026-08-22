package org.espetro.client.aui;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * One persistent AUI Overlay for every Espetro / EsPoints radial.
 * Pages are replaced in place; the document is created once.
 */
public final class AuiRadial {
    public static final String DOCUMENT_PATH = "overlays/radial.html";
    private static final ResourceLocation FALLBACK_ICON =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/commander_skills/unavailable.png");

    private static Document document;
    private static final List<AuiRadialSlot> slots = new ArrayList<>();
    private static String pageId = "";
    private static int hovered = -1;
    private static boolean open;
    private static boolean mouseWasDown;

    private AuiRadial() {
    }

    public static boolean isOpen() {
        return open && document != null && document.isActive();
    }

    public static String pageId() {
        return isOpen() ? pageId : "";
    }

    public static boolean isPage(String id) {
        return isOpen() && id != null && !id.isEmpty() && id.equals(pageId);
    }

    /** Current hover index, or {@code -1}. Vehicle hold-repeat uses this. */
    public static int hoveredIndex() {
        return isOpen() ? hovered : -1;
    }

    public static void show(List<AuiRadialSlot> next) {
        show(next, "");
    }

    public static void show(List<AuiRadialSlot> next, String nextPage) {
        // Radials are AuraTip. Never create the intercept overlay.
    }

    public static void replace(List<AuiRadialSlot> next) {
        replace(next, pageId);
    }

    public static void replace(List<AuiRadialSlot> next, String nextPage) {
        pageId = nextPage == null ? "" : nextPage;
        slots.clear();
        if (next != null) {
            slots.addAll(next);
        }
        hovered = -1;
        if (document == null || !document.isActive()) {
            return;
        }
        paintSlots();
        updateHover();
    }

    public static void hide() {
        open = false;
        hovered = -1;
        pageId = "";
        mouseWasDown = false;
        slots.clear();
        setVisible(false);
    }

    /**
     * Confirm the hovered slot. Does not hide after a successful action —
     * leaf controllers call {@link #hide()} themselves. A miss closes the ring.
     */
    public static boolean confirmHovered() {
        if (!isOpen() || hovered < 0 || hovered >= slots.size()) {
            hide();
            return false;
        }
        slots.get(hovered).action().run();
        return true;
    }

    /** Update hover only. Vehicle transfer slots must not confirm-on-release. */
    public static void tickHover(Minecraft minecraft) {
        if (!isOpen() || minecraft == null || minecraft.getWindow() == null) {
            return;
        }
        updateHover();
    }

    public static void tickInput(Minecraft minecraft) {
        if (!isOpen() || minecraft == null || minecraft.getWindow() == null) {
            mouseWasDown = false;
            return;
        }
        updateHover();
        boolean down = GLFW.glfwGetMouseButton(
            minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
            == GLFW.GLFW_PRESS;
        if (mouseWasDown && !down) {
            confirmHovered();
        }
        mouseWasDown = down;
    }

    private static boolean ensureDocument() {
        if (document != null && document.isActive() && !document.isDisposed()) {
            return true;
        }
        document = ApricityUI.createDocument(DOCUMENT_PATH);
        if (document == null) {
            return false;
        }
        document.setReloadPersistent(true);
        setVisible(false);
        return true;
    }

    private static void setVisible(boolean visible) {
        if (document == null) {
            return;
        }
        Element root = document.getElementById("radial");
        if (root == null) {
            return;
        }
        if (visible) {
            root.removeAttribute("hidden");
            root.setAttribute("class", "radial");
        } else {
            root.setAttribute("hidden", "true");
            root.setAttribute("class", "radial hidden");
        }
    }

    private static void paintSlots() {
        Element host = document.getElementById("slots");
        if (host == null) {
            return;
        }
        List<Element> existing = new ArrayList<>(host.children);
        for (Element child : existing) {
            child.remove();
        }
        Minecraft minecraft = Minecraft.getInstance();
        double centerX = minecraft.getWindow().getGuiScaledWidth() * 0.5D;
        double centerY = minecraft.getWindow().getGuiScaledHeight() * 0.5D;
        int count = slots.size();
        for (int index = 0; index < count; index++) {
            AuiRadialSlot slot = slots.get(index);
            double x = AuiRadialLayout.slotX(centerX, index, count);
            double y = AuiRadialLayout.slotY(centerY, index, count);
            String inner = itemSlot(slot)
                ? ""
                : "<texture src=\"" + escape(textureSrc(slot)) + "\"></texture>";
            Element node = document.createHTML(
                "<div class=\"" + slotClass(index, -1) + "\" data-id=\""
                    + escape(slot.id()) + "\" "
                    + "style=\"left:" + (int) Math.round(x) + "px;top:"
                    + (int) Math.round(y) + "px;\">"
                    + inner + "</div>");
            if (node != null) {
                host.appendChild(node);
            }
        }
        Element label = document.getElementById("label");
        if (label != null) {
            label.setTextContent("");
        }
    }

    private static void updateHover() {
        if (!open || document == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        double scale = minecraft.getWindow().getGuiScale();
        double mouseX = minecraft.mouseHandler.xpos() / scale;
        double mouseY = minecraft.mouseHandler.ypos() / scale;
        double centerX = minecraft.getWindow().getGuiScaledWidth() * 0.5D;
        double centerY = minecraft.getWindow().getGuiScaledHeight() * 0.5D;
        int next = AuiRadialLayout.hitIndex(mouseX, mouseY, centerX, centerY, slots.size());
        if (next == hovered) {
            return;
        }
        hovered = next;
        Element host = document.getElementById("slots");
        if (host != null) {
            for (int index = 0; index < host.children.size(); index++) {
                host.children.get(index).setAttribute("class", slotClass(index, hovered));
            }
        }
        Element label = document.getElementById("label");
        if (label != null) {
            if (hovered >= 0 && hovered < slots.size()) {
                label.setTextContent(slots.get(hovered).label().getString());
            } else {
                label.setTextContent("");
            }
        }
    }

    private static String slotClass(int index, int hoveredIndex) {
        StringBuilder cls = new StringBuilder("slot");
        if (index == hoveredIndex) {
            cls.append(" hovered");
        }
        if (index >= 0 && index < slots.size() && !slots.get(index).enabled()) {
            cls.append(" disabled");
        }
        return cls.toString();
    }

    public static void renderItems(net.minecraft.client.gui.GuiGraphics graphics) {
        if (!isOpen() || graphics == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        double centerX = minecraft.getWindow().getGuiScaledWidth() * 0.5D;
        double centerY = minecraft.getWindow().getGuiScaledHeight() * 0.5D;
        int count = slots.size();
        for (int index = 0; index < count; index++) {
            ItemStack item = slots.get(index).item();
            if (item == null || item.isEmpty()) {
                continue;
            }
            int x = (int) Math.round(AuiRadialLayout.slotX(centerX, index, count)) - 8;
            int y = (int) Math.round(AuiRadialLayout.slotY(centerY, index, count)) - 8;
            graphics.renderItem(item, x, y);
        }
    }

    private static boolean itemSlot(AuiRadialSlot slot) {
        return slot.item() != null && !slot.item().isEmpty() && slot.texture() == null;
    }

    private static String textureSrc(AuiRadialSlot slot) {
        if (slot.texture() != null) {
            return slot.texture().toString();
        }
        return FALLBACK_ICON.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
