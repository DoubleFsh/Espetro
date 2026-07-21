package org.espetro.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class SupplySourceBlockEntity extends BlockEntity {

    private String sourceId = "default";

    public SupplySourceBlockEntity(BlockPos pos, BlockState state) {
        super(LogisticsBlocks.SUPPLY_SOURCE_BLOCK_ENTITY, pos, state);
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId == null || sourceId.isBlank() ? "default" : sourceId;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("source_id", sourceId);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("source_id")) {
            sourceId = tag.getString("source_id");
        }
    }
}
