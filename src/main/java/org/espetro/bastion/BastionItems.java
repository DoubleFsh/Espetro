package org.espetro.bastion;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import org.espetro.Espetro;

/**
 * 兵站物品注册器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class BastionItems {

    // 兵站建筑指令鱼竿
    public static BastionBuildingWandItem BASTION_BUILDING_WAND;

    @SubscribeEvent
    public static void registerItems(RegisterEvent event) {
        event.register(Registries.ITEM, helper -> {
            // 创建兵站建筑指令鱼竿
            BASTION_BUILDING_WAND = new BastionBuildingWandItem();
            helper.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Espetro.MOD_ID, BastionBuildingWandItem.BASTION_WAND_ID),
                BASTION_BUILDING_WAND
            );
            Espetro.LOGGER.info("注册兵站建筑指令鱼竿: {}", BastionBuildingWandItem.BASTION_WAND_ID);
        });
    }
}
