package org.espetro.logistics.resupply;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResupplyItemIdentityTest {

    @Test
    void missingNbtMatchesEveryTagVariantOfTheSameItem() {
        ResupplyItemIdentity.Configured identity =
            ResupplyItemIdentity.parse("superbwarfare:medical_kit", null);
        assertEquals("superbwarfare:medical_kit", identity.registryId());
        assertFalse(identity.exactTag());
        assertTrue(ResupplyItemIdentity.matchesNormal(identity.exactTag(), true, false));
        assertFalse(ResupplyItemIdentity.matchesNormal(identity.exactTag(), false, false));
    }

    @Test
    void explicitNbtAndInlineSnbtBothRequireExactTagMatch() {
        ResupplyItemIdentity.Configured explicit = ResupplyItemIdentity.parse(
            "taczmagazines:magazine",
            "{AmmoCount:30,AmmoId:\"tacz:58x42\",MagazineFamily:\"58x42_30\",MaxCapacity:30}");
        assertTrue(explicit.exactTag());
        assertEquals("taczmagazines:magazine", explicit.registryId());
        assertTrue(ResupplyItemIdentity.matchesNormal(explicit.exactTag(), true, true));
        assertFalse(ResupplyItemIdentity.matchesNormal(explicit.exactTag(), true, false));

        ResupplyItemIdentity.Configured inline = ResupplyItemIdentity.parse(
            "minecraft:bread{display:{Name:'x'}}", null);
        assertTrue(inline.exactTag());
        assertEquals("minecraft:bread", inline.registryId());
        assertEquals("{display:{Name:'x'}}", inline.nbt());
    }

    @Test
    void magazineKeyIgnoresRemainingAmmoAndUsesFamilyAmmoCapacity() {
        var key = ResupplyItemIdentity.magazineKey(
            "{AmmoCount:7,AmmoId:\"cib:58x21\",MagazineFamily:\"58x21_50\",MaxCapacity:50}");
        assertTrue(key.isPresent());
        assertEquals(new ResupplyItemIdentity.MagazineKey("58x21_50", "cib:58x21", 50), key.get());
    }

    @Test
    void spareMagazineCommandsIgnoreGunEmbeddedMagazines() {
        var spare = ResupplyItemIdentity.spareMagazineCommand(
            "taczmagazines:magazine{AmmoCount:30,AmmoId:\"tacz:58x42\",MagazineFamily:\"58x42_30\",MaxCapacity:30} 7");
        assertTrue(spare.isPresent());
        assertEquals(7, spare.get().count());
        assertEquals("58x42_30", spare.get().key().family());

        assertTrue(ResupplyItemIdentity.spareMagazineCommand(
            "tacz:modern_kinetic_gun{TaCZMag_StoredMagazine:{Count:1b,id:\"taczmagazines:magazine\",tag:{AmmoCount:30,AmmoId:\"tacz:58x42\",MagazineFamily:\"58x42_30\",MaxCapacity:30}}}").isEmpty());
    }
}
