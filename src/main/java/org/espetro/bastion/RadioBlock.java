package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Radio 电台方块：队伍补给锚点核心。
 * 破坏耗时约 30 秒（strength + BreakSpeed 事件锁定），敌方破坏扣兵力。
 */
public class RadioBlock extends HorizontalDirectionalBlock {

    public static final String BLOCK_ID = "radio";
    /** 目标徒手破坏时长（秒）；BreakSpeed 事件按此锁定挖掘速度。 */
    public static final int BREAK_SECONDS = 30;

    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 15, 15);

    public RadioBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
