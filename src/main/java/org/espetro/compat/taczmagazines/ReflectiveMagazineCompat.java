package org.espetro.compat.taczmagazines;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** Reflection against the public common-side API shared by 0.1.6 and 0.2.x. */
final class ReflectiveMagazineCompat implements MagazineCompat {
    private static final String MAGAZINE_ITEM =
        "com.raiiiden.taczmagazines.item.MagazineItem";

    private final Class<?> magazineClass;
    private final Method family;
    private final Method capacity;
    private final Method ammoId;
    private final Method ammoCount;
    private final Method createFour;
    private final Method createThree;
    private final Method setAmmoId;
    private final Method setAmmoCount;

    ReflectiveMagazineCompat() throws ReflectiveOperationException {
        magazineClass = Class.forName(MAGAZINE_ITEM, false,
            ReflectiveMagazineCompat.class.getClassLoader());
        family = magazineClass.getMethod("getMagazineFamilyId", ItemStack.class);
        capacity = magazineClass.getMethod("getMaxCapacity", ItemStack.class);
        ammoId = magazineClass.getMethod("getAmmoId", ItemStack.class);
        ammoCount = magazineClass.getMethod("getAmmoCount", ItemStack.class);
        setAmmoId = magazineClass.getMethod("setAmmoId", ItemStack.class,
            ResourceLocation.class);
        setAmmoCount = magazineClass.getMethod("setAmmoCount", ItemStack.class, int.class);

        Method four = null;
        Method three = null;
        for (Method method : magazineClass.getMethods()) {
            if (!method.getName().equals("createMagazineByFamily")
                || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() == 4) four = method;
            if (method.getParameterCount() == 3) three = method;
        }
        createFour = four;
        createThree = three;
        if (createFour == null && createThree == null) {
            throw new NoSuchMethodException("MagazineItem.createMagazineByFamily");
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<Identity> identity(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !magazineClass.isInstance(stack.getItem())) {
            return Optional.empty();
        }
        try {
            String familyId = (String) family.invoke(null, stack);
            int max = (Integer) capacity.invoke(null, stack);
            ResourceLocation ammunition = (ResourceLocation) invokeOnItem(ammoId, stack);
            if (familyId == null || familyId.isBlank() || ammunition == null || max <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Identity(familyId, ammunition, max));
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public int ammoCount(ItemStack stack) {
        if (identity(stack).isEmpty()) return 0;
        try {
            return Math.max(0, (Integer) invokeOnItem(ammoCount, stack));
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return 0;
        }
    }

    @Override
    public ItemStack createFull(ItemStack template) {
        Optional<Identity> resolved = identity(template);
        if (resolved.isEmpty()) return ItemStack.EMPTY;
        Identity id = resolved.get();
        try {
            ItemStack created;
            if (createFour != null) {
                created = (ItemStack) createFour.invoke(null, template.getItem(), id.family(),
                    id.capacity(), id.ammoId());
            } else {
                created = (ItemStack) createThree.invoke(null, template.getItem(), id.family(),
                    id.capacity());
                invokeOnItem(setAmmoId, created, id.ammoId());
            }
            if (created == null || created.isEmpty()) return ItemStack.EMPTY;
            invokeOnItem(setAmmoCount, created, id.capacity());
            created.setCount(1);
            return created;
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return ItemStack.EMPTY;
        }
    }

    private Object invokeOnItem(Method method, ItemStack stack, Object... extra)
            throws ReflectiveOperationException {
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : stack.getItem();
        Object[] args = new Object[1 + extra.length];
        args[0] = stack;
        System.arraycopy(extra, 0, args, 1, extra.length);
        return method.invoke(receiver, args);
    }
}
