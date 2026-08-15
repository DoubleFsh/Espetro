package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

/**
 * The single coordinate transform used by preview, collision, placement and indexing.
 *
 * <p>All coordinates are integral template block coordinates and follow
 * {@code world = anchor + R(originOffset + local - pivot)}.  Structure v2 never
 * mirrors templates.</p>
 */
public final class FortificationTransform {

    private FortificationTransform() {
    }

    public static BlockPos world(BlockPos anchor, BlockPos originOffset, BlockPos local,
                                 BlockPos pivot, Direction facing) {
        BlockPos relative = originOffset.offset(local).subtract(pivot);
        BlockPos rotated = rotate(relative, facing);
        return anchor.offset(rotated);
    }

    public static BlockPos rotate(BlockPos relative, Direction facing) {
        return switch (horizontal(facing)) {
            case NORTH -> relative;
            case EAST -> new BlockPos(-relative.getZ(), relative.getY(), relative.getX());
            case SOUTH -> new BlockPos(-relative.getX(), relative.getY(), -relative.getZ());
            case WEST -> new BlockPos(relative.getZ(), relative.getY(), -relative.getX());
            default -> throw new IllegalStateException("unreachable");
        };
    }

    public static Rotation rotation(Direction facing) {
        return switch (horizontal(facing)) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalStateException("unreachable");
        };
    }

    private static Direction horizontal(Direction facing) {
        if (facing == null || !facing.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("facing must be horizontal");
        }
        return facing;
    }
}
