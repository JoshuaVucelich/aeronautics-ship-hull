package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.ShipHullManager;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Phase 8: entities stored in a far unpiloted ship's plot skip {@code sendChanges}
 * on the same period as the pose snapshot. Players and anything carrying a player stay at full rate.
 * A pulse tick runs the real send, so position and metadata catch up together.
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntitySyncMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Entity entity;

    @Inject(method = "sendChanges", at = @At("HEAD"), cancellable = true)
    private void ash$throttleSend(CallbackInfo ci) {
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr != null && mgr.throttleEntitySync(entity)) ci.cancel();
    }
}
