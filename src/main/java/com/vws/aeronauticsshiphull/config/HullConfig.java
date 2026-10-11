package com.vws.aeronauticsshiphull.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class HullConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.IntValue CHEAP_PERIOD_TICKS;
    private static final ModConfigSpec.IntValue QUIET_TICKS_BEFORE_CHEAP;
    private static final ModConfigSpec.IntValue REBUILD_WINDOW_TICKS;
    private static final ModConfigSpec.DoubleValue COLLISION_LOOKAHEAD_BLOCKS;
    private static final ModConfigSpec.DoubleValue GRAVITY;
    private static final ModConfigSpec.BooleanValue PILOTED_USES_RAPIER;
    private static final ModConfigSpec.BooleanValue PROFILE;
    private static final ModConfigSpec.BooleanValue CHEAP_SOFT_SLEEP;
    // Phase 6
    private static final ModConfigSpec.BooleanValue PARK_ENABLED;
    private static final ModConfigSpec.IntValue PARK_STILL_TICKS;
    private static final ModConfigSpec.DoubleValue PARK_MAX_SPEED;
    private static final ModConfigSpec.DoubleValue WAKE_RADIUS;
    private static final ModConfigSpec.BooleanValue IDLE_SCENE_SKIP;
    private static final ModConfigSpec.IntValue IDLE_FULL_STEP_EVERY;
    private static final ModConfigSpec.BooleanValue DISTANT_SUBSTEPS;
    private static final ModConfigSpec.DoubleValue DISTANT_RADIUS;
    private static final ModConfigSpec.BooleanValue SKIP_DORMANT_SUBSTEP_WORK;
    private static final ModConfigSpec.BooleanValue BATCH_COLLIDER_UPDATES;
    private static final ModConfigSpec.IntValue WAKE_BLOCK_BURST;

    // Phase 7 — in-motion cost
    private static final ModConfigSpec.BooleanValue PHASE7_ENABLED;
    private static final ModConfigSpec.BooleanValue SIMPLIFIED_HULL_COLLIDERS;
    private static final ModConfigSpec.BooleanValue CACHE_MASS;
    private static final ModConfigSpec.BooleanValue SKIP_BLOCK_COLLIDER_WHILE_MOVING;
    private static final ModConfigSpec.BooleanValue DISTANT_CHEAP_SLOWDOWN;
    private static final ModConfigSpec.IntValue DISTANT_CHEAP_PERIOD;
    private static final ModConfigSpec.BooleanValue THROTTLE_NETWORK;
    private static final ModConfigSpec.IntValue NETWORK_PERIOD_TICKS;
    private static final ModConfigSpec.DoubleValue SIMPLIFIED_RADIUS;

    // Phase 8 — Rapier step LOD
    private static final ModConfigSpec.BooleanValue PHASE8_ENABLED;
    private static final ModConfigSpec.BooleanValue COAST_SEPARATED;
    private static final ModConfigSpec.IntValue COAST_REAL_STEP_EVERY;
    private static final ModConfigSpec.BooleanValue DISTANT_SOLVER;
    private static final ModConfigSpec.IntValue DISTANT_SOLVER_ITERATIONS;
    private static final ModConfigSpec.BooleanValue COLLIDER_CACHE;
    private static final ModConfigSpec.IntValue COLLIDER_SECTION_REPLAY_AT;
    private static final ModConfigSpec.BooleanValue CONTRAPTION_TICK;
    private static final ModConfigSpec.IntValue CONTRAPTION_ENTITY_PERIOD;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("ship_hull");
        ENABLED = b.comment("Master switch for hull cheap-physics diversion").define("enabled", true);
        CHEAP_PERIOD_TICKS = b.comment("Ticks between CHEAP integrator steps (2=10Hz, 4=5Hz)")
                .defineInRange("cheapPeriodTicks", 2, 1, 20);
        QUIET_TICKS_BEFORE_CHEAP = b.comment("Full-Rapier quiet ticks before demoting unpiloted ship to CHEAP")
                .defineInRange("quietTicksBeforeCheap", 40, 0, 1200);
        REBUILD_WINDOW_TICKS = b.comment("Full Rapier ticks after explosion/rebuild")
                .defineInRange("rebuildWindowTicks", 60, 1, 1200);
        COLLISION_LOOKAHEAD_BLOCKS = b.comment("Promote to FULL when hull is this close to solid world/other ships")
                .defineInRange("collisionLookaheadBlocks", 4.0, 0.5, 32.0);
        GRAVITY = b.comment("CHEAP-mode gravity (blocks/s^2); 0 = no gravity (airships)")
                .defineInRange("cheapGravity", 0.0, -40.0, 40.0);
        PILOTED_USES_RAPIER = b.comment("Piloted ships stay on full Rapier (still one rigid body, never per-block)")
                .define("pilotedUsesRapier", true);
        PROFILE = b.comment("Count cheap vs rapier steps for /shiphull profile").define("profile", true);
        CHEAP_SOFT_SLEEP = b.comment(
                "Phase 5: on CHEAP, empty Rapier local bounds (disable colliders) instead of pipeline.remove "
                + "(remove panics natives). Body stays in the scene; CheapIntegrator owns pose. "
                + "Restored via onStatsChanged on FULL/REBUILDING.")
                .define("cheapSoftSleep", true);
        b.push("phase6");
        PARK_ENABLED = b.comment("Park unpiloted ships that have been at rest (on terrain or hovering) with no player near: "
                + "pose frozen, colliders off, skipped by Sable's substep loop. Wakes on approach, impulse, nearby moving ship, block burst.")
                .define("parkEnabled", true);
        PARK_STILL_TICKS = b.comment("Consecutive still ticks before a FULL/CHEAP ship parks")
                .defineInRange("parkStillTicks", 60, 1, 6000);
        PARK_MAX_SPEED = b.comment("Max linear speed (blocks/s) still counted as 'at rest'; angular uses speed/4 rad/s")
                .defineInRange("parkMaxSpeed", 0.05, 0.0, 5.0);
        WAKE_RADIUS = b.comment("A player within this many blocks of a parked/unpiloted hull wakes it to FULL")
                .defineInRange("wakeRadius", 16.0, 0.0, 256.0);
        IDLE_SCENE_SKIP = b.comment("Skip Sable's whole physics substep loop (incl. Rapier step) when every ship is CHEAP/PARKED and no boxes/ropes exist")
                .define("idleSceneSkip", true);
        IDLE_FULL_STEP_EVERY = b.comment("While the scene is idle, still run one real physics tick every N ticks (housekeeping/safety)")
                .defineInRange("idleFullStepEvery", 40, 1, 1200);
        DISTANT_SUBSTEPS = b.comment("Run 1 substep instead of Sable's configured count when no Rapier ship is piloted or near a player")
                .define("distantSubsteps", true);
        DISTANT_RADIUS = b.comment("'Near a player' radius for distantSubsteps (blocks from hull)")
                .defineInRange("distantRadius", 64.0, 0.0, 1024.0);
        SKIP_DORMANT_SUBSTEP_WORK = b.comment("Skip prePhysicsTickBegin / mass merge / pose readback for CHEAP+PARKED ships inside Sable's substep loop")
                .define("skipDormantSubstepWork", true);
        BATCH_COLLIDER_UPDATES = b.comment("Defer Rapier voxel collider updates for ships with colliders off; replay de-duplicated on wake")
                .define("batchColliderUpdates", true);
        WAKE_BLOCK_BURST = b.comment("Wake a parked ship if this many of its blocks change in one tick (explosion-like)")
                .defineInRange("wakeBlockBurst", 8, 1, 4096);
        b.pop();

        b.push("phase7");
        PHASE7_ENABLED = b.comment("Master switch for Phase 7 in-motion cuts (A/B: /shiphull phase7 off|on)")
                .define("enabled", true);
        SIMPLIFIED_HULL_COLLIDERS = b.comment("Unpiloted ships farther than simplifiedRadius use one AABB Rapier collider (setLocalBounds to hull) "
                + "instead of per-block voxels. Restored on pilot / near player / rebuild.")
                .define("simplifiedHullColliders", true);
        SIMPLIFIED_RADIUS = b.comment("Within this radius of a player, keep full voxel colliders")
                .defineInRange("simplifiedRadius", 48.0, 0.0, 512.0);
        CACHE_MASS = b.comment("Skip Sable updateMergedMassData for unpiloted CHEAP/simplified ships (mass frozen until wake/rebuild)")
                .define("cacheMass", true);
        SKIP_BLOCK_COLLIDER_WHILE_MOVING = b.comment("While simplified/CHEAP soft-sleep, defer per-block Rapier collider updates (rigid hull)")
                .define("skipBlockColliderWhileMoving", true);
        DISTANT_CHEAP_SLOWDOWN = b.comment("Distant unpiloted CHEAP ships integrate less often (interpolation via teleport each sync still applies)")
                .define("distantCheapSlowdown", true);
        DISTANT_CHEAP_PERIOD = b.comment("CHEAP period ticks when no player within distantRadius (higher = fewer integrator steps)")
                .defineInRange("distantCheapPeriodTicks", 4, 1, 20);
        THROTTLE_NETWORK = b.comment("Mark far unpiloted ships as network-stopped most ticks (Sable lastNetworkedStopped) to cut pose sync")
                .define("throttleNetwork", true);
        NETWORK_PERIOD_TICKS = b.comment("Send a real network pose every N ticks for throttled ships")
                .defineInRange("networkPeriodTicks", 10, 1, 200);
        b.pop();

        b.push("phase8");
        PHASE8_ENABLED = b.comment("Master switch for Phase 8 Rapier step cuts (A/B: /shiphull phase8 off|on)")
                .define("enabled", true);
        COAST_SEPARATED = b.comment("Unpiloted FULL ships whose lookahead is clear of terrain and other hulls skip Rapier3D.step "
                + "and coast at the last solved velocity. A real step still runs every coastRealStepEvery ticks, and any "
                + "piloted, rebuilding, or about-to-collide ship forces a real step for the whole scene.")
                .define("coastSeparated", true);
        COAST_REAL_STEP_EVERY = b.comment("Run a real Rapier step every N ticks while coasting (1 = never coast)")
                .defineInRange("coastRealStepEvery", 4, 1, 20);
        DISTANT_SOLVER = b.comment("When a real step still runs but no Rapier ship is piloted or near a player, "
                + "lower solver iterations (contact generation stays). Restored when someone is close.")
                .define("distantSolver", true);
        DISTANT_SOLVER_ITERATIONS = b.comment("Solver iterations used by distantSolver (Sable default is 18)")
                .defineInRange("distantSolverIterations", 8, 1, 64);
        b.pop();
        b.push("colliderCache");
        COLLIDER_CACHE = b.comment("Incremental hull bounds, cached block classification, and section-batched collider replay")
                .define("enabled", true);
        COLLIDER_SECTION_REPLAY_AT = b.comment("When at least this many deferred collider cells wake at once, replay each dirty 16³ section "
                + "with one chunk upload instead of a native changeBlock per cell")
                .defineInRange("sectionReplayAt", 48, 1, 4096);
        b.pop();
        b.push("contraptionTick");
        CONTRAPTION_TICK = b.comment("Skip kinematic contraption pose uploads and heat maps on parked/settled ships, "
                + "and tick contraption entities on unattended parked ships every contraptionEntityPeriod ticks")
                .define("enabled", true);
        CONTRAPTION_ENTITY_PERIOD = b.comment("Contraption entity tick period on a parked ship with no player nearby (1 = every tick)")
                .defineInRange("contraptionEntityPeriod", 4, 1, 20);
        b.pop();
        b.pop();
        SPEC = b.build();
    }

    public static boolean enabled() { return ENABLED.get(); }
    public static int cheapPeriodTicks() { return CHEAP_PERIOD_TICKS.get(); }
    public static int quietTicksBeforeCheap() { return QUIET_TICKS_BEFORE_CHEAP.get(); }
    public static int rebuildWindowTicks() { return REBUILD_WINDOW_TICKS.get(); }
    public static double collisionLookahead() { return COLLISION_LOOKAHEAD_BLOCKS.get(); }
    public static double gravity() { return GRAVITY.get(); }
    public static boolean pilotedUsesRapier() { return PILOTED_USES_RAPIER.get(); }
    public static boolean profile() { return PROFILE.get(); }
    public static boolean cheapSoftSleep() { return CHEAP_SOFT_SLEEP.get(); }
    public static boolean parkEnabled() { return PARK_ENABLED.get(); }
    public static int parkStillTicks() { return PARK_STILL_TICKS.get(); }
    public static double parkMaxSpeed() { return PARK_MAX_SPEED.get(); }
    public static double wakeRadius() { return WAKE_RADIUS.get(); }
    public static boolean idleSceneSkip() { return IDLE_SCENE_SKIP.get(); }
    public static int idleFullStepEvery() { return IDLE_FULL_STEP_EVERY.get(); }
    public static boolean distantSubsteps() { return DISTANT_SUBSTEPS.get(); }
    public static double distantRadius() { return DISTANT_RADIUS.get(); }
    public static boolean skipDormantSubstepWork() { return SKIP_DORMANT_SUBSTEP_WORK.get(); }
    public static boolean batchColliderUpdates() { return BATCH_COLLIDER_UPDATES.get(); }
    public static int wakeBlockBurst() { return WAKE_BLOCK_BURST.get(); }

    public static boolean phase7() { return PHASE7_ENABLED.get(); }
    public static boolean simplifiedHullColliders() { return SIMPLIFIED_HULL_COLLIDERS.get(); }
    public static double simplifiedRadius() { return SIMPLIFIED_RADIUS.get(); }
    public static boolean cacheMass() { return CACHE_MASS.get(); }
    public static boolean skipBlockColliderWhileMoving() { return SKIP_BLOCK_COLLIDER_WHILE_MOVING.get(); }
    public static boolean distantCheapSlowdown() { return DISTANT_CHEAP_SLOWDOWN.get(); }
    public static int distantCheapPeriodTicks() { return DISTANT_CHEAP_PERIOD.get(); }
    public static boolean throttleNetwork() { return THROTTLE_NETWORK.get(); }
    public static int networkPeriodTicks() { return NETWORK_PERIOD_TICKS.get(); }

    public static boolean phase8() { return PHASE8_ENABLED.get(); }
    public static boolean coastSeparated() { return COAST_SEPARATED.get(); }
    public static int coastRealStepEvery() { return COAST_REAL_STEP_EVERY.get(); }
    public static boolean distantSolver() { return DISTANT_SOLVER.get(); }
    public static int distantSolverIterations() { return DISTANT_SOLVER_ITERATIONS.get(); }
    public static boolean colliderCache() { return COLLIDER_CACHE.get(); }
    public static int colliderSectionReplayAt() { return COLLIDER_SECTION_REPLAY_AT.get(); }
    public static boolean contraptionTick() { return CONTRAPTION_TICK.get(); }
    public static int contraptionEntityPeriod() { return CONTRAPTION_ENTITY_PERIOD.get(); }

    private HullConfig() {}
}
