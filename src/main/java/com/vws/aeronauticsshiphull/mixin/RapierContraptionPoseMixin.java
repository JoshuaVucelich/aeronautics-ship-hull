package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.SableQueries;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import net.minecraft.server.level.ServerLevel;

/**
 * Parked and settled ships do not move, so the kinematic contraption welded to them does not
 * need a fresh pose upload before {@code Rapier3D.step}. The upload allocates and may call
 * native {@code setKinematicContraptionTransform} even when the numbers have not changed.
 */
@Mixin(RapierPhysicsPipeline.class)
public abstract class RapierContraptionPoseMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "updateContraptionPose", at = @At("HEAD"), cancellable = true)
    private void ash$skipSettled(KinematicContraption contraption, double partialPhysicsTick, CallbackInfo ci) {
        try {
            ShipHullManager mgr = ShipHullManager.getOrNull(level);
            if (mgr == null) return;
            var pos = SableQueries.contraptionPosition(contraption);
            if (pos == null) return;
            if (mgr.skipContraptionPose(pos.x(), pos.y(), pos.z())) {
                HullProfiler.CONTRAPTION_POSE_SKIPS.incrementAndGet();
                ci.cancel();
            }
        } catch (Throwable t) {
            SableQueries.failOpen("contraption pose upload", t);
        }
    }
}
