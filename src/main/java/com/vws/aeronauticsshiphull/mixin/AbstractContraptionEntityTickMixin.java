package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.vws.aeronauticsshiphull.hull.SableQueries;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * Create contraption entities mounted on a parked ship with nobody nearby tick every
 * {@code contraptionEntityPeriod} ticks instead of every tick. A player passenger, or a player
 * within the wake radius, keeps the full rate so controls and machines stay live.
 */
@Mixin(AbstractContraptionEntity.class)
public abstract class AbstractContraptionEntityTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ash$throttleParked(CallbackInfo ci) {
        try {
            if (ash$shouldThrottle()) {
                HullProfiler.CONTRAPTION_ENTITY_SKIPS.incrementAndGet();
                ci.cancel();
            }
        } catch (Throwable t) {
            SableQueries.failOpen("contraption entity tick", t);
        }
    }

    private boolean ash$shouldThrottle() {
        AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel sl)) return false;
        if (!(self instanceof KinematicContraption kc)) return false;
        for (var passenger : self.getPassengers()) {
            if (passenger instanceof Player) return false;
        }
        ShipHullManager mgr = ShipHullManager.getOrNull(sl);
        if (mgr == null) return false;
        // Mass tracker is still null at HEAD; Sable 2.0.3 assigns it later in this same tick.
        var pos = SableQueries.contraptionPosition(kc);
        if (pos == null) return false;
        return mgr.throttleContraptionEntity(pos.x(), pos.y(), pos.z(), sl.getGameTime());
    }
}
