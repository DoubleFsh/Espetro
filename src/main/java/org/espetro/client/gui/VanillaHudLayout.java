package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import se.mickelus.mutil.gui.GuiElement;

public final class VanillaHudLayout {
    private static final ResourceLocation WIDGETS_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/widgets.png");
    private static final ResourceLocation GUI_ICONS_LOCATION = ResourceLocation.withDefaultNamespace("textures/gui/icons.png");

    private static final float HOTBAR_SCALE = 0.82F;
    private static final int HOTBAR_RIGHT_MARGIN = 0;
    private static final int SLOT_SIZE = 22;
    private static final int SLOT_STEP = 20;
    private static final int HOTBAR_SLOTS = 9;
    private static final int OFFHAND_GAP = 6;
    private static final int HOTBAR_REVEAL_TICKS = 40;
    private static final int HOTBAR_DURABILITY_PERCENTAGE = 10;
    private static final int HOTBAR_DURABILITY_TOTAL = 20;
    private static final double HOTBAR_ANIMATION_SPEED = 2.0D;
    private static final double HOTBAR_HIDE_DISTANCE = 26.0D;

    private static final int HEALTH_LEFT = 12;
    private static final int HEALTH_BOTTOM = 16;
    private static final int HEALTH_HEIGHT = 8;
    private static final int HEALTH_MIN_WIDTH = 96;
    private static final int HEALTH_MAX_WIDTH = 170;

    private static boolean hotbarStateReady;
    private static int previousSelectedSlot = -1;
    private static ItemStack previousMainHand = ItemStack.EMPTY;
    private static int hotbarVisibleTicks;
    private static double hotbarOffset = 1.0D;
    private static double hotbarOffsetDelta;
    private static double hotbarAlpha;
    private static double hotbarAlphaDelta;

    private VanillaHudLayout() {
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            resetHotbarState();
            return;
        }

        if (!hotbarStateReady) {
            hotbarStateReady = true;
            previousSelectedSlot = player.getInventory().selected;
            previousMainHand = player.getMainHandItem().copy();
        }

        ItemStack mainHand = player.getMainHandItem();
        if (player.getInventory().selected != previousSelectedSlot || !ItemStack.matches(mainHand, previousMainHand)) {
            revealHotbar();
            previousSelectedSlot = player.getInventory().selected;
            previousMainHand = mainHand.copy();
        }

        if (shouldRevealHotbarForLowDurability(mainHand)
                || shouldRevealHotbarForLowDurability(player.getOffhandItem())) {
            revealHotbar();
        }

        tickHotbarAnimation();
    }

    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
        ResourceLocation overlayId = event.getOverlay().id();
        Minecraft mc = Minecraft.getInstance();

        if (VanillaGuiOverlay.HOTBAR.id().equals(overlayId)) {
            if (renderRightHotbar(event.getGuiGraphics(), mc, event.getPartialTick(),
                    event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight())) {
                event.setCanceled(true);
            }
            return;
        }

        if (VanillaGuiOverlay.PLAYER_HEALTH.id().equals(overlayId)) {
            if (shouldReplaceSurvivalBars(mc)) {
                event.setCanceled(true);
                renderHealthLine(event.getGuiGraphics(), mc,
                        event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight());
            }
            return;
        }

        if (VanillaGuiOverlay.ARMOR_LEVEL.id().equals(overlayId)) {
            event.setCanceled(true);
            return;
        }

        if (VanillaGuiOverlay.FOOD_LEVEL.id().equals(overlayId)) {
            if (shouldReplaceSurvivalBars(mc)) {
                event.setCanceled(true);
            }
            return;
        }

        if (VanillaGuiOverlay.EXPERIENCE_BAR.id().equals(overlayId)) {
            event.setCanceled(true);
        }
    }

    private static void revealHotbar() {
        hotbarVisibleTicks = HOTBAR_REVEAL_TICKS;
    }

    private static void resetHotbarState() {
        hotbarStateReady = false;
        previousSelectedSlot = -1;
        previousMainHand = ItemStack.EMPTY;
        hotbarVisibleTicks = 0;
        hotbarOffset = 1.0D;
        hotbarOffsetDelta = 0.0D;
        hotbarAlpha = 0.0D;
        hotbarAlphaDelta = 0.0D;
    }

    private static boolean shouldRevealHotbarForLowDurability(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return false;
        }

        int maxDamage = stack.getMaxDamage();
        int damage = stack.getDamageValue();
        int remaining = maxDamage - damage;
        return damage >= (100 - HOTBAR_DURABILITY_PERCENTAGE) / 100.0D * maxDamage
                && remaining < HOTBAR_DURABILITY_TOTAL;
    }

    private static void tickHotbarAnimation() {
        if (hotbarVisibleTicks == 0) {
            if (!isHotbarFullyHidden()) {
                moveHotbarOut();
            }
            if (hotbarOffset == 1.0D) {
                hotbarOffsetDelta = 0.0D;
            }
            if (hotbarAlpha == 0.0D) {
                hotbarAlphaDelta = 0.0D;
            }
        } else if (!isHotbarFullyRevealed()) {
            moveHotbarIn();
        } else {
            if (hotbarOffset == 0.0D) {
                hotbarOffsetDelta = 0.0D;
            }
            if (hotbarAlpha == 1.0D) {
                hotbarAlphaDelta = 0.0D;
            }
        }

        if (hotbarVisibleTicks > 0) {
            hotbarVisibleTicks--;
        }
    }

    private static boolean isHotbarFullyHidden() {
        return hotbarOffset == 1.0D && hotbarAlpha == 0.0D;
    }

    private static boolean isHotbarFullyRevealed() {
        return hotbarOffset == 0.0D && hotbarAlpha == 1.0D;
    }

    private static void moveHotbarIn() {
        hotbarOffset = Math.max(0.0D, hotbarOffset + hotbarOffsetDelta);
        hotbarAlpha = Math.min(1.0D, hotbarAlpha + hotbarAlphaDelta);

        double offsetSpeed = Math.sqrt(0.01D + hotbarOffset) * 0.1D * HOTBAR_ANIMATION_SPEED;
        double alphaSpeed = 0.05D * HOTBAR_ANIMATION_SPEED;
        hotbarOffsetDelta = hotbarOffset - offsetSpeed <= 0.0D ? -hotbarOffset : -offsetSpeed;
        hotbarAlphaDelta = hotbarAlpha + alphaSpeed >= 1.0D ? 1.0D - hotbarAlpha : alphaSpeed;
    }

    private static void moveHotbarOut() {
        hotbarOffset = Math.min(1.0D, hotbarOffset + hotbarOffsetDelta);
        hotbarAlpha = Math.max(0.0D, hotbarAlpha + hotbarAlphaDelta);

        double offsetSpeed = Math.sqrt(0.01D + hotbarOffset) * 0.1D * HOTBAR_ANIMATION_SPEED;
        double alphaSpeed = 0.05D * HOTBAR_ANIMATION_SPEED;
        hotbarOffsetDelta = hotbarOffset + offsetSpeed >= 1.0D ? 1.0D - hotbarOffset : offsetSpeed;
        hotbarAlphaDelta = hotbarAlpha - alphaSpeed <= 0.0D ? -hotbarAlpha : -alphaSpeed;
    }

    private static float getHotbarAlpha(float partialTick) {
        return Mth.clamp((float) (hotbarAlpha + partialTick * hotbarAlphaDelta), 0.0F, 1.0F);
    }

    private static float getHotbarOffset(float partialTick) {
        return Mth.clamp((float) (hotbarOffset + partialTick * hotbarOffsetDelta), 0.0F, 1.0F);
    }

    private static boolean renderRightHotbar(GuiGraphics graphics, Minecraft mc, float partialTick,
                                             int screenWidth, int screenHeight) {
        boolean[] rendered = {false};
        GuiElement element = new GuiElement(0, 0, screenWidth, screenHeight) {
            @Override
            public void draw(GuiGraphics gui, int x, int y, int width, int height,
                             int mouseX, int mouseY, float tick) {
                rendered[0] = drawRightHotbar(gui, mc, partialTick, screenWidth, screenHeight);
                super.draw(gui, x, y, width, height, mouseX, mouseY, tick);
            }
        };
        element.draw(graphics, 0, 0, screenWidth, screenHeight, -1, -1, partialTick);
        return rendered[0];
    }

    private static boolean drawRightHotbar(GuiGraphics graphics, Minecraft mc, float partialTick,
                                           int screenWidth, int screenHeight) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.options.hideGui) {
            return false;
        }
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return false;
        }

        float alpha = getHotbarAlpha(partialTick);
        if (alpha <= 0.0F && getHotbarOffset(partialTick) >= 1.0F) {
            return true;
        }

        ItemStack offhand = player.getOffhandItem();
        int totalHeight = SLOT_SIZE + (HOTBAR_SLOTS - 1) * SLOT_STEP;
        if (!offhand.isEmpty()) {
            totalHeight += OFFHAND_GAP + SLOT_SIZE;
        }

        float localScreenWidth = screenWidth / HOTBAR_SCALE;
        float localScreenHeight = screenHeight / HOTBAR_SCALE;
        int baseX = Mth.floor(localScreenWidth - HOTBAR_RIGHT_MARGIN / HOTBAR_SCALE - SLOT_SIZE);
        int baseY = Mth.floor(Math.max(8.0F / HOTBAR_SCALE, (localScreenHeight - totalHeight) / 2.0F));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        graphics.pose().pushPose();
        graphics.pose().scale(HOTBAR_SCALE, HOTBAR_SCALE, 1.0F);
        graphics.pose().translate(getHotbarOffset(partialTick) * HOTBAR_HIDE_DISTANCE, 0.0F, 0.0F);
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);

        for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
            int slotY = baseY + slot * SLOT_STEP;
            graphics.blit(WIDGETS_LOCATION, baseX, slotY, 0, 0, SLOT_SIZE, SLOT_SIZE);
        }

        int selectedY = baseY + player.getInventory().selected * SLOT_STEP;
        graphics.blit(WIDGETS_LOCATION, baseX - 1, selectedY - 1, 0, 22, 24, 22);

        int seed = 1;
        for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
            int slotY = baseY + slot * SLOT_STEP;
            renderSlot(graphics, mc, player, player.getInventory().items.get(slot),
                    baseX + 3, slotY + 3, partialTick, seed++);
        }

        if (!offhand.isEmpty()) {
            int offhandY = baseY + SLOT_SIZE + (HOTBAR_SLOTS - 1) * SLOT_STEP + OFFHAND_GAP;
            graphics.blit(WIDGETS_LOCATION, baseX, offhandY, 0, 0, SLOT_SIZE, SLOT_SIZE);
            renderSlot(graphics, mc, player, offhand, baseX + 3, offhandY + 3, partialTick, seed);
        }

        renderAttackIndicator(graphics, mc, player, baseX - 22, selectedY + 2);

        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.pose().popPose();
        RenderSystem.disableBlend();
        return true;
    }

    private static void renderSlot(GuiGraphics graphics, Minecraft mc, Player player, ItemStack stack,
                                   int x, int y, float partialTick, int seed) {
        if (stack.isEmpty()) {
            return;
        }

        float popTime = (float) stack.getPopTime() - partialTick;
        if (popTime > 0.0F) {
            float scale = 1.0F + popTime / 5.0F;
            graphics.pose().pushPose();
            graphics.pose().translate(x + 8.0F, y + 12.0F, 0.0F);
            graphics.pose().scale(1.0F / scale, (scale + 1.0F) / 2.0F, 1.0F);
            graphics.pose().translate(-(x + 8.0F), -(y + 12.0F), 0.0F);
        }

        graphics.renderItem(player, stack, x, y, seed);

        if (popTime > 0.0F) {
            graphics.pose().popPose();
        }

        graphics.renderItemDecorations(mc.font, stack, x, y);
    }

    private static void renderAttackIndicator(GuiGraphics graphics, Minecraft mc, LocalPlayer player, int x, int y) {
        if (mc.options.attackIndicator().get() != AttackIndicatorStatus.HOTBAR) {
            return;
        }

        float attackStrength = player.getAttackStrengthScale(0.0F);
        if (attackStrength >= 1.0F) {
            return;
        }

        int filled = (int) (attackStrength * 19.0F);
        graphics.blit(GUI_ICONS_LOCATION, x, y, 0, 94, 18, 18);
        graphics.blit(GUI_ICONS_LOCATION, x, y + 18 - filled, 18, 112 - filled, 18, filled);
    }

    private static void renderHealthLine(GuiGraphics graphics, Minecraft mc,
                                         int screenWidth, int screenHeight) {
        GuiElement element = new GuiElement(0, 0, screenWidth, screenHeight) {
            @Override
            public void draw(GuiGraphics gui, int x, int y, int width, int height,
                             int mouseX, int mouseY, float partialTick) {
                drawHealthLine(gui, mc, screenWidth, screenHeight);
                super.draw(gui, x, y, width, height, mouseX, mouseY, partialTick);
            }
        };
        element.draw(graphics, 0, 0, screenWidth, screenHeight, -1, -1, 0.0F);
    }

    private static void drawHealthLine(GuiGraphics graphics, Minecraft mc,
                                       int screenWidth, int screenHeight) {
        if (!shouldReplaceSurvivalBars(mc) || !(mc.getCameraEntity() instanceof Player player)) {
            return;
        }

        float maxHealth = Math.max(1.0F, player.getMaxHealth());
        float health = Mth.clamp(player.getHealth(), 0.0F, maxHealth);
        if (health >= maxHealth) {
            return;
        }

        int barWidth = Mth.clamp(screenWidth / 5, HEALTH_MIN_WIDTH, HEALTH_MAX_WIDTH);
        int x = HEALTH_LEFT;
        int healthY = screenHeight - HEALTH_BOTTOM - HEALTH_HEIGHT;
        int healthFilled = Math.round(barWidth * (health / maxHealth));
        if (health > 0.0F) {
            healthFilled = Math.max(1, healthFilled);
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        if (healthFilled > 0) {
            graphics.fill(x, healthY, x + healthFilled, healthY + HEALTH_HEIGHT, 0xFFE33434);
            graphics.fill(x, healthY, x + healthFilled, healthY + 2, 0xFFFF6B6B);
        }

        RenderSystem.disableBlend();
    }

    private static boolean shouldReplaceSurvivalBars(Minecraft mc) {
        return !mc.options.hideGui && mc.gameMode != null && mc.gameMode.canHurtPlayer()
                && mc.getCameraEntity() instanceof Player;
    }
}
