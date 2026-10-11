package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.JointLinks;

import dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle;

/** Drop a joint from the connectivity graph when Sable removes it. */
@Mixin(RapierConstraintHandle.class)
public abstract class RapierConstraintRemoveMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void ash$forgetJoint(CallbackInfo ci) {
        JointLinks.removed(this);
    }
}
