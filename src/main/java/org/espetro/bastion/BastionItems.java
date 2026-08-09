package org.espetro.bastion;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import org.espetro.Espetro;

/**
 * Radio 方块/物品注册（部署入口统一在 Alt 轮盘）。
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class BastionItems {

    public static RadioBlock RADIO_BLOCK;
    public static BlockItem RADIO_BLOCK_ITEM;
    public static OnBuildingBlock ON_BUILDING_BLOCK;

    @SubscribeEvent
    public static void registerAll(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            // 抗爆取石头级：允许炸药摧毁（走 Detonate 扣兵力路径）；挖掘时长由 BreakSpeed 锁 30s
            RADIO_BLOCK = new RadioBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .strength(6.0f, 30.0f)
                .sound(SoundType.METAL)
                .noOcclusion());
            helper.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    Espetro.MOD_ID, RadioBlock.BLOCK_ID),
                RADIO_BLOCK
            );
            ON_BUILDING_BLOCK = new OnBuildingBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_YELLOW)
                .strength(1.0f, 1.0f)
                .sound(SoundType.WOOD)
                .noOcclusion());
            helper.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    Espetro.MOD_ID, OnBuildingBlock.BLOCK_ID),
                ON_BUILDING_BLOCK
            );
        });
        event.register(Registries.ITEM, helper -> {
            RADIO_BLOCK_ITEM = new BlockItem(RADIO_BLOCK, new Item.Properties().stacksTo(1));
            helper.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    Espetro.MOD_ID, RadioBlock.BLOCK_ID),
                RADIO_BLOCK_ITEM
            );
            Espetro.LOGGER.info("注册 Radio 方块（部署走 Alt 轮盘）");
        });
    }
}
