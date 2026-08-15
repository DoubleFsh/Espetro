package org.espetro.compat.taczmagazines;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Optional TaCZ Magazines bridge.  No optional-mod type is allowed in this API,
 * so the JVM can verify Espetro when taczmagazines is absent.
 */
public interface MagazineCompat {
    record Identity(String family, ResourceLocation ammoId, int capacity) {
        public Identity {
            family = family == null ? "" : family;
            if (ammoId == null) {
                ammoId = ResourceLocation.fromNamespaceAndPath("minecraft", "air");
            }
            capacity = Math.max(0, capacity);
        }
    }

    boolean available();

    Optional<Identity> identity(ItemStack stack);

    int ammoCount(ItemStack stack);

    ItemStack createFull(ItemStack template);
}
