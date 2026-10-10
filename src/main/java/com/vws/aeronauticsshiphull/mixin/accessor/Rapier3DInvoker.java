package com.vws.aeronauticsshiphull.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;

@Mixin(Rapier3D.class)
public interface Rapier3DInvoker {
    @Invoker("setLocalBounds")
    static void ash$setLocalBounds(long scene, int bodyId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        throw new AssertionError();
    }

    @Invoker("configSolverIterations")
    static void ash$configSolverIterations(int solverIterations, int pgsIterations, int stabilizationIterations) {
        throw new AssertionError();
    }
}
