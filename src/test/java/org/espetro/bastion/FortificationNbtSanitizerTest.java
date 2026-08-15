package org.espetro.bastion;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FortificationNbtSanitizerTest {

    @Test
    void entityKeepsVisualFieldsAndStripsGameplayState() {
        CompoundTag raw = new CompoundTag();
        raw.putString("id", "minecraft:armor_stand");
        raw.putBoolean("Small", true);
        raw.putFloat("Health", 99F);
        raw.put("Attributes", new ListTag());
        raw.put("Brain", new CompoundTag());
        raw.put("ForgeCaps", new CompoundTag());
        raw.putUUID("UUID", java.util.UUID.randomUUID());
        CompoundTag clean = FortificationNbtSanitizer.sanitizeEntity(raw, 0, 4, 4096);
        assertTrue(clean.getBoolean("Small"));
        assertEquals("minecraft:armor_stand", clean.getString("id"));
        for (String forbidden : new String[]{"Health", "Attributes", "Brain", "ForgeCaps", "UUID"}) {
            assertFalse(clean.contains(forbidden), forbidden);
        }
    }

    @Test
    void rejectsPlayersUnknownBlockEntitiesAndDeepPassengers() {
        CompoundTag player = new CompoundTag();
        player.putString("id", "minecraft:player");
        assertThrows(IllegalArgumentException.class,
            () -> FortificationNbtSanitizer.sanitizeEntity(player, 0, 4, 4096));

        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.put("Items", new ListTag());
        assertThrows(IllegalArgumentException.class,
            () -> FortificationNbtSanitizer.sanitizeBlockEntity(chest, 4096));

        CompoundTag passenger = new CompoundTag();
        passenger.putString("id", "minecraft:armor_stand");
        ListTag passengers = new ListTag();
        passengers.add(passenger);
        CompoundTag root = new CompoundTag();
        root.putString("id", "dragonrise_reforge:ammo_supply_station");
        root.put("Passengers", passengers);
        assertThrows(IllegalArgumentException.class,
            () -> FortificationNbtSanitizer.sanitizeRootEntity(root,
                new ResourceLocation("dragonrise_reforge:ammo_supply_station"), 0, 4096));
    }
}
