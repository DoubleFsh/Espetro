package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;

/**
 * 阵营数据Provider
 * 负责在服务器启动时加载阵营和职业数据
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FactionDataProvider {

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        ResourceManager resourceManager = server.getResourceManager();
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        loader.ensureLoaded(resourceManager);
    }

    private static FactionDataLoader loader;

    public static FactionDataLoader getOrCreateLoader() {
        if (loader == null) {
            loader = new FactionDataLoader();
        }
        return loader;
    }
}
