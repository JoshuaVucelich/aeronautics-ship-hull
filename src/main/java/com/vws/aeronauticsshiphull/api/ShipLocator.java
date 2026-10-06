package com.vws.aeronauticsshiphull.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

/** Block position -> owning ship (Sable sub-level UUID), or null when the block is on the ground. */
public final class ShipLocator {
    public static Object locate(Object levelObj, Long packed) {
        if (!(levelObj instanceof ServerLevel sl) || packed == null) return null;
        ServerSubLevelContainer c = SubLevelContainer.getContainer(sl);
        if (c == null) return null;
        BlockPos p = BlockPos.of(packed);
        if (!c.inBounds(p)) return null;
        LevelPlot plot = c.getPlot(new ChunkPos(p));
        if (plot == null) return null;
        SubLevel sub = plot.getSubLevel();
        if (sub instanceof ServerSubLevel ss && !ss.isRemoved()) return ss.getUniqueId();
        return null;
    }

    private ShipLocator() {}
}
