package org.espetro.bastion;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;

/** Keeps the fortification indexes in sync with block destruction. */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public final class FortificationEventHandler {

    private FortificationEventHandler() {
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!event.isCanceled() && event.getLevel() instanceof ServerLevel level) {
            FortificationManager.getInstance().removeAt(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (var pos : event.getAffectedBlocks()) {
            FortificationManager.getInstance().removeAt(level, pos);
        }
    }
}
