package com.example.hcrpoints.client;

import com.example.hcrpoints.HCRPointsMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = HCRPointsMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientProxy {
    
    @SubscribeEvent
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        // 注册按键绑定
        event.register(ClientEventHandler.OPEN_GUI_KEY);
        event.register(ClientEventHandler.TACTICAL_MAP_KEY);
        event.register(ClientEventHandler.MAP_CONFIG_KEY); // 注册地图配置界面按键
        event.register(ClientEventHandler.OPEN_MD_READER_KEY); // 注册MD文件阅读器按键
    }
}
