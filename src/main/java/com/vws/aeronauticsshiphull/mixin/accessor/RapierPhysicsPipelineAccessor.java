package com.vws.aeronauticsshiphull.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

@Mixin(RapierPhysicsPipeline.class)
public interface RapierPhysicsPipelineAccessor {
    @Accessor("activeSubLevels")
    Int2ObjectMap<ServerSubLevel> ash$activeSubLevels();
}
