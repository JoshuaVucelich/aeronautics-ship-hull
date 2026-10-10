package com.vws.aeronauticsshiphull.hull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.vws.aeronauticsshiphull.api.ShipCadence;
import com.vws.aeronauticsshiphull.config.HullConfig;
import com.vws.aeronauticsshiphull.nested.NestedAssembly;
import com.vws.aeronauticsshiphull.profile.HullProfiler;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import com.vws.aeronauticsshiphull.mixin.accessor.Rapier3DInvoker;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

/**
 * Per-level registry of ship hulls. Owns mode transitions, CHEAP divert from Rapier,
 * explosion rebuild/split, nested assembly bookkeeping, and pilot detection.
 */
public final class ShipHullManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ServerLevel, ShipHullManager> BY_LEVEL = new ConcurrentHashMap<>();

    private final ServerLevel level;
    private final Map<UUID, ShipHull> ships = new ConcurrentHashMap<>();
    private int tickCounter;
    private boolean globallyEnabled = true;
    /** null = use config; Phase 5 A/B override for cheapSoftSleep. */
    public static Boolean softSleepOverride = null;
    /** Phase 6 A/B switch (null/true = on). Off reproduces Phase 5 behaviour in the same jar. */
    public static Boolean phase6Override = null;
    /** Phase 7 A/B switch (null/true = on). In-motion cuts. */
    public static Boolean phase7Override = null;
    /** Block-entity tick A/B switch (null = config). */
    public static Boolean blockEntityTickOverride = null;
    /** True while this mod itself calls into the pipeline (suppresses wake hooks). Server thread only. */
    public static boolean INTERNAL = false;
    /** True while replaying deferred collider updates (bypasses the deferral hook). */
    public static boolean REPLAYING = false;

    private boolean idleNext;
    private boolean reducedNext;
    private int idleRun;
    private boolean stepRanSinceSync = true;

    private ShipHullManager(ServerLevel level) {
        this.level = level;
    }

    public static ShipHullManager get(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, ShipHullManager::new);
    }

    public static ShipHullManager getOrNull(Level level) {
        if (level instanceof ServerLevel sl) return BY_LEVEL.get(sl);
        return null;
    }

    public static void remove(ServerLevel level) {
        BY_LEVEL.remove(level);
    }

    public static void clearAll() {
        BY_LEVEL.clear();
    }

    public Collection<ShipHull> all() {
        return ships.values();
    }

    public ShipHull get(UUID id) {
        return ships.get(id);
    }

    public ShipHull get(ServerSubLevel sub) {
        return ships.get(sub.getUniqueId());
    }

    public boolean globallyEnabled() {
        return globallyEnabled && HullConfig.enabled();
    }

    public void setGloballyEnabled(boolean v) {
        this.globallyEnabled = v;
    }

    public void onSubLevelAdded(ServerSubLevel sub) {
        if (sub == null || sub.isRemoved()) return;
        ShipHull existing = ships.get(sub.getUniqueId());
        if (existing != null) {
            existing.bind(sub);
            return;
        }
        ShipHull hull = new ShipHull(sub);
        ships.put(hull.id, hull);
        hull.setMode(HullMode.FULL, level.getGameTime());
        LOGGER.info("[shiphull] registered ship {} voxels={} interactive={}",
                hull.id, hull.voxels.size(), hull.interactive.size());
    }

    public void onSubLevelRemoved(ServerSubLevel sub) {
        if (sub == null) return;
        ShipHull h = ships.remove(sub.getUniqueId());
        if (h != null) {
            ShipCadence.set(h.id, ShipCadence.Mode.FULL);
            LOGGER.info("[shiphull] unregistered ship {}", h.id);
        }
    }

    public void onBlockChange(ServerSubLevel sub, BlockPos pos, BlockState oldSt, BlockState newSt) {
        ShipHull hull = get(sub);
        if (hull == null) {
            onSubLevelAdded(sub);
            hull = get(sub);
        }
        if (hull == null) return;
        hull.blockChangesThisTick++;
        hull.onBlockChange(pos, oldSt, newSt);
    }

    // ---------------------------------------------------------------- Phase 6

    public static boolean phase6() {
        return phase6Override == null || phase6Override;
    }

    public static boolean phase7() {
        return (phase7Override == null || phase7Override) && HullConfig.phase7();
    }

    public static boolean blockEntityTick() {
        return blockEntityTickOverride != null ? blockEntityTickOverride : HullConfig.blockEntityTick();
    }

    public boolean isPlayerNear(ShipHull hull, double radius) {
        return playerNear(hull, radius);
    }

    /** CHEAP/PARKED ship whose substep work (begin/mass merge/pose readback) Sable may skip.
     * Phase 7 also skips mass merge for unpiloted simplified-hull FULL ships (cached mass). */
    public boolean skipDormantWork(ServerSubLevel sub) {
        if (!globallyEnabled()) return false;
        ShipHull h = ships.get(sub.getUniqueId());
        if (h == null || !h.enabled) return false;
        if (phase6() && HullConfig.skipDormantSubstepWork() && h.mode.dormant()) return true;
        if (phase7() && HullConfig.cacheMass() && h.simplifiedHullCollider && !h.piloted && h.mode == HullMode.FULL) {
            HullProfiler.MASS_CACHE_SKIPS.incrementAndGet();
            return true;
        }
        return false;
    }

    /** Called at the head of Sable's substep loop: skip it entirely this tick? */
    public boolean shouldSkipStep(SubLevelPhysicsSystem sys) {
        if (!idleNext) return false;
        // Live re-check: a ship assembled/woken this tick must simulate.
        for (ShipHull h : ships.values()) {
            if (h.mode.usesRapier() || h.wakeRequested) return false;
        }
        if (sys.getArbitraryObjects().iterator().hasNext()) return false;
        return true;
    }

    public boolean reducedSubsteps() {
        return reducedNext;
    }

    public void noteStepRan() {
        stepRanSinceSync = true;
    }

    /** Pipeline hook: something outside this mod pushed/moved a parked body. */
    public void requestWake(ServerSubLevel sub, String why) {
        if (INTERNAL) return;
        ShipHull h = ships.get(sub.getUniqueId());
        if (h != null && h.mode == HullMode.PARKED && !h.wakeRequested) {
            h.wakeRequested = true;
            h.lastWakeReason = why;
        }
    }

    /** Pipeline hook: external teleport of a parked body becomes its new parked pose. */
    public void adoptParkedPose(ServerSubLevel sub, org.joml.Vector3dc pos, org.joml.Quaterniondc rot) {
        ShipHull h = ships.get(sub.getUniqueId());
        if (h != null && h.mode == HullMode.PARKED) {
            h.parkedPos.set(pos);
            h.parkedRot.set(rot);
        }
    }

    /** Defer a voxel collider update for a ship whose colliders are off. Returns true if deferred. */
    public boolean deferColliderUpdate(ServerSubLevel sub, int gx, int gy, int gz) {
        if (REPLAYING) return false;
        ShipHull h = ships.get(sub.getUniqueId());
        if (h == null) return false;
        boolean soft = phase6() && HullConfig.batchColliderUpdates() && h.collidersDisabled;
        boolean rigid = phase7() && HullConfig.skipBlockColliderWhileMoving()
                && (h.simplifiedHullCollider || h.mode == HullMode.CHEAP);
        if (!soft && !rigid) return false;
        h.pendingColliderBlocks.add(BlockPos.asLong(gx, gy, gz));
        HullProfiler.COLLIDER_UPDATES_DEFERRED.incrementAndGet();
        if (rigid) HullProfiler.BLOCK_COLLIDER_SKIPS.incrementAndGet();
        return true;
    }

    private void replayDeferredColliders(ShipHull hull, PhysicsPipeline pipe) {
        if (hull.pendingColliderBlocks.isEmpty()) return;
        long[] keys = hull.pendingColliderBlocks.toLongArray();
        hull.pendingColliderBlocks.clear();
        REPLAYING = true;
        try {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (long k : keys) {
                p.set(k);
                var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
                if (chunk == null) continue;
                int idx = level.getSectionIndex(p.getY());
                if (idx < 0 || idx >= chunk.getSectionsCount()) continue;
                BlockState st = level.getBlockState(p);
                pipe.handleBlockChange(net.minecraft.core.SectionPos.of(p), chunk.getSection(idx),
                        p.getX() & 15, p.getY() & 15, p.getZ() & 15, st, st);
                HullProfiler.COLLIDER_UPDATES_REPLAYED.incrementAndGet();
            }
        } catch (Throwable t) {
            LOGGER.warn("[shiphull] collider replay failed for {}: {}", hull.id, t.toString());
        } finally {
            REPLAYING = false;
        }
    }

    private boolean playerNear(ShipHull hull, double radius) {
        if (radius <= 0) return false;
        AABB box = hull.worldHullAABB().inflate(radius);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (box.contains(p.getX(), p.getY(), p.getZ())) return true;
        }
        return false;
    }

    private boolean isStill(ShipHull hull) {
        double max = HullConfig.parkMaxSpeed();
        if (hull.mode == HullMode.CHEAP) {
            if (!Double.isNaN(hull.routeTarget.x)) return false;
            return hull.linearVel.length() <= max && hull.angularVel.length() <= max / 4.0;
        }
        ServerSubLevel sub = hull.subLevel();
        return sub.latestLinearVelocity.length() <= max && sub.latestAngularVelocity.length() <= max / 4.0;
    }

    private double speedOf(ShipHull h) {
        return h.mode == HullMode.CHEAP ? h.linearVel.length() : h.subLevel().latestLinearVelocity.length();
    }

    /** Touching non-parked neighbours must also be ready to park, so a ship resting on another never loses its support. */
    private boolean neighboursReady(ShipHull hull) {
        AABB me = hull.worldHullAABB().inflate(1.0);
        for (ShipHull other : ships.values()) {
            if (other == hull || other.mode == HullMode.PARKED || !other.hullValid || other.subLevel() == null) continue;
            if (!me.intersects(other.worldHullAABB())) continue;
            if (other.piloted || other.stillTicks < HullConfig.parkStillTicks() - 1) return false;
        }
        return true;
    }

    private void park(ShipHull hull, long gameTime) {
        ServerSubLevel sub = hull.subLevel();
        Pose3d pose = sub.logicalPose();
        hull.parkedPos.set(pose.position());
        hull.parkedRot.set(pose.orientation());
        hull.linearVel.zero();
        hull.angularVel.zero();
        sub.latestLinearVelocity.zero();
        sub.latestAngularVelocity.zero();
        // Grounded (resting on terrain / a ship): keep colliders so Rapier keeps the body asleep (near-free),
        // no pinning needed. Airborne: colliders off + pin, since gravity would otherwise drop the body.
        hull.parkedGrounded = CheapIntegrator.wouldCollide(hull, this);
        hull.setMode(HullMode.PARKED, gameTime);
        hull.wakeRequested = false;
        hull.stillTicks = 0;
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
        if (phys != null && !hull.parkedGrounded) {
            PhysicsPipeline pipe = phys.getPipeline();
            if (!hull.collidersDisabled) softSleepColliders(hull);
            pinParked(hull, pipe);
        }
        HullProfiler.PARKS.incrementAndGet();
        LOGGER.debug("[shiphull] parked {}", hull.id);
    }

    private final Pose3d driftPose = new Pose3d();

    /** Grounded parked body is normally asleep; if something nudged it in Rapier, put it back. */
    private void checkGroundedDrift(ShipHull hull, PhysicsPipeline pipe) {
        try {
            pipe.readPose(hull.subLevel(), driftPose);
            double d = driftPose.position().distance(hull.parkedPos);
            double a = Math.abs(driftPose.orientation().dot(hull.parkedRot));
            if (d > 0.01 || a < 0.99999) {
                pinParked(hull, pipe);
                HullProfiler.PARKED_DRIFT_FIXES.incrementAndGet();
            }
        } catch (Throwable t) {
            LOGGER.debug("[shiphull] drift check {}: {}", hull.id, t.toString());
        }
    }

    /** Hold a parked ship exactly where it parked (Rapier body may have drifted under gravity). */
    private void pinParked(ShipHull hull, PhysicsPipeline pipe) {
        ServerSubLevel sub = hull.subLevel();
        Pose3d pose = sub.logicalPose();
        pose.position().set(hull.parkedPos);
        pose.orientation().set(hull.parkedRot);
        INTERNAL = true;
        try {
            pipe.teleport(sub, hull.parkedPos, hull.parkedRot);
            pipe.resetVelocity(sub);
        } catch (Throwable t) {
            LOGGER.debug("[shiphull] pin {}: {}", hull.id, t.toString());
        } finally {
            INTERNAL = false;
        }
    }

    private void wake(ShipHull hull, String why, long gameTime) {
        hull.lastWakeReason = why;
        hull.wakeRequested = false;
        hull.stillTicks = 0;
        hull.quietTicks = 0;
        promote(hull, HullMode.FULL, gameTime);
        HullProfiler.WAKES.incrementAndGet();
        LOGGER.debug("[shiphull] woke {} ({})", hull.id, why);
        // Touching parked ships wake too (support / contact must be live before anything moves).
        AABB me = hull.worldHullAABB().inflate(1.0);
        for (ShipHull other : ships.values()) {
            if (other != hull && other.mode == HullMode.PARKED && other.hullValid && other.subLevel() != null
                    && me.intersects(other.worldHullAABB())) {
                wake(other, "contact:" + hull.id, gameTime);
            }
        }
    }

    /** Wake checks for parked ships. Returns true if woken. */
    private boolean updateWake(ShipHull hull, long gameTime) {
        if (hull.mode != HullMode.PARKED) return false;
        String why = null;
        if (hull.wakeRequested) why = "impulse:" + hull.lastWakeReason;
        else if (hull.blockChangesThisTick >= HullConfig.wakeBlockBurst()) why = "blockBurst=" + hull.blockChangesThisTick;
        else if (hull.piloted) why = "piloted";
        else if (playerNear(hull, HullConfig.wakeRadius())) why = "playerNear";
        else {
            AABB me = hull.worldHullAABB();
            for (ShipHull other : ships.values()) {
                if (other == hull || other.mode == HullMode.PARKED || !other.hullValid || other.subLevel() == null) continue;
                double speed = speedOf(other);
                // A resting neighbour is not a threat (prevents park/wake flapping for stacked/adjacent ships).
                boolean active = speed > HullConfig.parkMaxSpeed() || other.piloted || other.mode == HullMode.REBUILDING
                        || (other.mode == HullMode.CHEAP && !Double.isNaN(other.routeTarget.x));
                if (!active) continue;
                AABB o = other.worldHullAABB().inflate(HullConfig.collisionLookahead() + speed);
                if (o.intersects(me)) { why = "shipNear:" + other.id; break; }
            }
        }
        if (why == null) return false;
        wake(hull, why, gameTime);
        return true;
    }

    private void computeSceneFlags() {
        idleNext = false;
        reducedNext = false;
        if (!phase6() || !globallyEnabled()) { idleRun = 0; return; }
        boolean anyRapier = false, anyNear = false;
        double r = HullConfig.distantRadius();
        for (ShipHull h : ships.values()) {
            if (!h.enabled || h.subLevel() == null || h.subLevel().isRemoved()) continue;
            if (h.mode.usesRapier()) {
                anyRapier = true;
                if (h.piloted || h.mode == HullMode.REBUILDING || playerNear(h, r)) anyNear = true;
            }
        }
        if (!anyRapier && HullConfig.idleSceneSkip()) {
            idleRun++;
            idleNext = idleRun % HullConfig.idleFullStepEvery() != 0;
        } else {
            idleRun = 0;
        }
        reducedNext = anyRapier && !anyNear && HullConfig.distantSubsteps();
    }

    public void parkNow(ShipHull hull, long gameTime) {
        if (hull.mode != HullMode.PARKED) park(hull, gameTime);
    }

    public void wakeNow(ShipHull hull, String why, long gameTime) {
        if (hull.mode == HullMode.PARKED) wake(hull, why, gameTime);
    }

    public void wakeAllParked(String why) {
        long t = level.getGameTime();
        for (ShipHull h : ships.values()) if (h.mode == HullMode.PARKED) wake(h, why, t);
    }


    // ---------------------------------------------------------------- Phase 7

    private void updatePhase7Colliders(ShipHull hull, long gameTime) {
        if (!HullConfig.simplifiedHullColliders()) return;
        if (hull.subLevel() == null || hull.subLevel().isRemoved()) return;
        boolean wantSimple = !hull.piloted
                && hull.mode != HullMode.REBUILDING
                && hull.mode != HullMode.PARKED
                && !playerNear(hull, HullConfig.simplifiedRadius())
                && hull.hullValid;
        if (hull.mode == HullMode.CHEAP && hull.collidersDisabled) {
            if (hull.simplifiedHullCollider) hull.simplifiedHullCollider = false;
            return;
        }
        if (wantSimple && hull.mode == HullMode.FULL && !hull.simplifiedHullCollider) {
            applyHullAabbCollider(hull);
        } else if (!wantSimple && hull.simplifiedHullCollider) {
            restoreHullAabbCollider(hull);
        }
    }

    private void applyHullAabbCollider(ShipHull hull) {
        try {
            long scene = Rapier3D.getSceneHandle(level);
            int id = Rapier3D.getID(hull.subLevel());
            int minX = hull.minX, minY = hull.minY, minZ = hull.minZ;
            int maxX = hull.maxX + 1, maxY = hull.maxY + 1, maxZ = hull.maxZ + 1;
            Rapier3DInvoker.ash$setLocalBounds(scene, id, minX, minY, minZ, maxX, maxY, maxZ);
            hull.simplifiedHullCollider = true;
            hull.massCached = true;
            HullProfiler.HULL_AABB_APPLIES.incrementAndGet();
        } catch (Throwable t) {
            LOGGER.warn("[shiphull] hull-AABB collider failed for {}: {}", hull.id, t.toString());
        }
    }

    private void restoreHullAabbCollider(ShipHull hull) {
        try {
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            if (phys != null && phys.getPipeline() != null) {
                PhysicsPipeline pipe = phys.getPipeline();
                pipe.onStatsChanged(hull.subLevel());
                replayDeferredColliders(hull, pipe);
                INTERNAL = true;
                try { pipe.wakeUp(hull.subLevel()); } catch (Throwable ignored) {} finally { INTERNAL = false; }
            }
            hull.simplifiedHullCollider = false;
            hull.massCached = false;
            HullProfiler.HULL_AABB_RESTORES.incrementAndGet();
        } catch (Throwable t) {
            LOGGER.warn("[shiphull] hull-AABB restore failed for {}: {}", hull.id, t.toString());
        }
    }

    private void throttleNetwork(ShipHull hull, long gameTime) {
        if (hull.piloted || hull.mode == HullMode.REBUILDING) return;
        if (playerNear(hull, HullConfig.simplifiedRadius())) return;
        ServerSubLevel sub = hull.subLevel();
        if (sub == null) return;
        int period = HullConfig.networkPeriodTicks();
        boolean pulse = (gameTime - hull.lastNetworkPulseTick) >= period;
        if (pulse) {
            hull.lastNetworkPulseTick = gameTime;
            try { sub.setLastNetworkedStopped(false); } catch (Throwable ignored) {}
        } else {
            try {
                sub.setLastNetworkedStopped(true);
                HullProfiler.NETWORK_THROTTLES.incrementAndGet();
            } catch (Throwable ignored) {}
        }
    }

    public String phase7Status() {
        int simple = 0, mass = 0;
        for (ShipHull h : ships.values()) {
            if (h.simplifiedHullCollider) simple++;
            if (h.massCached) mass++;
        }
        return String.format("phase7=%s simplifiedHull=%d massCached=%d", phase7(), simple, mass);
    }

    public String phase6Status() {
        int parked = 0, pending = 0, grounded = 0;
        for (ShipHull h : ships.values()) {
            if (h.mode == HullMode.PARKED) { parked++; if (h.parkedGrounded) grounded++; }
            pending += h.pendingColliderBlocks.size();
        }
        return String.format("phase6=%s parked=%d (grounded=%d airborne=%d) idleNext=%s reducedNext=%s pendingCollider=%d",
                phase6(), parked, grounded, parked - grounded, idleNext, reducedNext, pending);
    }

    /**
     * Called from physics mixin before Rapier step.
     * We do NOT remove bodies mid-step (Rapier natives panic on getLinearVelocity for missing IDs).
     * Divert/reenter happens in {@link #endTick()} between ticks. This hook only counts skipped bodies.
     */
    public void divertCheapFromRapier(SubLevelPhysicsSystem phys) {
        // Membership sync is in endTick; physics mixin only needs the force-skip path live.
    }

    /**
     * CHEAP ships stay registered in Rapier (pipeline.remove panics natives on later getLinearVelocity;
     * Sable's Rapier3D JNI also has no sleep/setEnabled export — nm shows only wakeUpObject).
     * Phase 5 soft-sleep: empty local bounds (disable colliders) via setLocalBounds, skip forces,
     * CheapIntegrator owns pose, teleport+resetVelocity each sync. FULL/REBUILDING restore bounds.
     */
    private void syncRapierMembership() {
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
        if (phys == null) return;
        PhysicsPipeline pipe = phys.getPipeline();
        if (pipe == null) return;
        for (ShipHull hull : ships.values()) {
            if (!hull.enabled || hull.subLevel() == null || hull.subLevel().isRemoved()) continue;
            // Always keep inRapier=true while the sub-level exists — never remove.
            hull.inRapier = true;
            if (hull.mode == HullMode.PARKED) {
                // Body only moves if a real Rapier step ran since last sync (idle skip => nothing to undo).
                if (stepRanSinceSync) {
                    if (!hull.parkedGrounded) pinParked(hull, pipe);
                    else if ((tickCounter + (hull.id.hashCode() & 15)) % 20 == 0) checkGroundedDrift(hull, pipe);
                }
                HullProfiler.RAPIER_BODIES_SKIPPED.incrementAndGet();
            } else if (hull.mode == HullMode.CHEAP) {
                INTERNAL = true;
                try {
                    if (cheapSoftSleepEnabled() && !hull.collidersDisabled) {
                        softSleepColliders(hull);
                    }
                    var pose = hull.subLevel().logicalPose();
                    pipe.teleport(hull.subLevel(), pose.position(), pose.orientation());
                    pipe.resetVelocity(hull.subLevel());
                    HullProfiler.RAPIER_BODIES_SKIPPED.incrementAndGet();
                } catch (Throwable t) {
                    LOGGER.debug("[shiphull] cheap sync {}: {}", hull.id, t.toString());
                } finally {
                    INTERNAL = false;
                }
            } else if (hull.collidersDisabled) {
                restoreColliders(hull, pipe);
            }
        }
    }


    public static boolean cheapSoftSleepEnabled() {
        return softSleepOverride != null ? softSleepOverride : HullConfig.cheapSoftSleep();
    }

    public void setSoftSleep(boolean on) {
        softSleepOverride = on;
        if (!on) {
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            if (phys == null) return;
            PhysicsPipeline pipe = phys.getPipeline();
            if (pipe == null) return;
            for (ShipHull hull : ships.values()) {
                if (hull.collidersDisabled && hull.mode != HullMode.PARKED) restoreColliders(hull, pipe);
            }
        }
    }

    /** Empty Rapier local bounds so the body contributes no voxel colliders (still in the scene). */
    private void softSleepColliders(ShipHull hull) {
        try {
            long scene = Rapier3D.getSceneHandle(level);
            int id = Rapier3D.getID(hull.subLevel());
            // min > max => empty AABB / no collider volume
            Rapier3DInvoker.ash$setLocalBounds(scene, id, 0, 0, 0, -1, -1, -1);
            hull.collidersDisabled = true;
            HullProfiler.COLLIDER_DISABLES.incrementAndGet();
            LOGGER.debug("[shiphull] soft-sleep colliders off for {}", hull.id);
        } catch (Throwable t) {
            LOGGER.warn("[shiphull] soft-sleep failed for {}: {}", hull.id, t.toString());
        }
    }

    private void restoreColliders(ShipHull hull, PhysicsPipeline pipe) {
        try {
            if (pipe instanceof RapierPhysicsPipeline) {
                pipe.onStatsChanged(hull.subLevel());
            } else {
                // Fallback: re-apply bounds from plot via onStatsChanged default
                pipe.onStatsChanged(hull.subLevel());
            }
            hull.collidersDisabled = false;
            replayDeferredColliders(hull, pipe);
            HullProfiler.COLLIDER_RESTORES.incrementAndGet();
            INTERNAL = true;
            try { pipe.wakeUp(hull.subLevel()); } catch (Throwable ignored) {} finally { INTERNAL = false; }
            LOGGER.debug("[shiphull] restored colliders for {}", hull.id);
        } catch (Throwable t) {
            LOGGER.warn("[shiphull] collider restore failed for {}: {}", hull.id, t.toString());
        }
    }

    private void reenterRapier(ShipHull hull, PhysicsPipeline pipe) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || sub.isRemoved()) return;
        try {
            Pose3dc pose = sub.logicalPose();
            pipe.add(sub, pose);
            // Prefer teleport + impulse APIs that do not read missing native state
            pipe.teleport(sub, pose.position(), pose.orientation());
            try {
                pipe.applyLinearAndAngularImpulse(sub, hull.linearVel, hull.angularVel, false);
            } catch (Throwable ignored) {}
            try { pipe.wakeUp(sub); } catch (Throwable ignored) {}
            hull.inRapier = true;
        } catch (Throwable t) {
            LOGGER.warn("[shiphull] failed to re-enter Rapier for {}: {}", hull.id, t.toString());
        }
    }

    /** Level tick: pilot detect, mode transitions, CHEAP integrate, nested poses, routes. */
    public void endTick() {
        tickCounter++;
        long gameTime = level.getGameTime();
        syncFromContainer();
        if (globallyEnabled()) syncRapierMembership();

        for (ShipHull hull : ships.values()) {
            if (!hull.enabled || hull.subLevel() == null || hull.subLevel().isRemoved()) continue;
            updatePiloted(hull);
            if (phase6() && globallyEnabled()) updateWake(hull, gameTime);
            updateMode(hull, gameTime);
            hull.blockChangesThisTick = 0;

            if (globallyEnabled() && phase7()) updatePhase7Colliders(hull, gameTime);
            if (globallyEnabled() && phase7() && HullConfig.throttleNetwork()) throttleNetwork(hull, gameTime);

            if (globallyEnabled() && hull.mode == HullMode.CHEAP) {
                int period = HullConfig.cheapPeriodTicks();
                if (phase7() && HullConfig.distantCheapSlowdown() && !playerNear(hull, HullConfig.distantRadius())) {
                    period = Math.max(period, HullConfig.distantCheapPeriodTicks());
                    HullProfiler.DISTANT_CHEAP_STEPS.incrementAndGet();
                }
                // Phase 4: step on game-time phase so parented factories (FactoryCadence) align to the same tick
                if (gameTime % period == ShipCadence.phaseOf(hull.id, period)) {
                    boolean collision = CheapIntegrator.step(hull, this, 0.05 * period);
                    HullProfiler.CHEAP_STEPS.incrementAndGet();
                    if (collision) {
                        promote(hull, HullMode.FULL, gameTime);
                    }
                }
                applyNestedTransforms(hull);
            } else if (hull.mode.usesRapier()) {
                hull.rapierSteps++;
                HullProfiler.RAPIER_STEPS.incrementAndGet();
                // Keep velocities mirrored for future demotion
                hull.linearVel.set(hull.subLevel().latestLinearVelocity);
                hull.angularVel.set(hull.subLevel().latestAngularVelocity);
                applyNestedTransforms(hull);
            }
        }
        computeSceneFlags();
        stepRanSinceSync = false;
    }

    private void syncFromContainer() {
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return;
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                if (!ships.containsKey(sub.getUniqueId())) {
                    onSubLevelAdded(sub);
                } else {
                    ships.get(sub.getUniqueId()).bind(sub);
                }
            }
        } catch (Throwable t) {
            // Container may not be ready yet
        }
    }

    private void updatePiloted(ShipHull hull) {
        boolean was = hull.piloted;
        hull.piloted = false;
        AABB world = hull.worldHullAABB().inflate(1.5);
        for (ServerPlayer p : level.players()) {
            if (world.contains(p.getX(), p.getY(), p.getZ()) || world.intersects(p.getBoundingBox())) {
                hull.piloted = true;
                break;
            }
        }
        // Also treat tracking players standing in hull as piloted
        if (!hull.piloted) {
            for (UUID id : hull.subLevel().getTrackingPlayers()) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                if (p != null && world.intersects(p.getBoundingBox())) {
                    hull.piloted = true;
                    break;
                }
            }
        }
        if (hull.piloted && !was) {
            LOGGER.debug("[shiphull] {} now piloted", hull.id);
        }
    }

    private void updateMode(ShipHull hull, long gameTime) {
        if (!globallyEnabled()) {
            if (hull.mode != HullMode.FULL) promote(hull, HullMode.FULL, gameTime);
            return;
        }
        if (hull.mode == HullMode.REBUILDING) {
            if (gameTime >= hull.rebuildUntilTick) {
                // After rebuild window: piloted→FULL, else start quiet countdown toward CHEAP
                if (hull.piloted && HullConfig.pilotedUsesRapier()) {
                    promote(hull, HullMode.FULL, gameTime);
                } else {
                    hull.setMode(HullMode.FULL, gameTime);
                    hull.quietTicks = 0;
                }
            }
            return;
        }
        if (hull.piloted && HullConfig.pilotedUsesRapier()) {
            if (hull.mode != HullMode.FULL) promote(hull, HullMode.FULL, gameTime);
            hull.quietTicks = 0;
            hull.stillTicks = 0;
            return;
        }
        if (hull.mode == HullMode.PARKED) return; // woken only via updateWake
        if (phase6() && HullConfig.parkEnabled()) {
            if (isStill(hull)) hull.stillTicks++; else hull.stillTicks = 0;
            if (hull.stillTicks >= HullConfig.parkStillTicks() && !playerNear(hull, HullConfig.wakeRadius()) && neighboursReady(hull)) {
                park(hull, gameTime);
                return;
            }
        }
        // Unpiloted: demote to CHEAP after quiet period
        if (hull.mode == HullMode.FULL) {
            hull.quietTicks++;
            if (hull.quietTicks >= HullConfig.quietTicksBeforeCheap()) {
                demote(hull, gameTime);
            }
        }
    }

    public void promote(ShipHull hull, HullMode mode, long gameTime) {
        HullMode prev = hull.mode;
        if (prev == HullMode.PARKED) {
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            if (phys != null) pinParked(hull, phys.getPipeline()); // re-enter Rapier exactly where it parked, zero velocity
            hull.wakeRequested = false;
        }
        hull.setMode(mode, gameTime);
        if (mode == HullMode.REBUILDING) {
            hull.rebuildUntilTick = gameTime + HullConfig.rebuildWindowTicks();
        }
        if (!hull.inRapier) {
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            if (phys != null) reenterRapier(hull, phys.getPipeline());
        }
        if (mode.usesRapier() && hull.collidersDisabled) {
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            if (phys != null) restoreColliders(hull, phys.getPipeline());
        }
        if (prev.dormant() && mode.usesRapier()) {
            HullProfiler.MODE_PROMOTIONS.incrementAndGet();
        }
    }

    public long demotesSuppressed;

    private void demote(ShipHull hull, long gameTime) {
        if (hull.mode == HullMode.CHEAP) return;
        // Phase 4: don't announce CHEAP if the first hull step would promote straight back
        // (ship parked on / next to terrain). Prevents FULL<->CHEAP event flapping every quiet period.
        if (CheapIntegrator.wouldCollide(hull, this)) {
            hull.quietTicks = 0;
            demotesSuppressed++;
            return;
        }
        hull.setMode(HullMode.CHEAP, gameTime);
        HullProfiler.MODE_DEMOTIONS.incrementAndGet();
        // Actual pipeline.remove happens next physics tick in divertCheapFromRapier
    }

    private void applyNestedTransforms(ShipHull parent) {
        if (parent.nested.isEmpty()) return;
        Pose3d parentPose = parent.subLevel().logicalPose();
        for (NestedAssembly child : parent.nested) {
            ShipHull childHull = ships.get(child.childId);
            if (childHull == null || childHull.subLevel() == null) continue;
            Vector3d world = new Vector3d(child.localPos);
            parentPose.orientation().transform(world);
            world.add(parentPose.position());
            Quaterniond worldRot = new Quaterniond(parentPose.orientation()).mul(child.localRot);
            Pose3d cp = childHull.subLevel().logicalPose();
            cp.position().set(world);
            cp.orientation().set(worldRot);
            childHull.subLevel().updateBoundingBox();
            if (childHull.inRapier && childHull.mode.usesRapier()) {
                SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
                if (phys != null) {
                    try {
                        phys.getPipeline().teleport(childHull.subLevel(), world, worldRot);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    /**
     * Simulate / handle an explosion at a world position: delete voxels inside radius,
     * rebuild hull, split into separate bodies if connectivity breaks.
     * Does NOT return to per-block ticking.
     */
    public int handleExplosion(ShipHull hull, double worldX, double worldY, double worldZ, double radius) {
        if (hull == null || hull.subLevel() == null) return 0;
        ServerSubLevel sub = hull.subLevel();
        var accessor = sub.getPlot().getEmbeddedLevelAccessor();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongSet removed = new LongOpenHashSet();

        // Test each voxel in ship-local space against world-space blast
        Pose3d pose = sub.logicalPose();
        Vector3d tmp = new Vector3d();
        Vector3d rp = pose.rotationPoint();
        double r2 = radius * radius;
        for (long key : new LongOpenHashSet(hull.voxels)) {
            int lx = BlockPos.getX(key), ly = BlockPos.getY(key), lz = BlockPos.getZ(key);
            tmp.set(lx + 0.5 - rp.x, ly + 0.5 - rp.y, lz + 0.5 - rp.z);
            pose.orientation().transform(tmp);
            tmp.add(pose.position());
            double dx = tmp.x - worldX, dy = tmp.y - worldY, dz = tmp.z - worldZ;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                removed.add(key);
                cursor.set(lx, ly, lz);
                accessor.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                hull.voxels.remove(key);
                hull.interactive.remove(key);
            }
        }
        hull.recomputeHullBounds();
        hull.explosionsHandled++;
        HullProfiler.EXPLOSIONS.incrementAndGet();

        promote(hull, HullMode.REBUILDING, level.getGameTime());

        List<LongSet> comps = Connectivity.components(hull.voxels);
        if (comps.size() >= 2) {
            // Keep largest on original; spawn note for the rest (console-testable split bookkeeping).
            comps.sort((a, b) -> Integer.compare(b.size(), a.size()));
            LongSet keep = comps.get(0);
            hull.voxels.clear();
            hull.voxels.addAll(keep);
            hull.recomputeHullBounds();
            for (int i = 1; i < comps.size(); i++) {
                LongSet piece = comps.get(i);
                // Record a "virtual split" child hull entry with its own UUID for Phase 3 console tests.
                // Full SubLevelAssemblyHelper split into a new ServerSubLevel needs player/world context;
                // we register a NestedAssembly-less sibling ShipHull marker and log it.
                UUID splitId = UUID.randomUUID();
                LOGGER.info("[shiphull] split {} → new component {} voxels={} (logical split; physical SubLevel spawn via /shiphull split_apply)",
                        hull.id, splitId, piece.size());
                hull.splitsPerformed++;
                HullProfiler.SPLITS.incrementAndGet();
                // Store pending split voxels on the parent for /shiphull split_apply
                hull.subLevel().getUserDataTag().putLongArray("ash_pending_split_" + i, piece.toLongArray());
            }
            hull.subLevel().getUserDataTag().putInt("ash_pending_splits", comps.size() - 1);
        }
        return removed.size();
    }

    /** Attach child as nested assembly (bearing/crane on deck). */
    public void attachNested(ShipHull parent, UUID childId, double lx, double ly, double lz, String label) {
        NestedAssembly n = new NestedAssembly(childId, label);
        n.setLocal(lx, ly, lz, new Quaterniond());
        parent.nested.removeIf(a -> a.childId.equals(childId));
        parent.nested.add(n);
        ShipHull child = ships.get(childId);
        if (child != null) {
            // Child follows parent — keep it CHEAP when parent is CHEAP (still one body each, parent transform)
            child.quietTicks = 0;
        }
    }

    public String statusLine() {
        int cheap = 0, full = 0, rebuild = 0, piloted = 0, voxels = 0, parked = 0;
        for (ShipHull h : ships.values()) {
            switch (h.mode) {
                case CHEAP -> cheap++;
                case FULL -> full++;
                case REBUILDING -> rebuild++;
                case PARKED -> parked++;
            }
            if (h.piloted) piloted++;
            voxels += h.voxels.size();
        }
        return String.format("ships=%d cheap=%d parked=%d full=%d rebuilding=%d piloted=%d voxels=%d enabled=%s softSleep=%s bridge=%s demotesSuppressed=%d | %s | %s | %s",
                ships.size(), cheap, parked, full, rebuild, piloted, voxels,
                globallyEnabled(), cheapSoftSleepEnabled(), ShipCadence.factoryBridgeActive(), demotesSuppressed, phase6Status(), phase7Status(), HullProfiler.summary());
    }
}
