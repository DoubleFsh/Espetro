package org.espetro.logistics;

import net.minecraft.world.item.Item;

/**
 * Espetro 建材补给：普通物品，不可放置方块。
 * 外观与铁锭相同（见 item model）；存入 FOB 仍依赖 {@link SupplyManager} 的 NBT 标记。
 */
public final class ConstructionMaterialItem extends Item {

    public ConstructionMaterialItem(Properties properties) {
        super(properties);
    }
}
