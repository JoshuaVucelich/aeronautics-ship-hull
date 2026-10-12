package com.vws.shiphullbench;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Dedicated-server probe. Spawn order is a parked fleet, then one overlapping moving
 * cluster (kept in a single group so unpiloted ships stay on Rapier), then one large
 * platform with three touching grabbers (the airship incident shape).
 * Enabled only with {@code -Dshiphull.bench=true}.
 */
@Mod("shiphull_bench")
public class ShipHullBench {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DONE = "SHIPHULL_BENCH_DONE";

    private static final int PARK_SHIPS = Integer.getInteger("shiphull.bench.parkShips", 8);
    private static final int PARK_SIZE = Integer.getInteger("shiphull.bench.parkSize", 12);
    private static final int MOVE_SHIPS = Integer.getInteger("shiphull.bench.moveShips", 4);
    private static final int MOVE_SIZE = Integer.getInteger("shiphull.bench.moveSize", 12);
    private static final int WARMUP = Integer.getInteger("shiphull.bench.warmup", 240);
    private static final int PARK_TICKS = Integer.getInteger("shiphull.bench.parkTicks", 400);
    private static final int MOVE_TICKS = Integer.getInteger("shiphull.bench.moveTicks", 200);
    /**
     * Half-extent 12 platforms occupy 25 blocks. A center spacing of 27 leaves about two
     * blocks of air, inside the 3-block group link, without the overlap that deletes the
     * sub-levels on the first physics step.
     */
    private static final int MOVE_SPACING = Integer.getInteger("shiphull.bench.moveSpacing", 27);
    private static final int MOVE_Y = Integer.getInteger("shiphull.bench.moveY", 600);
    private static final int GROUP_TICKS = Integer.getInteger("shiphull.bench.groupTicks", 300);
    private static final double MOVE_SPEED = Double.parseDouble(System.getProperty("shiphull.bench.moveSpeed", "8"));

    private enum Phase {
        BOOT, SPAWN_PARKED, WARMUP_PARKED, MEASURE_PARKED,
        SPAWN_MOVING, MEASURE_MOVING, SPAWN_GROUP, MEASURE_GROUP, DONE
    }

    private final boolean enabled = Boolean.getBoolean("shiphull.bench");
    private Phase phase = Phase.BOOT;
    private int wait;
    private long tickStart;
    private boolean measuring;
    private int measured;
    private int netTick;
    private boolean globalUpdating;
    private ServerLevel level;
    private final List<Ship> parked = new ArrayList<>();
    private final List<Ship> moving = new ArrayList<>();
    private final List<Ship> group = new ArrayList<>();
    private final Scenario parkedStats = new Scenario("parked");
    private final Scenario movingStats = new Scenario("moving");
    private final Scenario groupStats = new Scenario("group");
    private Scenario active;
    private SableClientModel clock = new SableClientModel();
    private final JsonObject result = new JsonObject();
    private String failure;

    private Method hullManagerGet;
    private Method hullGet;
    private Method throttlePose;

    public ShipHullBench() {
        if (!enabled) {
            LOGGER.info("[shiphull-bench] inactive (set -Dshiphull.bench=true to run)");
            return;
        }
        try {
            Class<?> mgr = Class.forName("com.vws.aeronauticsshiphull.hull.ShipHullManager");
            hullManagerGet = mgr.getMethod("getOrNull", Level.class);
            hullGet = mgr.getMethod("get", UUID.class);
            throttlePose = mgr.getMethod("throttlePoseSend", ServerSubLevel.class);
            LOGGER.info("[shiphull-bench] ship hull mod is on the classpath");
        } catch (Throwable ignored) {
            LOGGER.info("[shiphull-bench] ship hull mod is not loaded; this run is the baseline");
        }
        NeoForge.EVENT_BUS.addListener(this::onServerPre);
        NeoForge.EVENT_BUS.addListener(this::onServerPost);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, this::beforeHullEndTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, this::afterHullEndTick);
    }

    private void onServerPre(ServerTickEvent.Pre event) {
        tickStart = System.nanoTime();
        measuring = phase == Phase.MEASURE_PARKED || phase == Phase.MEASURE_MOVING || phase == Phase.MEASURE_GROUP;
    }

    private void onServerPost(ServerTickEvent.Post event) {
        if (phase == Phase.DONE) return;
        MinecraftServer server = event.getServer();
        if (level == null) level = server.overworld();
        double ms = (System.nanoTime() - tickStart) / 1_000_000.0;
        try {
            switch (phase) {
                case BOOT -> boot(server);
                case SPAWN_PARKED -> {
                    spawnFleet(parked, "park", PARK_SHIPS, PARK_SIZE, 0, 80, 0, 80, false);
                    phase = Phase.WARMUP_PARKED;
                    wait = WARMUP;
                    LOGGER.info("[shiphull-bench] spawned {} parked-fleet ships, warming {} ticks", parked.size(), WARMUP);
                }
                case WARMUP_PARKED -> {
                    if (--wait <= 0) beginMeasure(parkedStats, parked);
                }
                case MEASURE_PARKED -> {
                    parkedStats.mspt.add(ms);
                    if (++measured >= PARK_TICKS) {
                        finishScenario(parkedStats, parked);
                        phase = Phase.SPAWN_MOVING;
                    }
                }
                case SPAWN_MOVING -> {
                    spawnMovingCluster();
                    beginMeasure(movingStats, moving);
                    LOGGER.info("[shiphull-bench] spawned {} grouped moving ships at y={}", moving.size(), MOVE_Y);
                }
                case MEASURE_MOVING -> {
                    movingStats.mspt.add(ms);
                    if (++measured >= MOVE_TICKS) {
                        finishScenario(movingStats, moving);
                        phase = Phase.SPAWN_GROUP;
                    }
                }
                case SPAWN_GROUP -> {
                    spawnGroup();
                    beginMeasure(groupStats, group);
                    LOGGER.info("[shiphull-bench] spawned airship plus {} grabbers", group.size() - 1);
                }
                case MEASURE_GROUP -> {
                    groupStats.mspt.add(ms);
                    if (++measured >= GROUP_TICKS) {
                        finishScenario(groupStats, group);
                        finish(server);
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            failure = t.toString();
            LOGGER.error("[shiphull-bench] failed in {}", phase, t);
            finish(server);
        }
    }

    /** After Sable's tracking pass, before this mod's LevelTickEvent.Post endTick. */
    private void beforeHullEndTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel sl) || sl != level) return;
        if (phase == Phase.DONE) return;
        List<Ship> live = currentShips();
        if (live == null) {
            netTick++;
            return;
        }
        boolean anyMove = false;
        for (Ship ship : live) {
            if (ship.removed) continue;
            if (ship.sub.isRemoved()) {
                if (!ship.removed) logRemoval(ship, netTick);
                ship.removed = true;
                ship.removedAtTick = netTick;
                continue;
            }
            Packet packet = classify(ship);
            if (measuring) noteNetworkGap(ship);
            if (packet == Packet.MOVE) {
                ship.client.receiveMove(netTick, ship.sentX, ship.sentY, ship.sentZ);
                anyMove = true;
            } else if (packet == Packet.STOP) {
                ship.client.receiveStop();
            }
        }
        if (anyMove) {
            clock.receiveMoves(netTick, 50);
            globalUpdating = true;
        } else if (globalUpdating) {
            clock.receiveGlobalStop(netTick, 50);
            globalUpdating = false;
        }
        clock.tickClock();
        double pointer = clock.pointer();
        for (Ship ship : live) {
            if (ship.removed) continue;
            ship.client.sample(pointer);
        }
        netTick++;
    }

    /** Server pose after coast, cheap integration, and park pins. */
    private void afterHullEndTick(LevelTickEvent.Post event) {
        if (!measuring || !(event.getLevel() instanceof ServerLevel sl) || sl != level || active == null) return;
        for (Ship ship : active.ships) {
            if (ship.removed || ship.sub.isRemoved()) {
                ship.removed = true;
                continue;
            }
            Vector3d pos = ship.sub.logicalPose().position();
            double x = pos.x();
            double y = pos.y();
            double z = pos.z();
            ship.minY = Math.min(ship.minY, y);
            ship.maxY = Math.max(ship.maxY, y);
            double speed = ship.sub.latestLinearVelocity.length();
            ship.maxSpeed = Math.max(ship.maxSpeed, speed);
            ship.track(x, y, z);
            if (!ship.client.ready) continue;
            double[] server = ship.pushServer(x, y, z);
            double[] client = ship.pushClient(ship.client.x, ship.client.y, ship.client.z);
            if (server == null || client == null) continue;
            active.desync.add(BenchStats.distance(client, server));
            double[] error = ship.pushError(client[0] - server[0], client[1] - server[1], client[2] - server[2]);
            if (ship.serverHistory() >= 3 && ship.clientHistory() >= 3) {
                active.serverJitter.add(BenchStats.secondDifference(ship.s0, ship.s1, ship.s2));
                active.clientJitter.add(BenchStats.secondDifference(ship.c0, ship.c1, ship.c2));
            }
            if (ship.errorHistory() >= 3 && error != null) {
                active.errorJitter.add(BenchStats.secondDifference(ship.e0, ship.e1, ship.e2));
            }
        }
    }

    private void boot(MinecraftServer server) {
        level = server.overworld();
        if (wait++ < 40) return;
        GameRules rules = level.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || container.physicsSystem() == null || container.physicsSystem().getPipeline() == null) {
            if (wait > 200) throw new IllegalStateException("Sable physics pipeline was not ready");
            return;
        }
        phase = Phase.SPAWN_PARKED;
        LOGGER.info("[shiphull-bench] pipeline ready, mods {}", modVersions());
    }

    private void beginMeasure(Scenario scenario, List<Ship> ships) {
        scenario.ships = ships;
        scenario.profilerAtStart = profilerSnapshot();
        clock = new SableClientModel();
        netTick = 0;
        globalUpdating = true;
        measured = 0;
        for (Ship ship : ships) {
            ship.resetForWindow();
            Vector3d pos = ship.sub.logicalPose().position();
            ship.client.receiveMove(0, pos.x(), pos.y(), pos.z());
            Vector3d net = ship.sub.lastNetworkedPose().position();
            ship.haveNet = true;
            ship.netX = net.x();
            ship.netY = net.y();
            ship.netZ = net.z();
            ship.netStopped = ship.sub.getLastNetworkedStopped();
        }
        // A player who starts tracking gets the current pose before any throttle gap.
        clock.receiveMoves(0, 50);
        clock.tickClock();
        for (Ship ship : ships) ship.client.sample(clock.pointer());
        netTick = 1;
        active = scenario;
        phase = scenario == parkedStats ? Phase.MEASURE_PARKED
                : scenario == movingStats ? Phase.MEASURE_MOVING : Phase.MEASURE_GROUP;
        LOGGER.info("[shiphull-bench] measuring {}", scenario.name);
    }

    private void finishScenario(Scenario scenario, List<Ship> ships) {
        scenario.profilerAtEnd = profilerSnapshot();
        scenario.modes = modeHistogram(ships);
        active = null;
        measuring = false;
    }

    private List<Ship> currentShips() {
        return switch (phase) {
            case WARMUP_PARKED, MEASURE_PARKED -> parked;
            case MEASURE_MOVING -> moving;
            case MEASURE_GROUP -> group;
            default -> null;
        };
    }

    private Packet classify(Ship ship) {
        ship.lastThrottled = throttled(ship.sub);
        boolean throttled = ship.lastThrottled;
        Vector3d net = ship.sub.lastNetworkedPose().position();
        double x = net.x();
        double y = net.y();
        double z = net.z();
        boolean stopped = ship.sub.getLastNetworkedStopped();
        Packet packet = Packet.NONE;
        if (!throttled && ship.haveNet) {
            double dx = x - ship.netX;
            double dy = y - ship.netY;
            double dz = z - ship.netZ;
            if (dx * dx + dy * dy + dz * dz > 1.0e-12) {
                packet = Packet.MOVE;
                ship.sentX = x;
                ship.sentY = y;
                ship.sentZ = z;
            } else if (stopped && !ship.netStopped) {
                packet = Packet.STOP;
            }
        }
        ship.haveNet = true;
        ship.netX = x;
        ship.netY = y;
        ship.netZ = z;
        ship.netStopped = stopped;
        if (packet == Packet.MOVE) ship.moves++;
        if (packet == Packet.STOP) ship.stops++;
        return packet;
    }

    private void logRemoval(Ship ship, int tick) {
        String pose = "unreadable";
        String box = "unreadable";
        try {
            Vector3d pos = ship.sub.logicalPose().position();
            pose = String.format(Locale.ROOT, "%.3f,%.3f,%.3f", pos.x(), pos.y(), pos.z());
        } catch (Throwable ignored) {
        }
        try {
            var bounds = ship.sub.boundingBox();
            box = String.format(Locale.ROOT, "y %.3f..%.3f", bounds.minY(), bounds.maxY());
        } catch (Throwable ignored) {
        }
        LOGGER.warn("[shiphull-bench] {} removed at netTick {} pose {} box {}", ship.name, tick, pose, box);
    }

    /** Logical pose versus the pose tracking last published, split by throttle pulse. */
    private void noteNetworkGap(Ship ship) {
        Vector3d logical = ship.sub.logicalPose().position();
        double dx = logical.x() - ship.netX;
        double dy = logical.y() - ship.netY;
        double dz = logical.z() - ship.netZ;
        double gap = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(gap)) return;
        if (ship.lastThrottled) {
            ship.throttledTicks++;
            if (gap > ship.maxThrottledGap) ship.maxThrottledGap = gap;
        } else {
            ship.pulseTicks++;
            if (gap > ship.maxPulseGap) ship.maxPulseGap = gap;
        }
    }

    private boolean throttled(ServerSubLevel sub) {
        if (throttlePose == null || hullManagerGet == null) return false;
        try {
            Object mgr = hullManagerGet.invoke(null, level);
            if (mgr == null) return false;
            return Boolean.TRUE.equals(throttlePose.invoke(mgr, sub));
        } catch (Throwable t) {
            return false;
        }
    }

    private void spawnFleet(List<Ship> into, String prefix, int count, int size, int x0, int y, int z0, int spacing, boolean kick) {
        for (int i = 0; i < count; i++) {
            int x = x0 + i * spacing;
            layPad(x, z0, size + 2, y - 1);
            ServerSubLevel sub = spawnPlatform(prefix + "-" + i, x + 0.5, y, z0 + 0.5, size);
            Ship ship = new Ship(prefix + "-" + i, sub);
            into.add(ship);
            if (kick) kick(ship);
        }
    }

    /**
     * One cluster, centers {@link #MOVE_SPACING} apart so the hulls stay one group.
     * A stone pad far below only force-loads the chunk columns. The bodies start high
     * enough that the measured window is airborne.
     */
    private void spawnMovingCluster() {
        int z = 0;
        int span = MOVE_SHIPS * MOVE_SPACING + 96;
        layPad(10_000 + span / 2, z, span / 2, 40);
        for (int i = 0; i < MOVE_SHIPS; i++) {
            double x = 10_000.5 + i * MOVE_SPACING;
            ServerSubLevel sub = spawnPlatform("move-" + i, x, MOVE_Y, z + 0.5, MOVE_SIZE);
            Ship ship = new Ship("move-" + i, sub);
            moving.add(ship);
            kick(ship);
        }
    }

    private void spawnGroup() {
        int x = 20_000;
        int z = 0;
        int y = 80;
        layPad(x, z, 20, y - 1);
        group.add(new Ship("airship", spawnPlatform("airship", x + 0.5, y, z + 0.5, 12)));
        group.add(new Ship("grabber-1", spawnPlatform("grabber-1", x + 12.5, y, z + 0.5, 2)));
        group.add(new Ship("grabber-2", spawnPlatform("grabber-2", x + 12.5, y, z + 4.5, 2)));
        group.add(new Ship("grabber-3", spawnPlatform("grabber-3", x + 12.5, y + 1.0, z + 0.5, 2)));
    }

    private void layPad(int cx, int cz, int radius, int y) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState stone = Blocks.STONE.defaultBlockState();
        int x0 = cx - radius;
        int x1 = cx + radius;
        int z0 = cz - radius;
        int z1 = cz + radius;
        for (int cxChunk = x0 >> 4; cxChunk <= x1 >> 4; cxChunk++) {
            for (int czChunk = z0 >> 4; czChunk <= z1 >> 4; czChunk++) {
                level.getChunkSource().getChunk(cxChunk, czChunk, true);
                level.setChunkForced(cxChunk, czChunk, true);
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                pos.set(x, y, z);
                level.setBlock(pos, stone, 2);
            }
        }
    }

    private ServerSubLevel spawnPlatform(String name, double x, double y, double z, int size) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        Pose3d pose = new Pose3d();
        pose.position().set(x, y, z);
        ServerSubLevel sub = (ServerSubLevel) container.allocateNewSubLevel(pose);
        sub.setName(name);
        LevelPlot plot = sub.getPlot();
        ChunkPos center = plot.getCenterChunk();
        int radiusChunks = (size + 8) / 16;
        for (int cx = -radiusChunks; cx <= radiusChunks; cx++) {
            for (int cz = -radiusChunks; cz <= radiusChunks; cz++) {
                plot.newEmptyChunk(new ChunkPos(center.x + cx, center.z + cz));
            }
        }
        BlockState oak = Blocks.OAK_PLANKS.defaultBlockState();
        var accessor = plot.getEmbeddedLevelAccessor();
        for (int bx = -size; bx <= size; bx++) {
            for (int bz = -size; bz <= size; bz++) {
                accessor.setBlock(new BlockPos(bx, 0, bz), oak, 2);
            }
        }
        sub.updateLastPose();
        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        pipeline.teleport(sub, new Vector3d(x, y, z), new Quaterniond());
        return sub;
    }

    private void kick(Ship ship) {
        try {
            double mass = ship.sub.getSelfMassTracker().getMass();
            if (!Double.isFinite(mass) || mass <= 0.0) mass = 1.0;
            PhysicsPipeline pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
            pipeline.applyLinearAndAngularImpulse(ship.sub, new Vector3d(mass * MOVE_SPEED, mass * 1.5, 0.0), new Vector3d(), true);
            ship.kicked = true;
        } catch (Throwable t) {
            LOGGER.warn("[shiphull-bench] impulse {} failed: {}", ship.name, t.toString());
        }
    }

    private Map<String, String> modeHistogram(List<Ship> ships) {
        Map<String, String> modes = new LinkedHashMap<>();
        if (hullGet == null || hullManagerGet == null) return modes;
        try {
            Object mgr = hullManagerGet.invoke(null, level);
            if (mgr == null) return modes;
            for (Ship ship : ships) {
                Object hull = hullGet.invoke(mgr, ship.sub.getUniqueId());
                if (hull == null) {
                    modes.put(ship.name, "missing");
                    continue;
                }
                Object mode = hull.getClass().getField("mode").get(hull);
                modes.put(ship.name, String.valueOf(mode));
            }
        } catch (Throwable t) {
            modes.put("error", t.toString());
        }
        return modes;
    }

    private Map<String, Long> profilerSnapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            Class<?> profiler = Class.forName("com.vws.aeronauticsshiphull.profile.HullProfiler");
            for (String name : List.of(
                    "RAPIER_STEPS", "CHEAP_STEPS", "IDLE_STEP_SKIPS", "REDUCED_SUBSTEP_TICKS",
                    "PARKS", "WAKES", "GROUP_PARKS", "POSE_RESTORES", "COAST_SKIPS", "COAST_INTEGRATIONS",
                    "SOLVER_LOD_TICKS", "POSE_SEND_SKIPS", "NETWORK_THROTTLES", "RAPIER_PHYSICS_TICK_NS",
                    "RAPIER_PHYSICS_TICK_CALLS", "SABLE_TICK_NS", "SABLE_TICK_CALLS")) {
                Object atomic = profiler.getField(name).get(null);
                long value = (long) atomic.getClass().getMethod("get").invoke(atomic);
                out.put(name, value);
            }
        } catch (Throwable ignored) {
            // Baseline run has no profiler.
        }
        return out;
    }

    private JsonObject modVersions() {
        JsonObject mods = new JsonObject();
        mods.addProperty("neoforge", "21.1.233");
        mods.addProperty("minecraft", "1.21.1");
        mods.addProperty("create", modVersion("create"));
        mods.addProperty("sable", modVersion("sable"));
        mods.addProperty("aeronautics", modVersion("aeronautics"));
        mods.addProperty("simulated", modVersion("simulated"));
        mods.addProperty("sodium", ModList.get().isLoaded("sodium"));
        mods.addProperty("shiphull", modVersion("aeronautics_ship_hull"));
        return mods;
    }

    private static String modVersion(String id) {
        return ModList.get().getModContainerById(id).map(c -> c.getModInfo().getVersion().toString()).orElse("");
    }

    private void finish(MinecraftServer server) {
        if (phase == Phase.DONE) return;
        if (active != null) finishScenario(active, active.ships);
        phase = Phase.DONE;
        result.add("pack", modVersions());
        result.addProperty("sodiumLoaded", ModList.get().isLoaded("sodium"));
        result.addProperty("shiphullLoaded", hullManagerGet != null);
        result.addProperty("interpolationDelayTicks", SableClientModel.INTERPOLATION_DELAY);
        result.addProperty("packetDelivery", "same-tick (integrated). Desync is Sable's interpolator, not ping.");
        result.addProperty("jitter", "RMS of the second finite difference of position, blocks per tick squared");
        result.addProperty("errorJitter", "RMS of the second finite difference of (client - server), blocks per tick squared");
        result.addProperty("movingLayout", "platforms half-extent " + MOVE_SIZE + ", centers " + MOVE_SPACING
                + " apart at y=" + MOVE_Y + ", stone pad at y=40 so the chunk columns stay loaded, one shared impulse, "
                + MOVE_TICKS + " ticks");
        if (failure != null) result.addProperty("failure", failure);
        JsonObject scenarios = new JsonObject();
        scenarios.add("parked", parkedStats.toJson());
        scenarios.add("moving", movingStats.toJson());
        scenarios.add("group", groupStats.toJson());
        result.add("scenarios", scenarios);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
        Path out = Path.of(System.getProperty("shiphull.bench.out", "bench-result.json"));
        try {
            Files.writeString(out, json + "\n", StandardCharsets.UTF_8);
            LOGGER.info("[shiphull-bench] wrote {}", out.toAbsolutePath());
        } catch (Throwable t) {
            LOGGER.error("[shiphull-bench] could not write results", t);
        }
        LOGGER.info(DONE);
        server.halt(false);
    }

    private enum Packet { NONE, MOVE, STOP }

    private static final class Ship {
        final String name;
        final ServerSubLevel sub;
        final SableClientModel.Ship client = new SableClientModel.Ship();
        boolean haveNet;
        boolean netStopped;
        double netX, netY, netZ;
        double sentX, sentY, sentZ;
        int moves;
        int stops;
        boolean lastThrottled;
        int throttledTicks;
        int pulseTicks;
        double maxThrottledGap;
        double maxPulseGap;
        boolean removed;
        int removedAtTick = -1;
        boolean kicked;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxSpeed;
        boolean haveTrack;
        double ox, oy, oz;
        double px, py, pz;
        double ex, ey, ez;
        double path;
        double[] s0, s1, s2;
        double[] c0, c1, c2;
        double[] e0, e1, e2;
        int serverN;
        int clientN;
        int errorN;

        Ship(String name, ServerSubLevel sub) {
            this.name = name;
            this.sub = sub;
        }

        void resetForWindow() {
            client.clear();
            moves = 0;
            stops = 0;
            throttledTicks = 0;
            pulseTicks = 0;
            maxThrottledGap = 0;
            maxPulseGap = 0;
            haveTrack = false;
            path = 0;
            s0 = s1 = s2 = null;
            c0 = c1 = c2 = null;
            e0 = e1 = e2 = null;
            serverN = 0;
            clientN = 0;
            errorN = 0;
        }

        void track(double x, double y, double z) {
            if (!haveTrack) {
                ox = x;
                oy = y;
                oz = z;
                haveTrack = true;
            } else {
                double dx = x - px;
                double dy = y - py;
                double dz = z - pz;
                path += Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            px = x;
            py = y;
            pz = z;
            ex = x;
            ey = y;
            ez = z;
        }

        double travel() {
            if (!haveTrack) return 0;
            double dx = ex - ox;
            double dy = ey - oy;
            double dz = ez - oz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        double[] pushServer(double x, double y, double z) {
            s0 = s1;
            s1 = s2;
            s2 = new double[] {x, y, z};
            serverN++;
            return s2;
        }

        double[] pushClient(double x, double y, double z) {
            c0 = c1;
            c1 = c2;
            c2 = new double[] {x, y, z};
            clientN++;
            return c2;
        }

        double[] pushError(double x, double y, double z) {
            e0 = e1;
            e1 = e2;
            e2 = new double[] {x, y, z};
            errorN++;
            return e2;
        }

        int serverHistory() { return serverN; }
        int clientHistory() { return clientN; }
        int errorHistory() { return errorN; }
    }

    private static final class Scenario {
        final String name;
        List<Ship> ships = List.of();
        final BenchStats.Series mspt = new BenchStats.Series();
        final BenchStats.Series desync = new BenchStats.Series();
        final BenchStats.Series serverJitter = new BenchStats.Series();
        final BenchStats.Series clientJitter = new BenchStats.Series();
        final BenchStats.Series errorJitter = new BenchStats.Series();
        Map<String, Long> profilerAtStart = Map.of();
        Map<String, Long> profilerAtEnd = Map.of();
        Map<String, String> modes = Map.of();

        Scenario(String name) {
            this.name = name;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ships", ships.size());
            o.add("mspt", series(mspt, true));
            JsonObject sync = new JsonObject();
            sync.addProperty("samples", desync.n());
            sync.addProperty("meanBlocks", desync.mean());
            sync.addProperty("rmsBlocks", desync.rms());
            sync.addProperty("p99Blocks", desync.p99());
            o.add("serverToClientDesync", sync);
            JsonObject jitter = new JsonObject();
            jitter.addProperty("samples", clientJitter.n());
            jitter.addProperty("clientRmsBlocksPerTick2", clientJitter.rms());
            jitter.addProperty("serverRmsBlocksPerTick2", serverJitter.rms());
            jitter.addProperty("errorSamples", errorJitter.n());
            jitter.addProperty("errorRmsBlocksPerTick2", errorJitter.rms());
            o.add("jitter", jitter);
            JsonArray removed = new JsonArray();
            int moves = 0;
            int stops = 0;
            for (Ship ship : ships) {
                moves += ship.moves;
                stops += ship.stops;
                if (!ship.removed && !ship.sub.isRemoved()) continue;
                JsonObject r = new JsonObject();
                r.addProperty("name", ship.name);
                r.addProperty("id", String.valueOf(ship.sub.getUniqueId()));
                r.addProperty("netTick", ship.removedAtTick);
                r.addProperty("minY", ship.minY);
                r.addProperty("maxY", ship.maxY);
                removed.add(r);
            }
            o.add("removed", removed);
            o.addProperty("movePackets", moves);
            o.addProperty("stopPackets", stops);
            JsonArray shipRows = new JsonArray();
            for (Ship ship : ships) {
                JsonObject row = new JsonObject();
                row.addProperty("name", ship.name);
                row.addProperty("id", String.valueOf(ship.sub.getUniqueId()));
                row.addProperty("minY", ship.minY);
                row.addProperty("maxY", ship.maxY);
                row.addProperty("endX", ship.ex);
                row.addProperty("endY", ship.ey);
                row.addProperty("endZ", ship.ez);
                row.addProperty("maxSpeed", ship.maxSpeed);
                row.addProperty("travelBlocks", ship.travel());
                row.addProperty("pathBlocks", ship.path);
                row.addProperty("movePackets", ship.moves);
                row.addProperty("stopPackets", ship.stops);
                row.addProperty("throttledTicks", ship.throttledTicks);
                row.addProperty("pulseTicks", ship.pulseTicks);
                row.addProperty("maxThrottledGap", ship.maxThrottledGap);
                row.addProperty("maxPulseGap", ship.maxPulseGap);
                row.addProperty("removed", ship.removed || ship.sub.isRemoved());
                if (modes.containsKey(ship.name)) row.addProperty("mode", modes.get(ship.name));
                shipRows.add(row);
            }
            o.add("shipRows", shipRows);
            JsonObject delta = new JsonObject();
            for (Map.Entry<String, Long> e : profilerAtEnd.entrySet()) {
                long start = profilerAtStart.getOrDefault(e.getKey(), 0L);
                delta.addProperty(e.getKey(), e.getValue() - start);
            }
            o.add("profilerDelta", delta);
            return o;
        }

        private static JsonObject series(BenchStats.Series s, boolean mspt) {
            JsonObject o = new JsonObject();
            o.addProperty("samples", s.n());
            o.addProperty("mean", s.mean());
            o.addProperty("p99", s.p99());
            o.addProperty("stddev", s.stddev());
            o.addProperty("min", s.min());
            o.addProperty("max", s.max());
            if (mspt) {
                o.addProperty("unit", "ms");
            }
            return o;
        }
    }

    /** Local check that a 10-tick snapshot gap produces a second-difference spike. Not used by the server. */
    static String selfCheck() {
        SableClientModel baselineClock = new SableClientModel();
        SableClientModel.Ship baseline = new SableClientModel.Ship();
        SableClientModel throttledClock = new SableClientModel();
        SableClientModel.Ship throttled = new SableClientModel.Ship();
        BenchStats.Series baseJitter = new BenchStats.Series();
        BenchStats.Series throttleJitter = new BenchStats.Series();
        double[] b0 = null, b1 = null, b2 = null, t0 = null, t1 = null, t2 = null;
        int n = 0;
        for (int tick = 0; tick < 80; tick++) {
            double x = tick * 0.4;
            baseline.receiveMove(tick, x, 80, 0);
            baselineClock.receiveMoves(tick, 50);
            baselineClock.tickClock();
            baseline.sample(baselineClock.pointer());
            if (tick % 10 == 0) {
                throttled.receiveMove(tick, x, 80, 0);
                throttledClock.receiveMoves(tick, 50);
            } else if (tick % 10 == 1) {
                throttledClock.receiveGlobalStop(tick, 50);
            }
            throttledClock.tickClock();
            throttled.sample(throttledClock.pointer());
            b0 = b1; b1 = b2; b2 = new double[] {baseline.x, baseline.y, baseline.z};
            t0 = t1; t1 = t2; t2 = new double[] {throttled.x, throttled.y, throttled.z};
            if (n++ >= 2 && tick > 15) {
                baseJitter.add(BenchStats.secondDifference(b0, b1, b2));
                throttleJitter.add(BenchStats.secondDifference(t0, t1, t2));
            }
        }
        return String.format(Locale.ROOT, "baselineJitter=%.6f throttledJitter=%.6f", baseJitter.rms(), throttleJitter.rms());
    }
}
