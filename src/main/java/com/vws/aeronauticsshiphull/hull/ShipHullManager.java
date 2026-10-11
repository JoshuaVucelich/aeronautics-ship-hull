package com.vws.aeronauticsshiphull.hull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.vws.aeronauticsshiphull.api.ShipCadence;
import com.vws.aeronauticsshiphull.config.HullConfig;
import com.vws.aeronauticsshiphull.nested.NestedAssembly;
import com.vws.aeronauticsshiphull.profile.HullProfiler;
import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import com.vws.aeronauticsshiphull.mixin.accessor.Rapier3DInvoker;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
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
    /** Phase 8 A/B switch (null/true = on). Rapier step LOD. */
    public static Boolean phase8Override = null;
    /** Collider-cache A/B switch (null = config). */
    public static Boolean colliderCacheOverride = null;
    /** Contraption-tick A/B switch (null = config). */
    public static Boolean contraptionTickOverride = null;
    /** Block-entity tick A/B switch (null = config). */
    public static Boolean blockEntityTickOverride = null;
    /** Phase 8 ship-sync A/B. null = config ship_hull.phase8.shipSync. */
    public static Boolean shipSyncOverride = null;
    /**
     * Startup lock when a park or pose mixin did not apply, or when {@code shiphull.forceSafeMode}
     * is set. {@code /shiphull safe-mode off} cannot clear it.
     */
    public static boolean safeModeForced = false;
    /** null follows config. {@code /shiphull safe-mode off} sets this back to null. */
    public static Boolean safeModeOverride = null;
    private long lastPhysicsNanos;
    private long lastLagLogNanos;
    /** Consecutive physics gaps at or above {@code lagWakeMillis}. One pause does not wake. */
    private int slowGaps;
    /** True while this mod itself calls into the pipeline (suppresses wake hooks). Server thread only. */
    public static boolean INTERNAL = false;
    /** True while replaying deferred collider updates (bypasses the deferral hook). */
    public static boolean REPLAYING = false;

    private boolean idleNext;
    private boolean reducedNext;
    private int idleRun;
    private boolean stepRanSinceSync = true;
    /** Set when this tick's Rapier step was replaced by coasting. Consumed in endTick. */
    private boolean coastedThisTick;
    /** World AABBs of parked unattended ships, rebuilt once per tick. */
    private final ArrayList<AABB> parkedWorldBoxes = new ArrayList<>();
    private long parkedWorldTick = Long.MIN_VALUE;
    /** Chunk -> 1 allow, 2 skip. Cleared when the game time changes. 0 means absent. */
    private final Long2ByteOpenHashMap entitySyncCache = new Long2ByteOpenHashMap();
    private long entitySyncCacheTick = Long.MIN_VALUE;
    /** World boxes of ships whose pose send is throttled this tick. Rebuilt once per tick. */
    private final List<AABB> throttledWorldBoxes = new ArrayList<>();
    private long throttledWorldTick = Long.MIN_VALUE;
    /** Connectivity rebuilt once per end tick and on park/wake commands. */
    private List<ParkGroups.Group> groupsThisTick = List.of();
    /** Ships added since the last group rebuild. Flushed once per physics tick, not once per ship. */
    private final Set<UUID> pendingAdds = ConcurrentHashMap.newKeySet();
    /** Hulls that contained a moving non-player on the last shared entity scan. */
    private final Set<UUID> movingHits = new HashSet<>();
    private long movingScanTick = Long.MIN_VALUE;
    private boolean entityScanFailedLogged;
    /** Group keys already logged by the joint velocity clamp. Cleared at {@link #WARN_ONCE_CAP}. */
    private final Set<String> jointClampLogged = new HashSet<>();
    private static final int WARN_ONCE_CAP = 256;
    private static final int ENTITY_CELL = 32;
    private static final int ENTITY_CELL_CAP = 8;
    private static final int ENTITY_SCAN_PERIOD = 4;

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

    public static boolean safeMode() {
        if (safeModeForced) return true;
        if (safeModeOverride != null) return safeModeOverride;
        return HullConfig.safeMode();
    }

    public static void anomaly(String message) {
        LOGGER.warn("[shiphull] anomaly: {}", message);
    }

    public void onSubLevelAdded(ServerSubLevel sub) {
        if (sub == null || sub.isRemoved()) return;
        if (sub.getLevel() != this.level) {
            anomaly("ignored ship " + sub.getUniqueId() + " registered from another level");
            return;
        }
        ShipHull existing = ships.get(sub.getUniqueId());
        if (existing != null) {
            existing.bind(sub);
            wakeBody(sub);
            pendingAdds.add(existing.id);
            return;
        }
        ShipHull hull = new ShipHull(sub);
        ships.put(hull.id, hull);
        hull.setMode(HullMode.FULL, level.getGameTime());
        LOGGER.info("[shiphull] registered ship {} voxels={} interactive={}",
                hull.id, hull.voxels.size(), hull.interactive.size());
        wakeBody(sub);
        pendingAdds.add(hull.id);
    }

    /**
     * Rebuild groups once for every ship queued by {@link #onSubLevelAdded}. A parked partner
     * wakes in that same pass ({@code memberLoaded}). Unloads stay immediate.
     */
    public void flushPendingGroups() {
        if (pendingAdds.isEmpty()) return;
        Set<UUID> added = new HashSet<>(pendingAdds);
        pendingAdds.clear();
        refreshGroups();
        long gameTime = level.getGameTime();
        for (ParkGroups.Group group : groupsThisTick) {
            boolean parked = false;
            boolean hasNew = false;
            for (ShipHull member : group.members) {
                if (added.contains(member.id)) hasNew = true;
                if (member.mode == HullMode.PARKED) parked = true;
            }
            if (!parked || !hasNew) continue;
            try {
                wakeGroup(group.members, "memberLoaded", gameTime);
            } catch (Throwable t) {
                anomaly("member load wake failed: " + t);
            }
        }
    }

    public void onSubLevelRemoved(ServerSubLevel sub) {
        if (sub == null) return;
        ShipHull h = ships.get(sub.getUniqueId());
        if (h == null) return;
        pendingAdds.remove(sub.getUniqueId());
        JointLinks.forgetShip(h.id);
        jointClampLogged.removeIf(key -> key.contains(h.id.toString()));
        try {
            wakePartnersIfParked(h, "memberUnloaded");
        } catch (Throwable t) {
            anomaly("member unload wake failed for " + h.id + ": " + t);
            try {
                wakeAllParked("memberUnloaded");
            } catch (Throwable ignored) {
                // The next tick tries again.
            }
        }
        ships.remove(sub.getUniqueId());
        ShipCadence.set(h.id, ShipCadence.Mode.FULL);
        LOGGER.info("[shiphull] unregistered ship {}", h.id);
    }

    /** A body saved asleep by a crash is woken as it loads. Parked mode itself is not saved. */
    private void wakeBody(ServerSubLevel sub) {
        try {
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            if (phys == null) return;
            PhysicsPipeline pipe = phys.getPipeline();
            if (pipe != null) pipe.wakeUp(sub);
        } catch (Throwable t) {
            anomaly("wakeUp on add failed for " + sub.getUniqueId() + ": " + t);
        }
    }

    private void wakePartnersIfParked(ShipHull hull, String why) {
        refreshGroups();
        ParkGroups.Group group = groupOf(hull);
        boolean parked = false;
        for (ShipHull member : group.members) {
            if (member.mode == HullMode.PARKED) parked = true;
        }
        if (parked) wakeGroup(group.members, why, level.getGameTime());
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
        // Explosion edits are counted here and applied after the ship has already left PARKED.
        if (hull.blockEditsCountOnly) return;
        if (hull.mode == HullMode.PARKED) {
            try {
                wakePartnersIfParked(hull, "blockChange");
            } catch (Throwable t) {
                anomaly("block change wake failed for " + hull.id + ": " + t);
            }
        }
    }

    // ---------------------------------------------------------------- Phase 6

    public static boolean phase6() {
        return phase6Override == null || phase6Override;
    }

    public static boolean phase7() {
        return (phase7Override == null || phase7Override) && HullConfig.phase7();
    }

    public static boolean phase8() {
        return (phase8Override == null || phase8Override) && HullConfig.phase8();
    }

    public static boolean colliderCache() {
        return colliderCacheOverride != null ? colliderCacheOverride : HullConfig.colliderCache();
    }

    public static boolean contraptionTick() {
        return contraptionTickOverride != null ? contraptionTickOverride : HullConfig.contraptionTick();
    }

    /** Parked, or settled CHEAP: the welded contraption pose cannot have moved. */
    public boolean skipContraptionPose(double x, double y, double z) {
        if (!contraptionTick()) return false;
        ShipHull h = hullAt(x, y, z);
        if (h == null || !h.enabled || !h.poseReady()) return false;
        if (h.mode == HullMode.PARKED) return true;
        return h.mode == HullMode.CHEAP
                && h.linearVel.lengthSquared() < 1e-6
                && h.angularVel.lengthSquared() < 1e-6;
    }

    /** True when this contraption entity tick should be skipped (parked, nobody near, off-period). */
    public boolean throttleContraptionEntity(double x, double y, double z, long gameTime) {
        if (!contraptionTick()) return false;
        ShipHull h = hullAt(x, y, z);
        if (h == null || !h.poseReady() || h.voxels.isEmpty() || h.mode != HullMode.PARKED) return false;
        if (playerNear(h, HullConfig.wakeRadius())) return false;
        int period = HullConfig.contraptionEntityPeriod();
        if (period <= 1) return false;
        return Math.floorMod(gameTime + (h.id.hashCode() & 1023), period) != 0;
    }

    private ShipHull hullAt(double x, double y, double z) {
        for (ShipHull h : ships.values()) {
            if (!h.poseReady()) continue;
            if (h.worldHullAABB().inflate(2.0).contains(x, y, z)) return h;
        }
        return null;
    }

    public static boolean blockEntityTick() {
        return blockEntityTickOverride != null ? blockEntityTickOverride : HullConfig.blockEntityTick();
    }

    public boolean isPlayerNear(ShipHull hull, double radius) {
        return playerNear(hull, radius);
    }

    /**
     * True when a world-space point sits on a parked ship nobody is near.
     * Sable moves riding entities to the ship pose, so their chunk is not the storage plot.
     */
    public boolean parkedUnattendedContains(double x, double y, double z) {
        if (!blockEntityTick() || !globallyEnabled()) return false;
        long now = level.getGameTime();
        if (parkedWorldTick != now) {
            parkedWorldTick = now;
            parkedWorldBoxes.clear();
            for (ShipHull hull : ships.values()) {
                if (!hull.enabled || !hull.poseReady() || hull.mode != HullMode.PARKED || hull.subLevel() == null) continue;
                if (playerNear(hull, HullConfig.wakeRadius())) continue;
                parkedWorldBoxes.add(hull.worldHullAABB().inflate(2.0));
            }
        }
        for (AABB box : parkedWorldBoxes) {
            if (box.contains(x, y, z)) return true;
        }
        return false;
    }

    /** CHEAP/PARKED ship whose substep work (begin/mass merge/pose readback) Sable may skip.
     * Phase 7 also skips mass merge for unpiloted simplified-hull FULL ships (cached mass).
     * Pose readback stays on for FULL ships so a diverging body is visible to the pose guard. */
    public boolean skipDormantWork(ServerSubLevel sub) {
        if (!globallyEnabled()) return false;
        ShipHull h = ships.get(sub.getUniqueId());
        if (h == null || !h.enabled) return false;
        if (phase6() && HullConfig.skipDormantSubstepWork() && h.mode.dormant()) return true;
        return false;
    }

    /** Mass merge only. Simplified FULL ships keep their cached mass, but still read their pose back.
     *  A jointed ship always merges. A cached mass on one side of a joint is how a partner runs away. */
    public boolean skipMassMerge(ServerSubLevel sub) {
        if (!globallyEnabled()) return false;
        ShipHull h = ships.get(sub.getUniqueId());
        if (h != null && h.enabled && sharesConstraint(h)) return false;
        if (skipDormantWork(sub)) return true;
        if (h == null || !h.enabled) return false;
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

    /**
     * Skip {@code Rapier3D.step} this tick and coast separated unpiloted FULL ships at the last
     * solved velocity. A real step still runs every {@code coastRealStepEvery} ticks. Any piloted,
     * rebuilding, or about-to-collide ship forces the real step for the whole scene (Rapier steps
     * every body together).
     */
    public boolean shouldCoastStep() {
        coastedThisTick = false;
        if (!phase8() || !globallyEnabled() || !HullConfig.coastSeparated()) return false;
        int period = HullConfig.coastRealStepEvery();
        if (period <= 1) return false;
        if (Math.floorMod(level.getGameTime(), period) == 0) return false;
        boolean anyFull = false;
        for (ShipHull h : ships.values()) {
            if (!h.enabled || h.subLevel() == null || h.subLevel().isRemoved()) continue;
            if (!h.poseReady()) return false;
            if (!h.mode.usesRapier()) continue;
            if (h.mode == HullMode.REBUILDING || h.piloted || h.wakeRequested || h.blockChangesThisTick > 0) return false;
            anyFull = true;
            if (!coastClear(h)) return false;
        }
        if (!anyFull) return false;
        coastedThisTick = true;
        return true;
    }

    public boolean coastedThisTick() {
        return coastedThisTick;
    }

    /** Real step, but nobody is piloting or standing near a Rapier ship: solver can run fewer iterations. */
    public boolean distantSolverThisTick() {
        return phase8() && globallyEnabled() && HullConfig.distantSolver() && reducedNext;
    }

    private boolean coastClear(ShipHull hull) {
        if (playerNear(hull, HullConfig.wakeRadius())) return false;
        ServerSubLevel sub = hull.subLevel();
        double dt = 0.05 * HullConfig.coastRealStepEvery();
        double vx = sub.latestLinearVelocity.x;
        double vy = sub.latestLinearVelocity.y;
        double vz = sub.latestLinearVelocity.z;
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        AABB world = hull.worldHullAABB().inflate(HullConfig.collisionLookahead() + speed * dt);
        world = world.move(vx * dt, vy * dt, vz * dt);
        if (CheapIntegrator.aabbHitsSolid(level, world)) return false;
        for (ShipHull other : ships.values()) {
            if (other == hull || !other.hullValid || other.subLevel() == null) continue;
            if (world.intersects(other.worldHullAABB().inflate(1.0))) return false;
        }
        return true;
    }

    /** Constant-velocity step for one skipped Rapier tick. Colliders stay; the body is teleported so the next real step starts here. */
    private void integrateCoast(ShipHull hull) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || !hull.poseReady()) return;
        double dt = 0.05;
        Pose3d pose = sub.logicalPose();
        if (pose == null || pose.position() == null || pose.orientation() == null) return;
        var v = sub.latestLinearVelocity;
        pose.position().add(v.x * dt, v.y * dt, v.z * dt);
        var w = sub.latestAngularVelocity;
        if (w.lengthSquared() > 1e-12) {
            org.joml.Quaterniond dq = new org.joml.Quaterniond().rotateXYZ(w.x * dt, w.y * dt, w.z * dt);
            pose.orientation().mul(dq).normalize();
        }
        hull.linearVel.set(v);
        hull.angularVel.set(w);
        hull.bumpPose();
        sub.updateBoundingBox();
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
        if (phys == null || phys.getPipeline() == null) return;
        INTERNAL = true;
        try {
            phys.getPipeline().teleport(sub, pose.position(), pose.orientation());
        } catch (Throwable t) {
            LOGGER.debug("[shiphull] coast teleport {}: {}", hull.id, t.toString());
        } finally {
            INTERNAL = false;
        }
        HullProfiler.COAST_INTEGRATIONS.incrementAndGet();
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

    /**
     * Pipeline hook: an external teleport is a real pose. A parked body adopts it and stays parked.
     * Every ship resets last-good so a /tp farther than the jump cap is not pulled back.
     */
    public void adoptParkedPose(ServerSubLevel sub, org.joml.Vector3dc pos, org.joml.Quaterniondc rot) {
        ShipHull h = ships.get(sub.getUniqueId());
        if (h == null) return;
        if (h.mode == HullMode.PARKED) {
            h.parkedPos.set(pos);
            h.parkedRot.set(rot);
        }
        PoseGuard.acceptTeleport(h, pos, rot);
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
            if (colliderCache() && keys.length >= HullConfig.colliderSectionReplayAt()
                    && replayDeferredSections(keys, pipe)) {
                return;
            }
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

    /** One native chunk upload per dirty 16³ section. Returns false if nothing was uploaded. */
    private boolean replayDeferredSections(long[] keys, PhysicsPipeline pipe) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet sections = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long k : keys) sections.add(net.minecraft.core.SectionPos.asLong(BlockPos.of(k)));
        boolean any = false;
        for (long packed : sections) {
            net.minecraft.core.SectionPos sp = net.minecraft.core.SectionPos.of(packed);
            var chunk = level.getChunkSource().getChunkNow(sp.x(), sp.z());
            if (chunk == null) continue;
            int idx = level.getSectionIndexFromSectionY(sp.y());
            if (idx < 0 || idx >= chunk.getSectionsCount()) continue;
            pipe.handleChunkSectionAddition(chunk.getSection(idx), sp.x(), sp.y(), sp.z(), true);
            HullProfiler.COLLIDER_SECTION_REPLAYS.incrementAndGet();
            any = true;
        }
        return any;
    }

    private boolean playerNear(ShipHull hull, double radius) {
        if (radius <= 0) return false;
        AABB box = proximityBox(hull, radius);
        if (box == null) return false;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (box.contains(p.getX(), p.getY(), p.getZ())) return true;
        }
        return false;
    }

    /**
     * Voxel hull when it is built. Otherwise a point box on the logical pose, so a player who logs
     * in beside a ship whose voxels are not ready still wakes it.
     */
    private AABB proximityBox(ShipHull hull, double radius) {
        if (hull.poseReady()) return hull.worldHullAABB().inflate(radius);
        if (!readablePose(hull)) return null;
        var pos = hull.subLevel().logicalPose().position();
        return new AABB(pos.x() - radius, pos.y() - radius, pos.z() - radius,
                pos.x() + radius, pos.y() + radius, pos.z() + radius);
    }

    /** A player who just logged in is not in the end-of-tick scan yet. */
    public void wakeForPlayer(ServerPlayer player) {
        if (player == null || player.isSpectator()) return;
        refreshGroups();
        double radius = HullConfig.wakeRadius();
        for (ParkGroups.Group group : groupsThisTick) {
            boolean hit = false;
            for (ShipHull member : group.members) {
                if (member.mode != HullMode.PARKED && member.mode != HullMode.CHEAP) continue;
                AABB box = proximityBox(member, radius);
                if (box != null && box.contains(player.getX(), player.getY(), player.getZ())) hit = true;
                if (member.mode == HullMode.PARKED && !readablePose(member)) hit = true;
            }
            if (hit) wakeGroup(group.members, "playerNear", level.getGameTime());
        }
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
        hull.groupPinned = false;
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

    /**
     * Before each Rapier substep, put pinned parked bodies back on their parked pose with zero
     * velocity. The solver then cannot integrate a joint against a partner that already ran away.
     */
    public void pinParkedBeforeStep() {
        if (!phase6() || !globallyEnabled()) return;
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
        if (phys == null) return;
        PhysicsPipeline pipe = phys.getPipeline();
        if (pipe == null) return;
        for (ShipHull hull : ships.values()) {
            if (!hull.enabled || hull.mode != HullMode.PARKED || hull.subLevel() == null || hull.subLevel().isRemoved()) continue;
            if (hull.groupPinned || !hull.parkedGrounded) pinParked(hull, pipe);
        }
    }

    /**
     * Before the Rapier step, zero a multi-member group that is past the linear cap.
     * The angular cap applies only when the group shares a joint. Touching ships are left
     * to spin. One body is never teleported on its own.
     */
    public void clampJointedBeforeStep() {
        if (!globallyEnabled()) return;
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
        if (phys == null) return;
        PhysicsPipeline pipe = phys.getPipeline();
        if (pipe == null) return;
        List<JointLinks.Edge> edges = JointLinks.forStep();
        double maxLin = HullConfig.absurdSpeed();
        double maxAng = HullConfig.absurdAngular();
        Set<UUID> done = new HashSet<>();
        for (ParkGroups.Group group : groupsThisTick) {
            boolean jointed = anyShares(group.members, edges);
            if (group.members.size() <= 1 && !jointed) continue;
            double ang = jointed ? maxAng : Double.POSITIVE_INFINITY;
            clampBatch(group.members, maxLin, ang, jointed, pipe, done);
        }
        for (JointLinks.Edge edge : edges) {
            ShipHull a = hullOfBody(edge.a);
            ShipHull b = hullOfBody(edge.b);
            if (a == null && b == null) continue;
            List<ShipHull> pair = new ArrayList<>(2);
            if (a != null) pair.add(a);
            if (b != null && (a == null || !a.id.equals(b.id))) pair.add(b);
            clampBatch(pair, maxLin, maxAng, true, pipe, done);
        }
    }

    private void clampBatch(List<ShipHull> batch, double maxLin, double maxAng, boolean jointed,
            PhysicsPipeline pipe, Set<UUID> done) {
        if (!overSpeed(batch, maxLin, maxAng)) return;
        StringBuilder ids = new StringBuilder();
        for (ShipHull member : batch) {
            if (!done.add(member.id)) continue;
            zeroVelocity(member, pipe);
            if (ids.length() > 0) ids.append(',');
            ids.append(member.id);
        }
        if (ids.length() == 0) return;
        if (jointClampLogged.size() >= WARN_ONCE_CAP) jointClampLogged.clear();
        if (jointClampLogged.add(ids.toString())) {
            anomaly("clamped " + (jointed ? "jointed" : "touching") + " velocity group " + ids);
        }
    }

    /** True when this ship is on a joint. Pose restore must not move that body alone. */
    public boolean velocityOnlyJointed(ShipHull hull) {
        if (hull == null) return false;
        List<JointLinks.Edge> edges = JointLinks.snapshot();
        if (shares(hull, edges)) return true;
        for (ShipHull member : groupOf(hull).members) {
            if (shares(member, edges)) return true;
        }
        return false;
    }

    /**
     * Jointed pose guard. Near Sable's remove bounds the whole group moves by one shared Y offset.
     * A group taller than the keep range is stopped, not split. Every other reason only zeroes velocity.
     */
    public void guardJointed(ShipHull hull, String why) {
        if ("remove-bounds".equals(why) && shiftJointedClear(hull)) return;
        zeroJointedGroup(hull, why);
    }

    /**
     * Shift every jointed member by the same Y so the worst vertical span clears the remove margin
     * by one block. Returns true when the group was shifted or when both bounds were hit and the
     * group was only stopped.
     */
    private boolean shiftJointedClear(ShipHull hull) {
        LinkedHashMap<UUID, ShipHull> members = jointedMembers(hull);
        double floor = SableConfig.SUB_LEVEL_REMOVE_MIN.getAsDouble() + HullConfig.poseGuardMargin() + 1.0;
        double ceil = SableConfig.SUB_LEVEL_REMOVE_MAX.getAsDouble() - HullConfig.poseGuardMargin() - 1.0;
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (ShipHull member : members.values()) {
            double[] span = verticalSpan(member);
            if (span == null) continue;
            any = true;
            lowest = Math.min(lowest, span[0]);
            highest = Math.max(highest, span[1]);
        }
        if (!any) return false;
        boolean needUp = lowest < floor;
        boolean needDown = highest > ceil;
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
        PhysicsPipeline pipe = phys == null ? null : phys.getPipeline();
        if (needUp && needDown) {
            for (ShipHull member : members.values()) zeroVelocity(member, pipe);
            if (!hull.poseGuardLogged) {
                hull.poseGuardLogged = true;
                HullProfiler.POSE_RESTORES.incrementAndGet();
                anomaly("jointed group spans both remove bounds; velocity zeroed " + hull.id + " size=" + members.size());
            }
            return true;
        }
        double dy = needUp ? floor - lowest : needDown ? ceil - highest : 0.0;
        if (dy == 0.0) return false;
        for (ShipHull member : members.values()) shiftMember(member, dy, pipe);
        if (!hull.poseGuardLogged) {
            hull.poseGuardLogged = true;
            HullProfiler.POSE_RESTORES.incrementAndGet();
            anomaly("shifted jointed group " + hull.id + " dy=" + dy + " reason=remove-bounds size=" + members.size());
        }
        return true;
    }

    /** World-space vertical span of the hull box, falling back to the logical pose. */
    private static double[] verticalSpan(ShipHull hull) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || sub.isRemoved()) return null;
        double y;
        try {
            Pose3d pose = sub.logicalPose();
            if (pose == null || pose.position() == null) return null;
            y = pose.position().y();
            if (!Double.isFinite(y)) return null;
        } catch (Throwable ignored) {
            return null;
        }
        double minY = y;
        double maxY = y;
        try {
            BoundingBox3dc box = sub.boundingBox();
            if (box != null && Double.isFinite(box.minY()) && Double.isFinite(box.maxY())) {
                minY = Math.min(minY, box.minY());
                maxY = Math.max(maxY, box.maxY());
            }
        } catch (Throwable ignored) {
            // Pose Y is enough when the box cannot be read.
        }
        return new double[] { minY, maxY };
    }

    private void shiftMember(ShipHull member, double dy, PhysicsPipeline pipe) {
        ServerSubLevel sub = member.subLevel();
        if (sub == null || sub.isRemoved()) return;
        Pose3d pose;
        try {
            pose = sub.logicalPose();
        } catch (Throwable ignored) {
            zeroVelocity(member, pipe);
            return;
        }
        if (pose == null || pose.position() == null) {
            zeroVelocity(member, pipe);
            return;
        }
        pose.position().add(0.0, dy, 0.0);
        if (member.hasLastGoodPose) member.lastGoodPos.add(0.0, dy, 0.0);
        if (member.mode == HullMode.PARKED) member.parkedPos.add(0.0, dy, 0.0);
        if (pipe != null && pose.orientation() != null) {
            INTERNAL = true;
            try {
                pipe.teleport(sub, pose.position(), pose.orientation());
            } catch (Throwable t) {
                LOGGER.warn("[shiphull] jointed shift teleport {} failed: {}", member.id, t.toString());
            } finally {
                INTERNAL = false;
            }
        }
        zeroVelocity(member, pipe);
    }

    /** This ship, its park group, and every ship reached through a joint. One joint-list copy. */
    private LinkedHashMap<UUID, ShipHull> jointedMembers(ShipHull hull) {
        LinkedHashMap<UUID, ShipHull> members = new LinkedHashMap<>();
        members.put(hull.id, hull);
        for (ShipHull member : groupOf(hull).members) members.put(member.id, member);
        List<JointLinks.Edge> edges = JointLinks.snapshot();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (JointLinks.Edge edge : edges) {
                ShipHull a = hullOfBody(edge.a);
                ShipHull b = hullOfBody(edge.b);
                boolean hasA = a != null && members.containsKey(a.id);
                boolean hasB = b != null && members.containsKey(b.id);
                if (hasA && b != null && !hasB) {
                    members.put(b.id, b);
                    grew = true;
                }
                if (hasB && a != null && !hasA) {
                    members.put(a.id, a);
                    grew = true;
                }
            }
        }
        return members;
    }

    /** Zero velocity on this ship, its group, and its joint partners. Poses stay where they are. */
    public void zeroJointedGroup(ShipHull hull, String why) {
        LinkedHashMap<UUID, ShipHull> members = jointedMembers(hull);
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
        PhysicsPipeline pipe = phys == null ? null : phys.getPipeline();
        for (ShipHull member : members.values()) zeroVelocity(member, pipe);
        if (!hull.poseGuardLogged) {
            hull.poseGuardLogged = true;
            HullProfiler.POSE_RESTORES.incrementAndGet();
            anomaly("zeroed jointed group velocity " + hull.id + " reason=" + why + " size=" + members.size());
        }
    }

    private boolean sharesConstraint(ShipHull hull) {
        return shares(hull, JointLinks.forStep());
    }

    private static boolean anyShares(List<ShipHull> members, List<JointLinks.Edge> edges) {
        for (ShipHull member : members) {
            if (shares(member, edges)) return true;
        }
        return false;
    }

    private static boolean shares(ShipHull hull, List<JointLinks.Edge> edges) {
        if (hull == null) return false;
        for (JointLinks.Edge edge : edges) {
            if (bodyIs(edge.a, hull.id) || bodyIs(edge.b, hull.id)) return true;
        }
        return false;
    }

    private static boolean bodyIs(PhysicsPipelineBody body, UUID id) {
        return body instanceof ServerSubLevel sub && id.equals(sub.getUniqueId());
    }

    private ShipHull hullOfBody(PhysicsPipelineBody body) {
        if (!(body instanceof ServerSubLevel sub)) return null;
        return ships.get(sub.getUniqueId());
    }

    private static boolean overSpeed(List<ShipHull> batch, double maxLin, double maxAng) {
        for (ShipHull member : batch) {
            ServerSubLevel sub = member.subLevel();
            if (sub == null || sub.isRemoved()) continue;
            if (sub.latestLinearVelocity.length() > maxLin || sub.latestAngularVelocity.length() > maxAng) return true;
        }
        return false;
    }

    private void zeroVelocity(ShipHull hull, PhysicsPipeline pipe) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || sub.isRemoved()) return;
        sub.latestLinearVelocity.zero();
        sub.latestAngularVelocity.zero();
        hull.linearVel.zero();
        hull.angularVel.zero();
        if (pipe == null) return;
        INTERNAL = true;
        try {
            pipe.resetVelocity(sub);
        } catch (Throwable t) {
            LOGGER.debug("[shiphull] reset velocity {}: {}", hull.id, t.toString());
        } finally {
            INTERNAL = false;
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
        hull.groupPinned = false;
        promote(hull, HullMode.FULL, gameTime);
        HullProfiler.WAKES.incrementAndGet();
        LOGGER.debug("[shiphull] woke {} ({})", hull.id, why);
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

    /** Park this ship and every sub-level linked to it. Returns the group size, or 0 if the group is not safe to freeze. */
    public int parkNow(ShipHull hull, long gameTime) {
        if (safeMode()) {
            anomaly("refused to park " + hull.id + "; safe-mode is on");
            return 0;
        }
        refreshGroups();
        ParkGroups.Group group = groupOf(hull);
        if (!group.parkEligible) {
            anomaly("refused to park " + hull.id + "; a joint partner is not a known ship");
            return 0;
        }
        for (ShipHull member : group.members) {
            if (!readablePose(member)) {
                anomaly("refused to park " + hull.id + "; group member " + member.id + " has no pose");
                return 0;
            }
            if (!built(member)) {
                anomaly("refused to park " + hull.id + "; group member " + member.id + " is unbuilt");
                return 0;
            }
            if (badMass(member)) {
                anomaly("refused to park " + hull.id + "; group member " + member.id + " has bad mass");
                return 0;
            }
        }
        parkGroup(group.members, gameTime);
        return group.members.size();
    }

    /** Wake this ship and every sub-level linked to it. Returns the group size. */
    public int wakeNow(ShipHull hull, String why, long gameTime) {
        refreshGroups();
        ParkGroups.Group group = groupOf(hull);
        wakeGroup(group.members, why, gameTime);
        return group.members.size();
    }

    public int groupSize(ShipHull hull) {
        refreshGroups();
        return groupOf(hull).members.size();
    }

    /** One rebuild for {@code /shiphull list}. {@link #cachedGroupSize} reads that result. */
    public void refreshGroupsForList() {
        refreshGroups();
    }

    public int cachedGroupSize(ShipHull hull) {
        return groupOf(hull).members.size();
    }

    public void wakeAllParked(String why) {
        long t = level.getGameTime();
        refreshGroups();
        for (ParkGroups.Group group : groupsThisTick) wakeGroup(group.members, why, t);
    }

    /**
     * Called at the head of the physics tick, before the step. Two long gaps in a row mean the next
     * step would integrate a huge dt against parked bodies, so those bodies are woken first.
     */
    public void notePhysicsGap(long nowNanos) {
        long prev = lastPhysicsNanos;
        lastPhysicsNanos = nowNanos;
        if (prev == 0L) return;
        int limit = HullConfig.lagWakeMillis();
        if (limit <= 0) {
            slowGaps = 0;
            return;
        }
        long gapMs = (nowNanos - prev) / 1_000_000L;
        if (gapMs < limit) {
            slowGaps = 0;
            return;
        }
        // One GC pause is not a lag spike. Wake on the second consecutive slow tick.
        if (++slowGaps < 2) return;
        slowGaps = 0;
        if (nowNanos - lastLagLogNanos > 1_000_000_000L) {
            lastLagLogNanos = nowNanos;
            anomaly("physics tick gap " + gapMs + " ms; waking parked ships");
        }
        wakeAllParked("lagSpike");
    }

    private void refreshGroups() {
        for (ShipHull hull : ships.values()) hull.bumpPose();
        groupsThisTick = ParkGroups.build(ships.values(), HullConfig.groupLinkBlocks());
    }

    private ParkGroups.Group groupOf(ShipHull hull) {
        for (ParkGroups.Group group : groupsThisTick) {
            for (ShipHull member : group.members) {
                if (member.id.equals(hull.id)) return group;
            }
        }
        ParkGroups.Group alone = new ParkGroups.Group();
        alone.members.add(hull);
        return alone;
    }

    /** Freeze or wake each connected group in this tick. Never leave a parked body tied to a live one. */
    private void updateGroups(long gameTime) {
        try {
            if (safeMode()) {
                for (ParkGroups.Group group : groupsThisTick) {
                    boolean anyParked = false;
                    for (ShipHull member : group.members) {
                        if (member.mode == HullMode.PARKED) anyParked = true;
                    }
                    if (anyParked) wakeGroup(group.members, "safeMode", gameTime);
                }
                return;
            }
            for (ParkGroups.Group group : groupsThisTick) {
                if (group.members.isEmpty()) continue;
                String why = wakeWhy(group);
                boolean anyParked = false;
                for (ShipHull member : group.members) {
                    if (member.mode == HullMode.PARKED) anyParked = true;
                }
                if (!HullConfig.parkEnabled() && anyParked) {
                    wakeGroup(group.members, "parkDisabled", gameTime);
                    continue;
                }
                if (why != null) {
                    wakeGroup(group.members, why, gameTime);
                    continue;
                }
                if (anyParked && !group.parkEligible) {
                    wakeGroup(group.members, "unmappedJoint", gameTime);
                    continue;
                }
                if (anyParked && anyUnbuilt(group)) {
                    wakeGroup(group.members, "unbuilt", gameTime);
                    continue;
                }
                // A joiner that is still moving must not be frozen mid-motion. Parked members can
                // leak a little velocity after the pin; that leak is not a wake.
                if (anyParked && movingJoiner(group)) {
                    wakeGroup(group.members, "movingJoiner", gameTime);
                    continue;
                }
                if (anyParked) {
                    parkGroup(group.members, gameTime);
                    continue;
                }
                if (HullConfig.parkEnabled() && canPark(group) && stillLongEnough(group)) {
                    parkGroup(group.members, gameTime);
                }
            }
        } catch (Throwable t) {
            anomaly("group update failed, waking parked ships: " + t);
            wakeAllOnError(gameTime);
        }
    }

    private void wakeAllOnError(long gameTime) {
        List<ShipHull> waking = new ArrayList<>();
        for (ShipHull hull : ships.values()) {
            if (hull.mode == HullMode.PARKED || hull.mode == HullMode.CHEAP) waking.add(hull);
        }
        try {
            if (!waking.isEmpty()) wakeGroup(waking, "groupError", gameTime);
        } catch (Throwable nested) {
            LOGGER.warn("[shiphull] wake after group error failed: {}", nested.toString());
            for (ShipHull hull : waking) {
                try {
                    wake(hull, "groupError", gameTime);
                } catch (Throwable ignored) {
                    // The next tick tries again.
                }
            }
        }
    }

    private String wakeWhy(ParkGroups.Group group) {
        boolean anyParked = false;
        for (ShipHull member : group.members) {
            if (member.mode == HullMode.PARKED) anyParked = true;
        }
        for (ShipHull member : group.members) {
            if (member.wakeRequested) return "impulse:" + member.lastWakeReason;
            if (member.mode == HullMode.PARKED && member.blockChangesThisTick > 0) return "blockChange";
            if (member.blockChangesThisTick >= HullConfig.wakeBlockBurst()) {
                return "blockBurst=" + member.blockChangesThisTick;
            }
            if (member.piloted) return "piloted";
            if (member.mode == HullMode.PARKED && !readablePose(member)) return "unreadablePose";
            if (anyParked && badMass(member)) return "badMass";
            if (playerNear(member, HullConfig.wakeRadius())) return "playerNear";
            if (member.mode == HullMode.REBUILDING) return "rebuilding";
            if (movingContraption(member)) return "contraption";
        }
        String entity = movingEntity(group);
        if (entity != null) return entity;
        return outsideContact(group);
    }

    private boolean canPark(ParkGroups.Group group) {
        if (!group.parkEligible || safeMode()) return false;
        for (ShipHull member : group.members) {
            if (!built(member)) return false;
            if (!isStill(member)) return false;
            if (!readablePose(member)) return false;
            if (badMass(member)) return false;
        }
        return true;
    }

    /** Null, non-finite, or non-positive mass, or a missing center of mass. */
    private boolean badMass(ShipHull hull) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || sub.isRemoved()) return true;
        try {
            MassTracker tracker = sub.getSelfMassTracker();
            if (tracker == null) return true;
            double mass = tracker.getMass();
            if (!Double.isFinite(mass) || mass <= 0.0) return true;
            var com = tracker.getCenterOfMass();
            if (com == null) return true;
            return !Double.isFinite(com.x()) || !Double.isFinite(com.y()) || !Double.isFinite(com.z());
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean built(ShipHull member) {
        return member.poseReady() && member.hullValid;
    }

    private boolean anyUnbuilt(ParkGroups.Group group) {
        for (ShipHull member : group.members) {
            if (!built(member)) return true;
        }
        return false;
    }

    /** True when a ship that is not already parked is still moving. */
    private boolean movingJoiner(ParkGroups.Group group) {
        for (ShipHull member : group.members) {
            if (member.mode == HullMode.PARKED) continue;
            if (!isStill(member)) return true;
        }
        return false;
    }

    private boolean stillLongEnough(ParkGroups.Group group) {
        int need = HullConfig.parkStillTicks();
        for (ShipHull member : group.members) {
            if (member.mode == HullMode.PARKED) continue;
            if (member.stillTicks < need) return false;
        }
        return true;
    }

    private String outsideContact(ParkGroups.Group group) {
        for (ShipHull other : ships.values()) {
            if (group.members.contains(other) || other.mode == HullMode.PARKED || other.subLevel() == null) continue;
            double speed = speedOf(other);
            boolean active = speed > HullConfig.parkMaxSpeed() || other.piloted || other.mode == HullMode.REBUILDING
                    || (other.mode == HullMode.CHEAP && !Double.isNaN(other.routeTarget.x));
            if (!active) continue;
            AABB incoming = contactBox(other, HullConfig.collisionLookahead() + speed);
            if (incoming == null) continue;
            for (ShipHull member : group.members) {
                AABB mine = contactBox(member, 0.0);
                if (mine != null && incoming.intersects(mine)) return "shipNear:" + other.id;
            }
        }
        return null;
    }

    private AABB contactBox(ShipHull hull, double extra) {
        if (hull.poseReady()) return hull.worldHullAABB().inflate(extra);
        if (!readablePose(hull)) return null;
        var pos = hull.subLevel().logicalPose().position();
        double r = Math.max(1.0, extra);
        return new AABB(pos.x() - r, pos.y() - r, pos.z() - r, pos.x() + r, pos.y() + r, pos.z() + r);
    }

    private boolean movingContraption(ShipHull hull) {
        if (hull.contraptionsUnreadable) return true;
        ServerSubLevel sub = hull.subLevel();
        if (sub == null) return false;
        try {
            if (!(sub.getPlot() instanceof dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot plot)) return false;
            var contraptions = plot.getContraptions();
            if (contraptions == null || contraptions.isEmpty()) {
                hull.contraptionSampled = false;
                return false;
            }
            boolean moving = !hull.contraptionSampled;
            hull.contraptionSampled = true;
            for (KinematicContraption contraption : contraptions) {
                if (contraption instanceof net.minecraft.world.entity.Entity entity
                        && entity.getDeltaMovement().lengthSqr() > 1.0e-4) {
                    moving = true;
                }
                if (contraption instanceof ControlledContraptionEntity controlled) {
                    float angle = controlled.getAngle(1.0f);
                    Float previous = hull.contraptionAngles.put(controlled.getId(), angle);
                    if (previous == null || Math.abs(angle - previous) > 0.05f) moving = true;
                }
            }
            return moving;
        } catch (Throwable ignored) {
            hull.contraptionsUnreadable = true;
            return true;
        }
    }

    /**
     * A minecart, train, or belt item moving through a parked (or nearly still) hull. Full ships
     * that are still being piloted are not scanned. One query covers every ship in a 32-block cell,
     * and that result is reused for {@link #ENTITY_SCAN_PERIOD} ticks.
     */
    private String movingEntity(ParkGroups.Group group) {
        prepareMovingEntities();
        for (ShipHull member : group.members) {
            if (movingHits.contains(member.id)) return "movingEntity";
        }
        return null;
    }

    private void prepareMovingEntities() {
        long now = level.getGameTime();
        if (movingScanTick == now) return;
        if (movingScanTick != Long.MIN_VALUE && now > movingScanTick && now - movingScanTick < ENTITY_SCAN_PERIOD) return;
        movingScanTick = now;
        movingHits.clear();
        Map<Long, List<ShipHull>> buckets = new HashMap<>();
        List<ShipHull> alone = new ArrayList<>();
        for (ShipHull hull : ships.values()) {
            if (!hull.enabled || hull.subLevel() == null || hull.subLevel().isRemoved()) continue;
            if (hull.mode != HullMode.PARKED && hull.stillTicks < 10) continue;
            AABB box = contactBox(hull, 1.0);
            if (box == null) continue;
            int x0 = (int) Math.floor(box.minX / ENTITY_CELL);
            int y0 = (int) Math.floor(box.minY / ENTITY_CELL);
            int z0 = (int) Math.floor(box.minZ / ENTITY_CELL);
            int x1 = (int) Math.floor(box.maxX / ENTITY_CELL);
            int y1 = (int) Math.floor(box.maxY / ENTITY_CELL);
            int z1 = (int) Math.floor(box.maxZ / ENTITY_CELL);
            long count = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            if (count <= 0 || count > ENTITY_CELL_CAP) {
                alone.add(hull);
                continue;
            }
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        long key = ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
                        buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(hull);
                    }
                }
            }
        }
        for (List<ShipHull> bucket : buckets.values()) scanEntityBucket(bucket);
        for (ShipHull hull : alone) scanEntityBucket(List.of(hull));
    }

    private void scanEntityBucket(List<ShipHull> bucket) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (ShipHull hull : bucket) {
            AABB box = contactBox(hull, 1.0);
            if (box == null) continue;
            any = true;
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        if (!any) return;
        AABB union = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        try {
            List<Entity> found = level.getEntities((Entity) null, union, entity -> {
                if (entity instanceof Player || !entity.isAlive()) return false;
                return entity.getDeltaMovement().lengthSqr() > 0.0025;
            });
            if (found.isEmpty()) return;
            for (ShipHull hull : bucket) {
                AABB box = contactBox(hull, 1.0);
                if (box == null) continue;
                for (Entity entity : found) {
                    if (box.intersects(entity.getBoundingBox())) {
                        movingHits.add(hull.id);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            if (!entityScanFailedLogged) {
                entityScanFailedLogged = true;
                anomaly("entity scan failed: " + t);
            }
            for (ShipHull hull : bucket) {
                if (hull.mode == HullMode.PARKED) movingHits.add(hull.id);
            }
        }
    }

    private boolean readablePose(ShipHull hull) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || sub.isRemoved()) return false;
        Pose3d pose = sub.logicalPose();
        if (pose == null || pose.position() == null || pose.orientation() == null) return false;
        var pos = pose.position();
        return Double.isFinite(pos.x()) && Double.isFinite(pos.y()) && Double.isFinite(pos.z());
    }

    private void parkGroup(List<ShipHull> members, long gameTime) {
        List<ShipHull> live = new ArrayList<>();
        for (ShipHull member : members) {
            if (member.subLevel() == null || member.subLevel().isRemoved()) continue;
            if (!readablePose(member) || !built(member)) return;
            live.add(member);
        }
        if (live.isEmpty()) return;
        boolean multi = live.size() > 1;
        boolean changed = false;
        for (ShipHull member : live) {
            if (member.mode != HullMode.PARKED || (multi && !member.groupPinned)) changed = true;
        }
        if (!changed) return;
        for (ShipHull member : live) snapshotParkedPose(member);
        if (multi) {
            for (ShipHull member : live) parkPinned(member, gameTime);
        } else {
            for (ShipHull member : live) {
                if (member.mode != HullMode.PARKED) park(member, gameTime);
            }
        }
        HullProfiler.GROUP_PARKS.incrementAndGet();
        LOGGER.info("[shiphull] parked group size={} ids={}", live.size(), groupIds(live));
    }

    private void snapshotParkedPose(ShipHull hull) {
        Pose3d pose = hull.subLevel().logicalPose();
        hull.parkedPos.set(pose.position());
        hull.parkedRot.set(pose.orientation());
    }

    /** Pin every body in a connected group. Colliders stay on so no member is the only soft-slept body. */
    private void parkPinned(ShipHull hull, long gameTime) {
        ServerSubLevel sub = hull.subLevel();
        hull.linearVel.zero();
        hull.angularVel.zero();
        sub.latestLinearVelocity.zero();
        sub.latestAngularVelocity.zero();
        if (hull.collidersDisabled) {
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            if (phys != null) restoreColliders(hull, phys.getPipeline());
        }
        hull.parkedGrounded = true;
        hull.groupPinned = true;
        hull.setMode(HullMode.PARKED, gameTime);
        hull.wakeRequested = false;
        hull.stillTicks = 0;
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
        if (phys != null) pinParked(hull, phys.getPipeline());
        HullProfiler.PARKS.incrementAndGet();
    }

    private void wakeGroup(List<ShipHull> members, String why, long gameTime) {
        boolean multi = members.size() > 1;
        List<ShipHull> changing = new ArrayList<>();
        for (ShipHull member : members) {
            if (member.subLevel() == null || member.subLevel().isRemoved()) continue;
            if (member.mode == HullMode.PARKED || (multi && member.mode == HullMode.CHEAP)) changing.add(member);
        }
        if (changing.isEmpty()) return;
        for (ShipHull member : changing) wake(member, why, gameTime);
        LOGGER.info("[shiphull] woke group size={} why={} ids={}", members.size(), why, groupIds(members));
    }

    private static String groupIds(List<ShipHull> members) {
        StringBuilder ids = new StringBuilder();
        for (ShipHull member : members) {
            if (ids.length() > 0) ids.append(',');
            ids.append(member.id);
        }
        return ids.toString();
    }

    /** A flickering plot-edge chunk must hold this long before it is worth scanning again. */
    private static final long CHUNK_RESCAN_HOLD_TICKS = 40L;

    /**
     * Rebuild a hull that was registered before its plot or mass existed, and again when more of
     * the plot is loaded than the last scan saw. A non-empty voxel set is not enough: a partial
     * scan used to stop here and leave the far end of a long hull out of the group. A chunk count
     * that keeps changing at the plot edge waits until it holds still, so the scan does not repeat
     * on every load and unload.
     */
    private void ensureHull(ShipHull hull) {
        if (level.getGameTime() % 10L != 0L) return;
        int chunks = loadedChunkCount(hull);
        boolean chunkChanged = chunks >= 0 && chunkCountHeld(hull, chunks) && chunks != hull.scannedChunkCount;
        boolean plotChanged = plotBoxChanged(hull);
        boolean smaller = hullSmallerThanPlot(hull) && !plotBoxMatchesScan(hull);
        boolean empty = hull.voxels.isEmpty() && !hull.hullScanSettled;
        if (chunkChanged || plotChanged) hull.hullScanSettled = false;
        if (!empty && !chunkChanged && !plotChanged && !smaller) return;
        if (!scanReady(hull)) return;
        hull.rebuildFromPlot();
        rememberScan(hull, chunks);
        if (!hull.voxels.isEmpty()) {
            if (!hull.hullBuiltLogged) {
                hull.hullBuiltLogged = true;
                LOGGER.info("[shiphull] built hull {} voxels={}", hull.id, hull.voxels.size());
            }
            return;
        }
        if (plotLoaded(hull)) {
            hull.emptyHullScans++;
            if (hull.emptyHullScans >= 5) hull.hullScanSettled = true;
        }
    }

    /** True once {@code chunks} has been the pending count for {@link #CHUNK_RESCAN_HOLD_TICKS}. */
    private boolean chunkCountHeld(ShipHull hull, int chunks) {
        long now = level.getGameTime();
        if (chunks != hull.pendingChunkCount) {
            hull.pendingChunkCount = chunks;
            hull.chunkCountStableSince = now;
            return false;
        }
        return now - hull.chunkCountStableSince >= CHUNK_RESCAN_HOLD_TICKS;
    }

    private int loadedChunkCount(ShipHull hull) {
        try {
            var plot = hull.subLevel().getPlot();
            if (plot == null) return -1;
            var chunks = plot.getLoadedChunks();
            return chunks == null ? -1 : chunks.size();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean plotBoxMatchesScan(ShipHull hull) {
        if (!hull.scannedPlotBox) return false;
        try {
            var plot = hull.subLevel().getPlot();
            var bb = plot == null ? null : plot.getBoundingBox();
            if (bb == null || bb.volume() <= 0) return false;
            return bb.minX() == hull.scannedPlotMinX && bb.minY() == hull.scannedPlotMinY
                    && bb.minZ() == hull.scannedPlotMinZ && bb.maxX() == hull.scannedPlotMaxX
                    && bb.maxY() == hull.scannedPlotMaxY && bb.maxZ() == hull.scannedPlotMaxZ;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean plotBoxChanged(ShipHull hull) {
        return hull.scannedPlotBox && !plotBoxMatchesScan(hull);
    }

    /** True when the voxel hull does not cover the plot box, so a partial scan is still short. */
    private boolean hullSmallerThanPlot(ShipHull hull) {
        if (!hull.hullValid || hull.voxels.isEmpty()) return false;
        try {
            var plot = hull.subLevel().getPlot();
            var bb = plot == null ? null : plot.getBoundingBox();
            if (bb == null || bb.volume() <= 0) return false;
            return hull.minX > bb.minX() || hull.maxX < bb.maxX()
                    || hull.minY > bb.minY() || hull.maxY < bb.maxY()
                    || hull.minZ > bb.minZ() || hull.maxZ < bb.maxZ();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void rememberScan(ShipHull hull, int chunks) {
        if (chunks >= 0) hull.scannedChunkCount = chunks;
        try {
            var plot = hull.subLevel().getPlot();
            var bb = plot == null ? null : plot.getBoundingBox();
            if (bb != null && bb.volume() > 0) {
                hull.scannedPlotBox = true;
                hull.scannedPlotMinX = bb.minX();
                hull.scannedPlotMinY = bb.minY();
                hull.scannedPlotMinZ = bb.minZ();
                hull.scannedPlotMaxX = bb.maxX();
                hull.scannedPlotMaxY = bb.maxY();
                hull.scannedPlotMaxZ = bb.maxZ();
            }
        } catch (Throwable ignored) {
            // The next scan records the box once the plot can be read.
        }
    }

    private boolean scanReady(ShipHull hull) {
        return plotLoaded(hull) || massReady(hull);
    }

    private boolean plotLoaded(ShipHull hull) {
        try {
            var plot = hull.subLevel().getPlot();
            if (plot == null || plot.getBoundingBox() == null || plot.getBoundingBox().volume() <= 0) return false;
            var chunks = plot.getLoadedChunks();
            return chunks != null && !chunks.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean massReady(ShipHull hull) {
        try {
            MassTracker tracker = hull.subLevel().getSelfMassTracker();
            return tracker != null && tracker.getMass() > 0.0 && tracker.getCenterOfMass() != null;
        } catch (Throwable ignored) {
            return false;
        }
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
        return String.format("phase7=%s simplifiedHull=%d massCached=%d shipSync=%s", phase7(), simple, mass, shipSyncEnabled());
    }

    public boolean shipSyncEnabled() {
        if (!globallyEnabled()) return false;
        if (shipSyncOverride != null) return shipSyncOverride;
        return HullConfig.shipSync();
    }

    /**
     * True when this tick should withhold the pose snapshot and plot-entity deltas.
     * Pulse ticks are spread by ship id so ten movers do not all send on the same tick.
     * Does not write {@code lastNetworkedStopped}; the tracking mixin does that before
     * Sable's within-tolerance branch, which is what actually skips the packet.
     */
    public boolean throttlePoseSend(ServerSubLevel sub) {
        if (!shipSyncEnabled() || sub == null || sub.isRemoved()) return false;
        ShipHull hull = ships.get(sub.getUniqueId());
        if (hull == null || !hull.enabled || !hull.poseReady()) return false;
        if (hull.piloted || hull.mode == HullMode.REBUILDING) return false;
        if (playerNear(hull, HullConfig.simplifiedRadius())) return false;
        int period = HullConfig.networkPeriodTicks();
        if (period <= 1) return false;
        int phase = ShipCadence.phaseOf(hull.id, period);
        return Math.floorMod(level.getGameTime(), period) != phase;
    }

    /** Identity match: {@code logicalPose()} returns the sublevel's stable pose field. */
    public ServerSubLevel sublevelForLogicalPose(Pose3d pose) {
        if (pose == null) return null;
        for (ShipHull h : ships.values()) {
            ServerSubLevel sub = h.subLevel();
            if (sub != null && sub.logicalPose() == pose) return sub;
        }
        return null;
    }

    /**
     * Skip {@code ServerEntity.sendChanges} for a non-player on a throttled ship.
     * Sable moves entities that ride the ship to its world pose, so a plot-chunk test misses them.
     * A position inside the throttled hull's world box is skipped, and so is a storage-plot chunk.
     * Players, and anything carrying a player, keep full rate. The chunk decision is cached for the tick.
     */
    public boolean throttleEntitySync(Entity entity) {
        if (!shipSyncEnabled() || entity == null || entity instanceof Player) return false;
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof Player) return false;
        }
        if (insideThrottledHull(entity.getX(), entity.getY(), entity.getZ())) {
            HullProfiler.ENTITY_SYNC_SKIPS.incrementAndGet();
            return true;
        }
        long now = level.getGameTime();
        if (entitySyncCacheTick != now) {
            entitySyncCacheTick = now;
            entitySyncCache.clear();
        }
        long key = entity.chunkPosition().toLong();
        byte cached = entitySyncCache.get(key);
        if (cached == 1) return false;
        if (cached == 2) {
            HullProfiler.ENTITY_SYNC_SKIPS.incrementAndGet();
            return true;
        }
        boolean skip = entityChunkThrottled(entity.chunkPosition());
        entitySyncCache.put(key, (byte) (skip ? 2 : 1));
        if (skip) HullProfiler.ENTITY_SYNC_SKIPS.incrementAndGet();
        return skip;
    }

    private boolean insideThrottledHull(double x, double y, double z) {
        long now = level.getGameTime();
        if (throttledWorldTick != now) {
            throttledWorldTick = now;
            throttledWorldBoxes.clear();
            if (!shipSyncEnabled()) return false;
            for (ShipHull hull : ships.values()) {
                ServerSubLevel sub = hull.subLevel();
                if (sub != null && hull.poseReady() && throttlePoseSend(sub)) {
                    throttledWorldBoxes.add(hull.worldHullAABB().inflate(2.0));
                }
            }
        }
        for (AABB box : throttledWorldBoxes) {
            if (box.contains(x, y, z)) return true;
        }
        return false;
    }

    private boolean entityChunkThrottled(ChunkPos chunk) {
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null || !container.inBounds(chunk)) return false;
            LevelPlot plot = container.getPlot(chunk);
            if (plot == null) return false;
            if (!(plot.getSubLevel() instanceof ServerSubLevel sub) || sub.isRemoved()) return false;
            return throttlePoseSend(sub);
        } catch (Throwable t) {
            return false;
        }
    }

    public String phase8Status() {
        return String.format("phase8=%s coastedThisTick=%s coastEvery=%d distantSolver=%s solverIters=%d",
                phase8(), coastedThisTick, HullConfig.coastRealStepEvery(),
                HullConfig.distantSolver(), HullConfig.distantSolverIterations());
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
                // A connected group is pinned every step so a joint cannot walk one body off the others.
                if (stepRanSinceSync) {
                    if (hull.groupPinned || !hull.parkedGrounded) pinParked(hull, pipe);
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
        if (phase6() && globallyEnabled()) {
            flushPendingGroups();
            refreshGroups();
        }

        for (ShipHull hull : ships.values()) {
            if (hull.subLevel() != null && !hull.subLevel().isRemoved() && hull.subLevel().getLevel() != level) {
                anomaly("ship " + hull.id + " left this level");
                onSubLevelRemoved(hull.subLevel());
                continue;
            }
            if (!hull.enabled || hull.subLevel() == null || hull.subLevel().isRemoved()) continue;
            // Rapier may have written a new pose since the cached world AABB.
            hull.bumpPose();
            if (coastedThisTick && hull.mode == HullMode.FULL) integrateCoast(hull);
            updatePiloted(hull);
            updateMode(hull, gameTime);
            if (globallyEnabled()) ensureHull(hull);
        }
        if (phase6() && globallyEnabled()) updateGroups(gameTime);
        for (ShipHull hull : ships.values()) {
            if (!hull.enabled || hull.subLevel() == null || hull.subLevel().isRemoved()) continue;
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
        coastedThisTick = false;
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
        if (hull.mode == HullMode.PARKED) return; // the group pass wakes a parked ship
        if (phase6() && HullConfig.parkEnabled()) {
            if (isStill(hull)) hull.stillTicks++; else hull.stillTicks = 0;
        }
        // A connected group stays in Rapier until the whole group parks. CHEAP would skip forces
        // on one body while a partner is still being solved.
        if (groupOf(hull).members.size() > 1) {
            if (hull.mode == HullMode.CHEAP) promote(hull, HullMode.FULL, gameTime);
            return;
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
            hull.groupPinned = false;
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
        if (parent.subLevel() == null || !parent.poseReady()) return;
        Pose3d parentPose = parent.subLevel().logicalPose();
        if (parentPose.position() == null || parentPose.orientation() == null) return;
        for (NestedAssembly child : parent.nested) {
            ShipHull childHull = ships.get(child.childId);
            if (childHull == null || childHull.subLevel() == null) continue;
            Vector3d world = new Vector3d(child.localPos);
            parentPose.orientation().transform(world);
            world.add(parentPose.position());
            Quaterniond worldRot = new Quaterniond(parentPose.orientation()).mul(child.localRot);
            if (!childHull.poseReady()) continue;
            Pose3d cp = childHull.subLevel().logicalPose();
            if (cp.position() == null || cp.orientation() == null) continue;
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
        if (hull == null || hull.subLevel() == null || hull.subLevel().isRemoved()) return 0;
        ServerSubLevel sub = hull.subLevel();
        LevelPlot plot = sub.getPlot();
        if (plot == null || plot.getEmbeddedLevelAccessor() == null) {
            anomaly("explosion on " + hull.id + " has no plot");
            return 0;
        }
        var accessor = plot.getEmbeddedLevelAccessor();
        Pose3d pose = sub.logicalPose();
        if (pose == null || pose.position() == null || pose.orientation() == null || pose.rotationPoint() == null) return 0;

        // Leave PARKED, and wake partners, before setBlock. onBlockChange would otherwise
        // re-add the Rapier body from inside Sable's own setBlock.
        promote(hull, HullMode.REBUILDING, level.getGameTime());
        try {
            wakeNow(hull, "explosion", level.getGameTime());
        } catch (Throwable t) {
            anomaly("explosion wake failed for " + hull.id + ": " + t);
        }
        sub = hull.subLevel();
        if (sub == null || sub.isRemoved()) {
            anomaly("explosion on " + hull.id + " lost its sub-level before the edit");
            return 0;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongSet removed = new LongOpenHashSet();
        Vector3d tmp = new Vector3d();
        Vector3d rp = pose.rotationPoint();
        double r2 = radius * radius;
        hull.blockEditsCountOnly = true;
        try {
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
        } finally {
            hull.blockEditsCountOnly = false;
        }
        hull.recomputeHullBounds();
        hull.explosionsHandled++;
        HullProfiler.EXPLOSIONS.incrementAndGet();

        sub = hull.subLevel();
        if (sub == null || sub.isRemoved()) {
            anomaly("explosion on " + hull.id + " lost its sub-level during the edit");
            return removed.size();
        }

        List<LongSet> comps = Connectivity.components(hull.voxels);
        if (comps.size() >= 2) {
            // Keep largest on original; spawn note for the rest (console-testable split bookkeeping).
            comps.sort((a, b) -> Integer.compare(b.size(), a.size()));
            LongSet keep = comps.get(0);
            hull.voxels.clear();
            hull.voxels.addAll(keep);
            hull.recomputeHullBounds();
            // Sable leaves userDataTag null until something writes it. The split record is that write.
            if (sub.getUserDataTag() == null) sub.setUserDataTag(new CompoundTag());
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
                sub.getUserDataTag().putLongArray("ash_pending_split_" + i, piece.toLongArray());
            }
            sub.getUserDataTag().putInt("ash_pending_splits", comps.size() - 1);
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
        return String.format("ships=%d cheap=%d parked=%d full=%d rebuilding=%d piloted=%d voxels=%d enabled=%s safeMode=%s forced=%s softSleep=%s bridge=%s demotesSuppressed=%d | %s | %s | %s",
                ships.size(), cheap, parked, full, rebuild, piloted, voxels,
                globallyEnabled(), safeMode(), safeModeForced, cheapSoftSleepEnabled(), ShipCadence.factoryBridgeActive(), demotesSuppressed, phase6Status(), phase7Status(), phase8Status(), HullProfiler.summary());
    }
}
