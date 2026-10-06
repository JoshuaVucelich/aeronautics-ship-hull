package com.vws.aeronauticsshiphull.profile;

import java.util.concurrent.atomic.AtomicLong;

public final class HullProfiler {
    public static final AtomicLong RAPIER_STEPS = new AtomicLong();
    public static final AtomicLong CHEAP_STEPS = new AtomicLong();
    public static final AtomicLong RAPIER_BODIES_SKIPPED = new AtomicLong();
    public static final AtomicLong MODE_PROMOTIONS = new AtomicLong();
    public static final AtomicLong MODE_DEMOTIONS = new AtomicLong();
    public static final AtomicLong EXPLOSIONS = new AtomicLong();
    public static final AtomicLong SPLITS = new AtomicLong();
    /** Times we emptied local bounds / suppressed colliders for CHEAP soft-sleep. */
    public static final AtomicLong COLLIDER_DISABLES = new AtomicLong();
    public static final AtomicLong COLLIDER_RESTORES = new AtomicLong();
    /** Cumulative nanoseconds spent inside RapierPhysicsPipeline.physicsTick. */
    public static final AtomicLong RAPIER_PHYSICS_TICK_NS = new AtomicLong();
    public static final AtomicLong RAPIER_PHYSICS_TICK_CALLS = new AtomicLong();

    public static void reset() {
        RAPIER_STEPS.set(0);
        CHEAP_STEPS.set(0);
        RAPIER_BODIES_SKIPPED.set(0);
        MODE_PROMOTIONS.set(0);
        MODE_DEMOTIONS.set(0);
        EXPLOSIONS.set(0);
        SPLITS.set(0);
        COLLIDER_DISABLES.set(0);
        COLLIDER_RESTORES.set(0);
        RAPIER_PHYSICS_TICK_NS.set(0);
        RAPIER_PHYSICS_TICK_CALLS.set(0);
    }

    public static String summary() {
        long calls = RAPIER_PHYSICS_TICK_CALLS.get();
        double avgUs = calls == 0 ? 0.0 : (RAPIER_PHYSICS_TICK_NS.get() / 1000.0) / calls;
        return String.format(
                "rapierSteps=%d cheapSteps=%d skippedBodies=%d promotions=%d demotions=%d explosions=%d splits=%d colliderOff=%d colliderOn=%d physTickCalls=%d physTickAvgUs=%.1f",
                RAPIER_STEPS.get(), CHEAP_STEPS.get(), RAPIER_BODIES_SKIPPED.get(),
                MODE_PROMOTIONS.get(), MODE_DEMOTIONS.get(), EXPLOSIONS.get(), SPLITS.get(),
                COLLIDER_DISABLES.get(), COLLIDER_RESTORES.get(), calls, avgUs);
    }

    private HullProfiler() {}
}
