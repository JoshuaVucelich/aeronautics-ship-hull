package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.vws.aeronauticsshiphull.hull.SableQueries;
import com.vws.aeronauticsshiphull.hull.ShipTickGate;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Non-player entities in a parked ship's plot stop ticking while nobody is within the wake radius.
 * Sable keeps those chunks in entity-ticking range so the entities would otherwise tick forever.
 * They resume the tick a player walks up. Items do not fall off or despawn in the meantime.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityTickMixin {

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void ash$skipParkedPlotEntities(Entity entity, CallbackInfo ci) {
        try {
            if (entity instanceof Player) return;
            ServerLevel self = (ServerLevel) (Object) this;
            if (ShipTickGate.skipEntity(self, entity)) {
                HullProfiler.ENTITY_TICK_SKIPS.incrementAndGet();
                ci.cancel();
            }
        } catch (Throwable t) {
            SableQueries.failOpen("parked entity tick", t);
        }
    }
}
