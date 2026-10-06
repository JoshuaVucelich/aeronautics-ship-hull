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
        hull.onBlockChange(pos, oldSt, newSt);
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
     * CHEAP ships stay registered in Rapier (remove() panics natives on later getLinearVelocity).
     * We instead: skip forces (mixin), integrate pose ourselves, teleport + resetVelocity so the
     * solver sees a kinematic-like body. FULL/REBUILDING leave Rapier in control.
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
            if (hull.mode == HullMode.CHEAP) {
                try {
                    var pose = hull.subLevel().logicalPose();
                    pipe.teleport(hull.subLevel(), pose.position(), pose.orientation());
                    pipe.resetVelocity(hull.subLevel());
                    HullProfiler.RAPIER_BODIES_SKIPPED.incrementAndGet();
                } catch (Throwable t) {
                    LOGGER.debug("[shiphull] cheap sync {}: {}", hull.id, t.toString());
                }
            }
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
            updateMode(hull, gameTime);

            if (globallyEnabled() && hull.mode == HullMode.CHEAP) {
                int period = HullConfig.cheapPeriodTicks();
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
        hull.setMode(mode, gameTime);
        if (mode == HullMode.REBUILDING) {
            hull.rebuildUntilTick = gameTime + HullConfig.rebuildWindowTicks();
        }
        if (!hull.inRapier) {
            SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(level);
            if (phys != null) reenterRapier(hull, phys.getPipeline());
        }
        if (prev == HullMode.CHEAP && mode.usesRapier()) {
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
        int cheap = 0, full = 0, rebuild = 0, piloted = 0, voxels = 0;
        for (ShipHull h : ships.values()) {
            switch (h.mode) {
                case CHEAP -> cheap++;
                case FULL -> full++;
                case REBUILDING -> rebuild++;
            }
            if (h.piloted) piloted++;
            voxels += h.voxels.size();
        }
        return String.format("ships=%d cheap=%d full=%d rebuilding=%d piloted=%d voxels=%d enabled=%s bridge=%s demotesSuppressed=%d | %s",
                ships.size(), cheap, full, rebuild, piloted, voxels,
                globallyEnabled(), ShipCadence.factoryBridgeActive(), demotesSuppressed, HullProfiler.summary());
    }
}
