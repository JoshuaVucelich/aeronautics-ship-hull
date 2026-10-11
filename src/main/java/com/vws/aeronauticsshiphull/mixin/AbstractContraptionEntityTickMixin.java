package com.vws.aeronauticsshiphull.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
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
        AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel sl)) return;
        if (!(self instanceof KinematicContraption kc) || !kc.sable$isValid()) return;
        for (var passenger : self.getPassengers()) {
            if (passenger instanceof Player) return;
        }
        ShipHullManager mgr = ShipHullManager.getOrNull(sl);
        if (mgr == null) return;
        var pos = kc.sable$getPosition();
        if (mgr.throttleContraptionEntity(pos.x(), pos.y(), pos.z(), sl.getGameTime())) {
            HullProfiler.CONTRAPTION_ENTITY_SKIPS.incrementAndGet();
            ci.cancel();
        }
    }
}
