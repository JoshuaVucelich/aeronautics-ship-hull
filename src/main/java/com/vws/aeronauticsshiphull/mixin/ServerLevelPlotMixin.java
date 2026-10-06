package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.ShipHullManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;

@Mixin(ServerLevelPlot.class)
public abstract class ServerLevelPlotMixin {

    @Inject(method = "onBlockChange", at = @At("TAIL"))
    private void ash$blockChange(BlockPos pos, BlockState state, CallbackInfo ci) {
        ServerLevelPlot self = (ServerLevelPlot) (Object) this;
        ServerSubLevel sub = self.getSubLevel();
        if (sub == null) return;
        ShipHullManager mgr = ShipHullManager.getOrNull(sub.getLevel());
        if (mgr == null) return;
        // We don't get old state here; treat empty→state as place, else update both ways.
        BlockState air = Blocks.AIR.defaultBlockState();
        if (state.isAir()) {
            mgr.onBlockChange(sub, pos, Blocks.STONE.defaultBlockState(), air);
        } else {
            mgr.onBlockChange(sub, pos, air, state);
        }
    }
}
