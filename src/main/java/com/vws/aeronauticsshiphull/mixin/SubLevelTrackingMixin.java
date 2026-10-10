package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.server.level.ServerLevel;

/**
 * Phase 8: Sable sends a MOVE snapshot whenever {@code logicalPose} is outside tolerance of
 * {@code lastNetworkedPose}, and that branch clears {@code lastNetworkedStopped}. Phase 7 sets
 * the flag in the post-tick, which is after this method, so a moving ship still sent every tick.
 *
 * On a throttled tick, report the pose as within tolerance and mark the ship stopped first, so
 * the else-branch continues without copying {@code lastNetworkedPose} or enqueueing a packet.
 * Pulse ticks call the real comparison and send the current pose.
 */
@Mixin(SubLevelTrackingSystem.class)
public abstract class SubLevelTrackingMixin {
    @Shadow @Final private ServerLevel level;

    @WrapOperation(method = "sendMovementUpdates", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/companion/math/Pose3d;withinTolerance(Ldev/ryanhcode/sable/companion/math/Pose3d;DD)Z"))
    private boolean ash$throttlePose(Pose3d pose, Pose3d other, double posTol, double rotTol, Operation<Boolean> original) {
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr != null) {
            ServerSubLevel sub = mgr.sublevelForLogicalPose(pose);
            if (sub != null && mgr.throttlePoseSend(sub)) {
                if (!sub.getLastNetworkedStopped()) sub.setLastNetworkedStopped(true);
                HullProfiler.POSE_SEND_SKIPS.incrementAndGet();
                return true;
            }
        }
        return original.call(pose, other, posTol, rotTol);
    }
}
