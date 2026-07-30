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
    public static final ResourceLocation CONSTRUCTION_MATERIAL_ID =
        ResourceLocation.fromNamespaceAndPath(Espetro.MOD_ID, "construction_material");

    public static SupplySourceBlock SUPPLY_SOURCE;
    public static BlockEntityType<SupplySourceBlockEntity> SUPPLY_SOURCE_BLOCK_ENTITY;
    /** 建材补给：纯物品，不可放置。 */
    public static ConstructionMaterialItem CONSTRUCTION_MATERIAL;

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

        event.register(Registries.ITEM, helper -> {
            helper.register(SUPPLY_SOURCE_ID, new BlockItem(SUPPLY_SOURCE, new Item.Properties()));
            CONSTRUCTION_MATERIAL = new ConstructionMaterialItem(
                new Item.Properties().stacksTo(64));
            helper.register(CONSTRUCTION_MATERIAL_ID, CONSTRUCTION_MATERIAL);
        });

        event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
            SUPPLY_SOURCE_BLOCK_ENTITY = BlockEntityType.Builder
                .of(SupplySourceBlockEntity::new, SUPPLY_SOURCE)
                .build(null);
            helper.register(SUPPLY_SOURCE_ID, SUPPLY_SOURCE_BLOCK_ENTITY);
        });
    }
}
