package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

public final class VerticalHotbarOverlay {
    private static final ResourceLocation WIDGETS_LOCATION =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/widgets.png");
    private static final int HOTBAR_SLOTS = 9;
    private static final int SLOT_SIZE = 22;
    private static final int SLOT_STRIDE = 20;
    private static final int EDGE_MARGIN = 8;
    private static final int ITEM_OFFSET = 3;
    private static final int VISIBLE_TICKS_AFTER_SWITCH = 60;

    private static int lastSelectedSlot = -1;
    private static int visibleTicks;

    private VerticalHotbarOverlay() {
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.screen != null) {
            reset();
            return;
        }

        int selectedSlot = minecraft.player.getInventory().selected;
        if (lastSelectedSlot < 0) {
            lastSelectedSlot = selectedSlot;
        } else if (selectedSlot != lastSelectedSlot) {
            lastSelectedSlot = selectedSlot;
            visibleTicks = VISIBLE_TICKS_AFTER_SWITCH;
        } else if (visibleTicks > 0) {
            visibleTicks--;
        }
    }

    public static void onRenderHotbar(RenderGuiOverlayEvent.Pre event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (shouldUseVanillaHotbar(minecraft)) {
            return;
        }

        event.setCanceled(true);
        if (shouldRender(minecraft)) {
            render(event.getGuiGraphics(), minecraft, event.getWindow().getGuiScaledWidth(),
                    event.getWindow().getGuiScaledHeight());
        }
    }

    private static boolean shouldUseVanillaHotbar(Minecraft minecraft) {
        return minecraft == null || minecraft.player == null || minecraft.gameMode == null
                || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR;
    }

    private static boolean shouldRender(Minecraft minecraft) {
        return minecraft != null
                && minecraft.player != null
                && minecraft.screen == null
                && !minecraft.options.hideGui
                && visibleTicks > 0;
    }

    private static void render(GuiGraphics graphics, Minecraft minecraft, int screenWidth, int screenHeight) {
        LocalPlayer player = minecraft.player;
        int stride = getSlotStride(screenHeight);
        int hotbarHeight = SLOT_SIZE + (HOTBAR_SLOTS - 1) * stride;
        int x = Math.max(EDGE_MARGIN, screenWidth - SLOT_SIZE - EDGE_MARGIN);
        int y = Math.max(EDGE_MARGIN, (screenHeight - hotbarHeight) / 2);

        for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
            int slotY = y + slot * stride;
            renderSlotBackground(graphics, x, slotY);
            if (slot == player.getInventory().selected) {
                renderSelectedSlot(graphics, x, slotY);
            }
        }

        for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                int slotY = y + slot * stride;
                renderItem(graphics, minecraft, stack, x + ITEM_OFFSET, slotY + ITEM_OFFSET);
            }
        }

        renderOffhandSlot(graphics, minecraft, player, x, y + player.getInventory().selected * stride);
    }

    private static int getSlotStride(int screenHeight) {
        int availableHeight = screenHeight - EDGE_MARGIN * 2;
        int fullHeight = SLOT_SIZE + (HOTBAR_SLOTS - 1) * SLOT_STRIDE;
        if (availableHeight >= fullHeight) {
            return SLOT_STRIDE;
        }
        return Math.max(16, (availableHeight - SLOT_SIZE) / (HOTBAR_SLOTS - 1));
    }

    private static void renderSlotBackground(GuiGraphics graphics, int x, int y) {
        graphics.blit(WIDGETS_LOCATION, x, y, 0, 0, SLOT_SIZE, SLOT_SIZE);
    }

    private static void renderSelectedSlot(GuiGraphics graphics, int x, int y) {
        graphics.blit(WIDGETS_LOCATION, x - 1, y - 1, 0, 22, 24, 22);
    }

    private static void renderOffhandSlot(GuiGraphics graphics, Minecraft minecraft, LocalPlayer player,
            int hotbarX, int selectedSlotY) {
        ItemStack offhand = player.getOffhandItem();
        if (offhand.isEmpty()) {
            return;
        }

        int x = Math.max(EDGE_MARGIN, hotbarX - SLOT_SIZE - 6);
        renderSlotBackground(graphics, x, selectedSlotY);
        renderItem(graphics, minecraft, offhand, x + ITEM_OFFSET, selectedSlotY + ITEM_OFFSET);
    }

    private static void renderItem(GuiGraphics graphics, Minecraft minecraft, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(minecraft.font, stack, x, y);
    }

    private static void reset() {
        lastSelectedSlot = -1;
        visibleTicks = 0;
    }
}
