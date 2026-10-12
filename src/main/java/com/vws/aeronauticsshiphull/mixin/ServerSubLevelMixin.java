package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.vws.aeronauticsshiphull.hull.PoseGuard;
import com.vws.aeronauticsshiphull.hull.SableQueries;
import com.vws.aeronauticsshiphull.hull.ShipHull;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

/**
 * CHEAP and PARKED ships skip force application into Rapier. Pose is overwritten by CheapIntegrator
 * + teleport in endTick. Pilot never triggers FloatingBlockController from our side.
 */
@Mixin(ServerSubLevel.class)
public abstract class ServerSubLevelMixin {

    private static boolean ash$cheap(ServerSubLevel self) {
        ShipHullManager mgr = ShipHullManager.getOrNull(self.getLevel());
        if (mgr == null || !mgr.globallyEnabled()) return false;
        ShipHull hull = mgr.get(self);
        return hull != null && hull.enabled && hull.mode.dormant();
    }

    @Inject(method = "prePhysicsTick", at = @At("HEAD"), cancellable = true)
    private void ash$skipPreTick(SubLevelPhysicsSystem system, RigidBodyHandle handle, double dt, CallbackInfo ci) {
        if (ash$cheap((ServerSubLevel) (Object) this)) ci.cancel();
    }

    @Inject(method = "applyQueuedForces", at = @At("HEAD"), cancellable = true)
    private void ash$skipForces(SubLevelPhysicsSystem system, RigidBodyHandle handle, double dt, CallbackInfo ci) {
        if (ash$cheap((ServerSubLevel) (Object) this)) ci.cancel();
    }

    /** Before Sable's extreme-Y removal check. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void ash$poseGuardHead(CallbackInfo ci) {
        PoseGuard.beforeTick((ServerSubLevel) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void ash$poseGuardTail(CallbackInfo ci) {
        PoseGuard.afterTick((ServerSubLevel) (Object) this);
    }

    /** Splitting heat maps walk the voxel set. A dormant ship is not splitting. */
    @WrapWithCondition(method = "tick",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/plot/heat/SubLevelHeatMapManager;tick()V"))
    private boolean ash$heat(SubLevelHeatMapManager heat) {
        try {
            ServerSubLevel self = (ServerSubLevel) (Object) this;
            ShipHullManager mgr = ShipHullManager.getOrNull(self.getLevel());
            if (mgr != null && mgr.syncSafe()) {
                ShipHull hull = mgr.get(self);
                if (hull != null && hull.poseReady()) {
                    var tracking = self.getTrackingPlayers();
                    if (tracking == null || tracking.isEmpty()) {
                        HullProfiler.HEATMAP_SKIPS.incrementAndGet();
                        return false;
                    }
                }
                return true;
            }
            if (!ShipHullManager.contraptionTick()) return true;
            if (!ash$cheap(self)) return true;
            ShipHull hull = ShipHullManager.getOrNull(self.getLevel()) == null
                    ? null : ShipHullManager.getOrNull(self.getLevel()).get(self);
            if (hull == null || !hull.poseReady()) return true;
            HullProfiler.HEATMAP_SKIPS.incrementAndGet();
            return false;
        } catch (Throwable t) {
            SableQueries.failOpen("heat map", t);
            return true;
        }
    }
}
