package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Resolves configured role icon slugs to packaged GUI textures. */
final class RoleIconResources {
    static final int TEXTURE_SIZE = 128;

    private RoleIconResources() {
    }

    static ResourceLocation resolve(String icon) {
        if (icon == null || icon.isBlank() || icon.contains("..")
                || !icon.matches("[a-z0-9][a-z0-9_/-]*")) {
            return null;
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            "espetro", "textures/gui/roles/" + icon + ".png");
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent()
            ? location
            : null;
    }
}
