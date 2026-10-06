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

    private HullConfig() {}
}
