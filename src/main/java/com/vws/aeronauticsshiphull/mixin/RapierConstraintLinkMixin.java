package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.vws.aeronauticsshiphull.hull.JointLinks;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;

/** Remember joints so a connected group can park and wake as one set. */
@Mixin(RapierPhysicsPipeline.class)
public abstract class RapierConstraintLinkMixin {
    @Inject(method = "addConstraint", at = @At("RETURN"))
    private void ash$rememberJoint(PhysicsPipelineBody a, PhysicsPipelineBody b, PhysicsConstraintConfiguration<?> config,
                                   CallbackInfoReturnable<PhysicsConstraintHandle> cir) {
        JointLinks.added(cir.getReturnValue(), a, b);
    }
}
