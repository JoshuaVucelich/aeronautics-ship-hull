package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.ShipHullManager;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

@Mixin(SubLevelPhysicsSystem.class)
public abstract class SubLevelPhysicsSystemMixin {

    @Inject(method = "onSubLevelAdded", at = @At("TAIL"))
    private void ash$added(SubLevel subLevel, CallbackInfo ci) {
        if (subLevel instanceof ServerSubLevel ssl) {
            ShipHullManager.get(ssl.getLevel()).onSubLevelAdded(ssl);
        }
    }

    @Inject(method = "onSubLevelRemoved", at = @At("HEAD"))
    private void ash$removed(SubLevel subLevel, SubLevelRemovalReason reason, CallbackInfo ci) {
        if (subLevel instanceof ServerSubLevel ssl) {
            ShipHullManager mgr = ShipHullManager.getOrNull(ssl.getLevel());
            if (mgr != null) mgr.onSubLevelRemoved(ssl);
        }
    }
}
