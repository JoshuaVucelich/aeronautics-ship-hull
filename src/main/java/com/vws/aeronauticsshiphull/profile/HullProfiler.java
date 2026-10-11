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
    // Phase 6
    /** Whole SubLevelPhysicsSystem.tick (tickets + actors + substep loop + Rapier) wall time. */
    public static final AtomicLong SABLE_TICK_NS = new AtomicLong();
    public static final AtomicLong SABLE_TICK_CALLS = new AtomicLong();
    public static final AtomicLong IDLE_STEP_SKIPS = new AtomicLong();
    public static final AtomicLong REDUCED_SUBSTEP_TICKS = new AtomicLong();
    public static final AtomicLong DORMANT_WORK_SKIPS = new AtomicLong();
    public static final AtomicLong PARKS = new AtomicLong();
    public static final AtomicLong WAKES = new AtomicLong();
    public static final AtomicLong PARKED_DRIFT_FIXES = new AtomicLong();
    public static final AtomicLong COLLIDER_UPDATES_DEFERRED = new AtomicLong();
    public static final AtomicLong COLLIDER_UPDATES_REPLAYED = new AtomicLong();
    // Phase 7
    public static final AtomicLong HULL_AABB_APPLIES = new AtomicLong();
    public static final AtomicLong HULL_AABB_RESTORES = new AtomicLong();
    public static final AtomicLong MASS_CACHE_SKIPS = new AtomicLong();
    public static final AtomicLong DISTANT_CHEAP_STEPS = new AtomicLong();
    public static final AtomicLong NETWORK_THROTTLES = new AtomicLong();
    public static final AtomicLong BLOCK_COLLIDER_SKIPS = new AtomicLong();
    // Phase 8
    public static final AtomicLong COAST_SKIPS = new AtomicLong();
    public static final AtomicLong COAST_INTEGRATIONS = new AtomicLong();
    public static final AtomicLong SOLVER_LOD_TICKS = new AtomicLong();

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
        SABLE_TICK_NS.set(0);
        SABLE_TICK_CALLS.set(0);
        IDLE_STEP_SKIPS.set(0);
        REDUCED_SUBSTEP_TICKS.set(0);
        DORMANT_WORK_SKIPS.set(0);
        PARKS.set(0);
        WAKES.set(0);
        PARKED_DRIFT_FIXES.set(0);
        COLLIDER_UPDATES_DEFERRED.set(0);
        COLLIDER_UPDATES_REPLAYED.set(0);
        HULL_AABB_APPLIES.set(0);
        HULL_AABB_RESTORES.set(0);
        MASS_CACHE_SKIPS.set(0);
        DISTANT_CHEAP_STEPS.set(0);
        NETWORK_THROTTLES.set(0);
        BLOCK_COLLIDER_SKIPS.set(0);
        COAST_SKIPS.set(0);
        COAST_INTEGRATIONS.set(0);
        SOLVER_LOD_TICKS.set(0);
    }

    public static String phase6Summary() {
        long sc = SABLE_TICK_CALLS.get();
        double sableUs = sc == 0 ? 0.0 : (SABLE_TICK_NS.get() / 1000.0) / sc;
        return String.format(
                "sableTickCalls=%d sableTickAvgUs=%.1f idleSkips=%d reducedSubstepTicks=%d dormantSkips=%d parks=%d wakes=%d driftFixes=%d colliderDeferred=%d colliderReplayed=%d",
                sc, sableUs, IDLE_STEP_SKIPS.get(), REDUCED_SUBSTEP_TICKS.get(), DORMANT_WORK_SKIPS.get(),
                PARKS.get(), WAKES.get(), PARKED_DRIFT_FIXES.get(), COLLIDER_UPDATES_DEFERRED.get(), COLLIDER_UPDATES_REPLAYED.get())
                + String.format(" | p7 aabbOn=%d aabbOff=%d massSkip=%d distantCheap=%d netThrottle=%d blockSkip=%d",
                HULL_AABB_APPLIES.get(), HULL_AABB_RESTORES.get(), MASS_CACHE_SKIPS.get(),
                DISTANT_CHEAP_STEPS.get(), NETWORK_THROTTLES.get(), BLOCK_COLLIDER_SKIPS.get())
                + String.format(" | p8 coastSkips=%d coastIntegrations=%d solverLod=%d",
                COAST_SKIPS.get(), COAST_INTEGRATIONS.get(), SOLVER_LOD_TICKS.get());
    }

    public static String summary() {
        long calls = RAPIER_PHYSICS_TICK_CALLS.get();
        double avgUs = calls == 0 ? 0.0 : (RAPIER_PHYSICS_TICK_NS.get() / 1000.0) / calls;
        return String.format(
                "rapierSteps=%d cheapSteps=%d skippedBodies=%d promotions=%d demotions=%d explosions=%d splits=%d colliderOff=%d colliderOn=%d physTickCalls=%d physTickAvgUs=%.1f",
                RAPIER_STEPS.get(), CHEAP_STEPS.get(), RAPIER_BODIES_SKIPPED.get(),
                MODE_PROMOTIONS.get(), MODE_DEMOTIONS.get(), EXPLOSIONS.get(), SPLITS.get(),
                COLLIDER_DISABLES.get(), COLLIDER_RESTORES.get(), calls, avgUs) + " " + phase6Summary();
    }

    private HullProfiler() {}
}
