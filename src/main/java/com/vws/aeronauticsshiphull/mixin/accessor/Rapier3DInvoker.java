package com.vws.aeronauticsshiphull.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;

@Mixin(Rapier3D.class)
public interface Rapier3DInvoker {
    /** Sable 1.x: scene handle is int (getID), not long (getSceneHandle). */
    @Invoker("setLocalBounds")
    static void ash$setLocalBounds(int scene, int bodyId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        throw new AssertionError();
    }
}
