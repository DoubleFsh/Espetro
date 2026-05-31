package org.espetro.vehicle;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import org.espetro.Espetro;

/**
 * 载具相关物品注册器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class VehicleItems {

    // 载具部署木棍 - 指挥官右键使用
    public static Item VEHICLE_DEPLOY_STICK;

    @SubscribeEvent
    public static void registerItems(RegisterEvent event) {
        event.register(Registries.ITEM, helper -> {
            VEHICLE_DEPLOY_STICK = new Item(new Item.Properties().stacksTo(1));
            helper.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Espetro.MOD_ID, "vehicle_deploy_stick"),
                VEHICLE_DEPLOY_STICK
            );
            Espetro.LOGGER.info("注册载具部署木棍: vehicle_deploy_stick");
        });
    }
}
