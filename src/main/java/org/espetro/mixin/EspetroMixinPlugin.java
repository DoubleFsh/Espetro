package org.espetro.mixin;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 仅在安装 Superb Warfare 时应用残骸加速 mixin。
 * DragonRise / FCP 载具继承 SBW {@code VehicleEntity}，无需单独目标类。
 */
public final class EspetroMixinPlugin implements IMixinConfigPlugin {

    private static final String SBW_MOD_ID = "superbwarfare";
    private static final String OPTIONAL_SEAT_MIXIN =
        "org.espetro.mixin.sbw.VehicleEntitySeatAccessMixin";
    private static final String OPTIONAL_SEAT_PACKET_MIXIN =
        "org.espetro.mixin.sbw.ChangeVehicleSeatMessageMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // These two mixins use @Pseudo and string targets. Always registering them
        // avoids an early Forge mod-list timing window; they safely disappear when
        // SBW is absent and must inject when the target is present.
        if (OPTIONAL_SEAT_MIXIN.equals(mixinClassName)
            || OPTIONAL_SEAT_PACKET_MIXIN.equals(mixinClassName)) {
            return true;
        }
        if (mixinClassName != null && mixinClassName.contains(".sbw.")) {
            return isModPresent(SBW_MOD_ID);
        }
        return true;
    }

    private static boolean isModPresent(String modId) {
        try {
            ModFileInfo info = FMLLoader.getLoadingModList().getModFileById(modId);
            return info != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
