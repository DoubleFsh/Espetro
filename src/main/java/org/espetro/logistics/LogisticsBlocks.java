package org.espetro.logistics;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import org.espetro.Espetro;

@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LogisticsBlocks {

    public static final ResourceLocation SUPPLY_SOURCE_ID =
        ResourceLocation.fromNamespaceAndPath(Espetro.MOD_ID, "supply_source");

    public static SupplySourceBlock SUPPLY_SOURCE;
    public static BlockEntityType<SupplySourceBlockEntity> SUPPLY_SOURCE_BLOCK_ENTITY;

    private LogisticsBlocks() {
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            SUPPLY_SOURCE = new SupplySourceBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5f)
                .sound(SoundType.METAL));
            helper.register(SUPPLY_SOURCE_ID, SUPPLY_SOURCE);
        });

        event.register(Registries.ITEM, helper ->
            helper.register(SUPPLY_SOURCE_ID, new BlockItem(SUPPLY_SOURCE, new Item.Properties())));

        event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
            SUPPLY_SOURCE_BLOCK_ENTITY = BlockEntityType.Builder
                .of(SupplySourceBlockEntity::new, SUPPLY_SOURCE)
                .build(null);
            helper.register(SUPPLY_SOURCE_ID, SUPPLY_SOURCE_BLOCK_ENTITY);
        });
    }
}
