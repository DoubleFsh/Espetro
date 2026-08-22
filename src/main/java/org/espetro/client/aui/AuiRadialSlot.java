package org.espetro.client.aui;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** One radial option. Texture or item icon, never a sector background. */
public record AuiRadialSlot(
        String id,
        Component label,
        ResourceLocation texture,
        ItemStack item,
        String accent,
        boolean enabled,
        Runnable action) {

    public AuiRadialSlot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(action, "action");
        item = item == null ? ItemStack.EMPTY : item;
        accent = accent == null || accent.isBlank() ? "#FFD5B25C" : accent;
    }

    public static AuiRadialSlot texture(String id, Component label, ResourceLocation texture,
                                        String accent, Runnable action) {
        return texture(id, label, texture, accent, true, action);
    }

    public static AuiRadialSlot texture(String id, Component label, ResourceLocation texture,
                                        String accent, boolean enabled, Runnable action) {
        return new AuiRadialSlot(id, label, texture, ItemStack.EMPTY, accent, enabled, action);
    }

    public static AuiRadialSlot item(String id, Component label, ItemStack stack,
                                     String accent, boolean enabled, Runnable action) {
        return new AuiRadialSlot(id, label, null, stack, accent, enabled, action);
    }
}
