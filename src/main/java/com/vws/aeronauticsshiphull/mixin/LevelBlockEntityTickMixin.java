package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.vws.aeronauticsshiphull.hull.ShipTickGate;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

@Mixin(Level.class)
public abstract class LevelBlockEntityTickMixin {

    @WrapOperation(method = "tickBlockEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void ash$throttleShipBlockEntities(TickingBlockEntity ticker, Operation<Void> original) {
        Level self = (Level) (Object) this;
        if (self instanceof ServerLevel sl && !ShipTickGate.allowBlockEntity(sl, ticker.getPos())) {
            HullProfiler.BLOCK_ENTITY_SKIPS.incrementAndGet();
            return;
        }
        original.call(ticker);
    }
}
