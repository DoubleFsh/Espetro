package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FortificationTransformTest {

    @Test
    void usesOneAnchorOriginPivotFormulaForAllFourDirections() {
        BlockPos anchor = new BlockPos(100, 64, -30);
        BlockPos origin = new BlockPos(2, 1, -1);
        BlockPos local = new BlockPos(4, 3, 7);
        BlockPos pivot = new BlockPos(1, 2, 2);
        // relative before rotation = (5,2,4)
        assertEquals(new BlockPos(105, 66, -26),
            FortificationTransform.world(anchor, origin, local, pivot, Direction.NORTH));
        assertEquals(new BlockPos(96, 66, -25),
            FortificationTransform.world(anchor, origin, local, pivot, Direction.EAST));
        assertEquals(new BlockPos(95, 66, -34),
            FortificationTransform.world(anchor, origin, local, pivot, Direction.SOUTH));
        assertEquals(new BlockPos(104, 66, -35),
            FortificationTransform.world(anchor, origin, local, pivot, Direction.WEST));
    }
}
