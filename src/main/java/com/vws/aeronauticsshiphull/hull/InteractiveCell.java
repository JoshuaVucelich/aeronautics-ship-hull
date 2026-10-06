package com.vws.aeronauticsshiphull.hull;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Interactive block on a ship, addressed in ship-local BlockPos. */
public record InteractiveCell(BlockPos localPos, Kind kind, BlockState state) {
    public enum Kind {
        CHEST, SEAT, BEARING, ENGINE, OTHER
    }
}
