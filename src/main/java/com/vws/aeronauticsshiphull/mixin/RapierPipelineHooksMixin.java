package com.vws.aeronauticsshiphull.mixin;

import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.ShipHullManager;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Phase 6 pipeline hooks:
 *  - collider batching: voxel updates for ships with colliders off are deferred (de-duplicated) and replayed on wake;
 *  - wake: impulses / velocity changes on a PARKED body from other code wake the ship;
 *    teleports (e.g. Sable's centre-of-mass correction) are adopted as the new parked pose.
 */
@Mixin(RapierPhysicsPipeline.class)
public abstract class RapierPipelineHooksMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "handleBlockChange", at = @At("HEAD"), cancellable = true)
    private void ash$deferCollider(SectionPos sectionPos, LevelChunkSection chunk, int x, int y, int z,
                                   BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (ShipHullManager.REPLAYING) return;
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr == null) return;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        LevelPlot plot = container.getPlot(sectionPos.chunk());
        if (plot == null || !(plot.getSubLevel() instanceof ServerSubLevel sub)) return;
        if (mgr.deferColliderUpdate(sub, (sectionPos.x() << 4) + x, (sectionPos.y() << 4) + y, (sectionPos.z() << 4) + z)) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void ash$wake(PhysicsPipelineBody body, String why) {
        if (ShipHullManager.INTERNAL || !(body instanceof ServerSubLevel sub)) return;
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr != null) mgr.requestWake(sub, why);
    }

    @Inject(method = "applyImpulse", at = @At("HEAD"))
    private void ash$wakeImpulse(PhysicsPipelineBody body, Vector3dc position, Vector3dc force, CallbackInfo ci) {
        ash$wake(body, "applyImpulse");
    }

    @Inject(method = "applyLinearAndAngularImpulse", at = @At("HEAD"))
    private void ash$wakeLinAng(PhysicsPipelineBody body, Vector3dc force, Vector3dc torque, boolean wakeUp, CallbackInfo ci) {
        ash$wake(body, "applyLinearAndAngularImpulse");
    }

    @Inject(method = "addLinearAndAngularVelocity", at = @At("HEAD"))
    private void ash$wakeVel(PhysicsPipelineBody body, Vector3dc lin, Vector3dc ang, CallbackInfo ci) {
        ash$wake(body, "addVelocity");
    }

    @Inject(method = "teleport", at = @At("HEAD"))
    private void ash$adoptTeleport(PhysicsPipelineBody body, Vector3dc position, Quaterniondc orientation, CallbackInfo ci) {
        // Sable re-teleports a body when its centre of mass shifts (block change). That is a pose
        // correction, not a push: a parked ship adopts the new pose and stays parked.
        if (ShipHullManager.INTERNAL || !(body instanceof ServerSubLevel sub)) return;
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr != null) mgr.adoptParkedPose(sub, position, orientation);
    }
}
