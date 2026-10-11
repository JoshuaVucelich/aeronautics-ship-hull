package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.vws.aeronauticsshiphull.config.HullConfig;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;
import com.vws.aeronauticsshiphull.mixin.accessor.Rapier3DInvoker;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.config.PhysicsConfigData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;

/**
 * Phase 6 scene-level cuts in Sable's physics loop:
 *  - idle skip: no Rapier ship anywhere => skip the whole substep loop (Rapier step + per-ship Java work);
 *  - distant substeps: Rapier ships exist but none piloted/near a player => 1 substep instead of N;
 *  - dormant skip: CHEAP/PARKED ships skip prePhysicsTickBegin, mass merge and pose readback per substep.
 * Also times the whole SubLevelPhysicsSystem.tick for before/after numbers.
 */
@Mixin(SubLevelPhysicsSystem.class)
public abstract class SubLevelPhysicsStepMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private PhysicsConfigData config;
    @Shadow private int currentSubstep;

    @Unique private int ash$savedSubsteps = -1;
    @Unique private long ash$tickStart;
    @Unique private boolean ash$solverLod;

    @Inject(method = "tick", at = @At("HEAD"))
    private void ash$tickHead(SubLevelContainer container, CallbackInfo ci) {
        ash$tickStart = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void ash$tickReturn(SubLevelContainer container, CallbackInfo ci) {
        HullProfiler.SABLE_TICK_NS.addAndGet(System.nanoTime() - ash$tickStart);
        HullProfiler.SABLE_TICK_CALLS.incrementAndGet();
    }

    @Inject(method = "tickPipelinePhysics", at = @At("HEAD"), cancellable = true)
    private void ash$stepHead(ServerSubLevelContainer container, CallbackInfo ci) {
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr == null) return;
        if (mgr.shouldSkipStep((SubLevelPhysicsSystem) (Object) this)) {
            // Keep Sable's bookkeeping consistent: removals still processed, substep counter at "done".
            container.processSubLevelRemovals();
            this.currentSubstep = this.config.substepsPerTick;
            HullProfiler.IDLE_STEP_SKIPS.incrementAndGet();
            ci.cancel();
            return;
        }
        if (mgr.shouldCoastStep()) {
            container.processSubLevelRemovals();
            this.currentSubstep = this.config.substepsPerTick;
            HullProfiler.COAST_SKIPS.incrementAndGet();
            ci.cancel();
            return;
        }
        mgr.noteStepRan();
        if (mgr.distantSolverThisTick()) {
            ash$solverLod = true;
            Rapier3DInvoker.ash$configSolverIterations(
                    HullConfig.distantSolverIterations(), this.config.pgsIterations, this.config.stabilizationIterations);
            HullProfiler.SOLVER_LOD_TICKS.incrementAndGet();
        }
        if (mgr.reducedSubsteps() && this.config.substepsPerTick > 1) {
            ash$savedSubsteps = this.config.substepsPerTick;
            this.config.substepsPerTick = 1;
            HullProfiler.REDUCED_SUBSTEP_TICKS.incrementAndGet();
        }
    }

    @Inject(method = "tickPipelinePhysics", at = @At("RETURN"))
    private void ash$stepReturn(ServerSubLevelContainer container, CallbackInfo ci) {
        if (ash$solverLod) {
            Rapier3DInvoker.ash$configSolverIterations(
                    this.config.solverIterations, this.config.pgsIterations, this.config.stabilizationIterations);
            ash$solverLod = false;
        }
        if (ash$savedSubsteps > 0) {
            this.config.substepsPerTick = ash$savedSubsteps;
            this.currentSubstep = ash$savedSubsteps;
            ash$savedSubsteps = -1;
        }
    }

    @Unique
    private boolean ash$work(ServerSubLevel sub) {
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr != null && mgr.skipDormantWork(sub)) {
            HullProfiler.DORMANT_WORK_SKIPS.incrementAndGet();
            return false;
        }
        return true;
    }

    @WrapWithCondition(method = "tickPipelinePhysics",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;prePhysicsTickBegin()V"))
    private boolean ash$gateBegin(ServerSubLevel sub) {
        return ash$work(sub);
    }

    @WrapWithCondition(method = "tickPipelinePhysics",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;updateMergedMassData(F)V"))
    private boolean ash$gateMass(ServerSubLevel sub, float partial) {
        return ash$work(sub);
    }

    @WrapWithCondition(method = "updateAllPoses",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;updatePose(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V"))
    private boolean ash$gatePose(SubLevelPhysicsSystem self, ServerSubLevel sub) {
        return ash$work(sub);
    }
}
