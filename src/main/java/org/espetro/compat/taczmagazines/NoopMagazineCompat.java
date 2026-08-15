package org.espetro.compat.taczmagazines;

import net.minecraft.world.item.ItemStack;

import java.util.Optional;

final class NoopMagazineCompat implements MagazineCompat {
    static final NoopMagazineCompat INSTANCE = new NoopMagazineCompat();

    private NoopMagazineCompat() {
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Optional<Identity> identity(ItemStack stack) {
        return Optional.empty();
    }

    @Override
    public int ammoCount(ItemStack stack) {
        return 0;
    }

    @Override
    public ItemStack createFull(ItemStack template) {
        return ItemStack.EMPTY;
    }
}
