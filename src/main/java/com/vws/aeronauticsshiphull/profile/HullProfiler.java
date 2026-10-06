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

    public static void reset() {
        RAPIER_STEPS.set(0);
        CHEAP_STEPS.set(0);
        RAPIER_BODIES_SKIPPED.set(0);
        MODE_PROMOTIONS.set(0);
        MODE_DEMOTIONS.set(0);
        EXPLOSIONS.set(0);
        SPLITS.set(0);
    }

    public static String summary() {
        return String.format(
                "rapierSteps=%d cheapSteps=%d skippedBodies=%d promotions=%d demotions=%d explosions=%d splits=%d",
                RAPIER_STEPS.get(), CHEAP_STEPS.get(), RAPIER_BODIES_SKIPPED.get(),
                MODE_PROMOTIONS.get(), MODE_DEMOTIONS.get(), EXPLOSIONS.get(), SPLITS.get());
    }

    private HullProfiler() {}
}
