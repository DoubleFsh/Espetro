package org.espetro.bastion;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Visible construction foundation shared by every unfinished fortification. */
public final class OnBuildingBlock extends Block {
    public static final String BLOCK_ID = "onbuilding";
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 6);

    public OnBuildingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STAGE, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STAGE);
    }
}
