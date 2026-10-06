package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.ShipHullManager;

import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;

/**
 * Before each Rapier step, divert CHEAP hulls out of the native scene so they are not solved.
 */
@Mixin(RapierPhysicsPipeline.class)
public abstract class RapierPhysicsPipelineMixin {

    @Inject(method = "physicsTick", at = @At("HEAD"))
    private void ash$beforeStep(double timeStep, CallbackInfo ci) {
        SubLevelPhysicsSystem sys = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        if (sys == null) return;
        ServerLevel level = sys.getLevel();
        if (level == null) return;
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr != null) mgr.divertCheapFromRapier(sys);
    }
}
