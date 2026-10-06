package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.HullMode;
import com.vws.aeronauticsshiphull.hull.ShipHull;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

/**
 * CHEAP ships skip force application into Rapier. Pose is overwritten by CheapIntegrator
 * + teleport in endTick. Pilot never triggers FloatingBlockController from our side.
 */
@Mixin(ServerSubLevel.class)
public abstract class ServerSubLevelMixin {

    private static boolean ash$cheap(ServerSubLevel self) {
        ShipHullManager mgr = ShipHullManager.getOrNull(self.getLevel());
        if (mgr == null || !mgr.globallyEnabled()) return false;
        ShipHull hull = mgr.get(self);
        return hull != null && hull.enabled && hull.mode == HullMode.CHEAP;
    }

    @Inject(method = "prePhysicsTick", at = @At("HEAD"), cancellable = true)
    private void ash$skipPreTick(SubLevelPhysicsSystem system, RigidBodyHandle handle, double dt, CallbackInfo ci) {
        if (ash$cheap((ServerSubLevel) (Object) this)) ci.cancel();
    }

    @Inject(method = "applyQueuedForces", at = @At("HEAD"), cancellable = true)
    private void ash$skipForces(SubLevelPhysicsSystem system, RigidBodyHandle handle, double dt, CallbackInfo ci) {
        if (ash$cheap((ServerSubLevel) (Object) this)) ci.cancel();
    }
}
