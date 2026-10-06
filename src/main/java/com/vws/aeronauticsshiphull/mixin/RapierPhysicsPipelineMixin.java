package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.ShipHullManager;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;

/**
 * Before each Rapier step, note CHEAP hulls. Around physicsTick, accumulate wall time so
 * Phase 5 can compare soft-sleep vs force-skip-only.
 */
@Mixin(RapierPhysicsPipeline.class)
public abstract class RapierPhysicsPipelineMixin {

    private static final ThreadLocal<Long> ASH_TICK_START = new ThreadLocal<>();

    @Inject(method = "physicsTick", at = @At("HEAD"))
    private void ash$beforeStep(double timeStep, CallbackInfo ci) {
        ASH_TICK_START.set(System.nanoTime());
        SubLevelPhysicsSystem sys = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        if (sys == null) return;
        ServerLevel level = sys.getLevel();
        if (level == null) return;
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr != null) mgr.divertCheapFromRapier(sys);
    }

    @Inject(method = "physicsTick", at = @At("RETURN"))
    private void ash$afterStep(double timeStep, CallbackInfo ci) {
        Long start = ASH_TICK_START.get();
        ASH_TICK_START.remove();
        if (start != null) {
            HullProfiler.RAPIER_PHYSICS_TICK_NS.addAndGet(System.nanoTime() - start);
            HullProfiler.RAPIER_PHYSICS_TICK_CALLS.incrementAndGet();
        }
    }
}
