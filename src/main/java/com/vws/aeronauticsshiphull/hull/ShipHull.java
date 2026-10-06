package com.vws.aeronauticsshiphull.hull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.vws.aeronauticsshiphull.api.ShipCadence;
import com.vws.aeronauticsshiphull.nested.NestedAssembly;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;

/**
 * One ship: rigid-body identity (ServerSubLevel) + sparse voxel list + interactive cells.
 * Movement in CHEAP mode uses the hull AABB; interactions resolve in ship-local coords.
 */
public final class ShipHull {
    public final UUID id;
    public final ServerLevel level;

    /** Packed BlockPos.asLong() of solid voxels in ship-local space. */
    public final LongSet voxels = new LongOpenHashSet();
    public final Map<Long, InteractiveCell> interactive = new HashMap<>();
    public final List<NestedAssembly> nested = new ArrayList<>();

    public HullMode mode = HullMode.FULL;
    public boolean piloted;
    public boolean inRapier = true;
    /** Phase 5: CHEAP soft-sleep emptied Rapier local bounds (colliders off). */
    public boolean collidersDisabled = false;
    public boolean enabled = true;
    // Phase 6
    /** Consecutive ticks at rest (speed below parkMaxSpeed). */
    public int stillTicks;
    /** Set from pipeline hooks (impulse/velocity on a parked body); handled in endTick. */
    public volatile boolean wakeRequested;
    public String lastWakeReason = "";
    /** Block changes on this ship during the current tick (explosion-burst wake). */
    public int blockChangesThisTick;
    /** Global plot positions whose Rapier voxel collider update was deferred while colliders were off. */
    public final LongSet pendingColliderBlocks = new LongOpenHashSet();
    /** Frozen pose while PARKED (ships stay put). */
    public final Vector3d parkedPos = new Vector3d();
    public final Quaterniond parkedRot = new Quaterniond();
    /** Parked while resting on terrain/another ship: colliders stay on so Rapier keeps the body asleep. */
    public boolean parkedGrounded;

    /** Local-space axis-aligned hull (block coords). */
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public boolean hullValid;

    public final Vector3d linearVel = new Vector3d();
    public final Vector3d angularVel = new Vector3d();
    public final Vector3d routeTarget = new Vector3d(Double.NaN, Double.NaN, Double.NaN);

    /** Game tick until which we stay in REBUILDING (full Rapier). */
    public long rebuildUntilTick;
    public int quietTicks;
    public int cheapSteps;
    public int rapierSteps;
    public int splitsPerformed;
    public int explosionsHandled;
    public long lastModeChangeTick;

    /** Scripted route waypoints (world positions). */
    public final List<Vector3d> route = new ArrayList<>();
    public int routeIndex;

    private ServerSubLevel subLevel; // may be refreshed

    public ShipHull(ServerSubLevel sub) {
        this.subLevel = sub;
        this.id = sub.getUniqueId();
        this.level = sub.getLevel();
        rebuildFromPlot();
    }

    public ServerSubLevel subLevel() {
        return subLevel;
    }

    public void bind(ServerSubLevel sub) {
        this.subLevel = sub;
    }

    public void rebuildFromPlot() {
        if (subLevel == null || subLevel.isRemoved()) return;
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) return;
        LongSet previous = new LongOpenHashSet(voxels);
        voxels.clear();
        interactive.clear();
        hullValid = false;

        BoundingBox3ic bb = plot.getBoundingBox();
        if (bb == null || bb.volume() <= 0) {
            // Fall back to scanning loaded chunks
            scanLoadedChunks(plot);
        } else {
            minX = bb.minX(); minY = bb.minY(); minZ = bb.minZ();
            maxX = bb.maxX(); maxY = bb.maxY(); maxZ = bb.maxZ();
            var accessor = plot.getEmbeddedLevelAccessor();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        cursor.set(x, y, z);
                        BlockState st = accessor.getBlockState(cursor);
                        if (st.isAir()) continue;
                        long key = BlockPos.asLong(x, y, z);
                        voxels.add(key);
                        InteractiveCell.Kind kind = classify(st, accessor.getBlockEntity(cursor));
                        if (kind != null) {
                            interactive.put(key, new InteractiveCell(cursor.immutable(), kind, st));
                        }
                    }
                }
            }
            hullValid = !voxels.isEmpty();
        }
        if (voxels.isEmpty() && !previous.isEmpty()) {
            voxels.addAll(previous);
        }
        recomputeHullBounds();
    }

    private void scanLoadedChunks(LevelPlot plot) {
        minX = Integer.MAX_VALUE; minY = Integer.MAX_VALUE; minZ = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE; maxY = Integer.MIN_VALUE; maxZ = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int yMin = level.getMinBuildHeight();
        int yMax = level.getMaxBuildHeight() - 1;
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            var chunk = holder.getChunk();
            if (chunk == null) continue;
            var cp = chunk.getPos();
            int x0 = cp.x << 4, z0 = cp.z << 4;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = yMin; y <= yMax; y++) {
                        cursor.set(x0 + lx, y, z0 + lz);
                        BlockState st = chunk.getBlockState(cursor);
                        if (st.isAir()) continue;
                        long key = BlockPos.asLong(cursor.getX(), cursor.getY(), cursor.getZ());
                        voxels.add(key);
                        InteractiveCell.Kind kind = classify(st, chunk.getBlockEntity(cursor));
                        if (kind != null) {
                            interactive.put(key, new InteractiveCell(cursor.immutable(), kind, st));
                        }
                        if (cursor.getX() < minX) minX = cursor.getX();
                        if (cursor.getY() < minY) minY = cursor.getY();
                        if (cursor.getZ() < minZ) minZ = cursor.getZ();
                        if (cursor.getX() > maxX) maxX = cursor.getX();
                        if (cursor.getY() > maxY) maxY = cursor.getY();
                        if (cursor.getZ() > maxZ) maxZ = cursor.getZ();
                    }
                }
            }
        }
        hullValid = !voxels.isEmpty();
    }

    public void recomputeHullBounds() {
        if (voxels.isEmpty()) {
            hullValid = false;
            return;
        }
        minX = Integer.MAX_VALUE; minY = Integer.MAX_VALUE; minZ = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE; maxY = Integer.MIN_VALUE; maxZ = Integer.MIN_VALUE;
        for (long k : voxels) {
            int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        hullValid = true;
    }

    public static InteractiveCell.Kind classify(BlockState st, BlockEntity be) {
        Block b = st.getBlock();
        if (b instanceof ChestBlock || be instanceof ChestBlockEntity) return InteractiveCell.Kind.CHEST;
        String path = st.getBlock().builtInRegistryHolder().key().location().getPath();
        if (path.contains("seat") || path.contains("chair")) return InteractiveCell.Kind.SEAT;
        if (path.contains("bearing") || path.contains("swivel")) return InteractiveCell.Kind.BEARING;
        if (path.contains("engine") || path.contains("propeller") || path.contains("burner")) return InteractiveCell.Kind.ENGINE;
        if (be != null && (path.contains("chest") || path.contains("barrel") || path.contains("shulker"))) {
            return InteractiveCell.Kind.CHEST;
        }
        return null;
    }

    public void onBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
        long key = pos.asLong();
        boolean wasSolid = !oldState.isAir();
        boolean isSolid = !newState.isAir();
        if (wasSolid && !isSolid) {
            voxels.remove(key);
            interactive.remove(key);
        } else if (isSolid) {
            voxels.add(key);
            InteractiveCell.Kind kind = classify(newState, null);
            if (kind != null) interactive.put(key, new InteractiveCell(pos.immutable(), kind, newState));
            else interactive.remove(key);
        }
        recomputeHullBounds();
    }

    public InteractiveCell lookupLocal(BlockPos local) {
        return interactive.get(local.asLong());
    }

    /** World hit → ship-local block pos via inverse pose (approximate: translation only + inverse rot). */
    public BlockPos worldToLocal(double wx, double wy, double wz) {
        Pose3d pose = subLevel.logicalPose();
        Vector3d local = new Vector3d(wx, wy, wz).sub(pose.position());
        Quaterniond inv = new Quaterniond(pose.orientation()).conjugate();
        inv.transform(local);
        // ship plot is already in "local" block space offset by rotation point — use floor
        return BlockPos.containing(local.x + pose.rotationPoint().x(),
                local.y + pose.rotationPoint().y(),
                local.z + pose.rotationPoint().z());
    }

    public AABB localHullAABB() {
        if (!hullValid) return new AABB(0, 0, 0, 1, 1, 1);
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public AABB worldHullAABB() {
        Pose3d pose = subLevel.logicalPose();
        AABB local = localHullAABB();
        // Corner transform for oriented hull → world AABB
        double[] xs = {local.minX, local.maxX};
        double[] ys = {local.minY, local.maxY};
        double[] zs = {local.minZ, local.maxZ};
        double minWx = Double.POSITIVE_INFINITY, minWy = Double.POSITIVE_INFINITY, minWz = Double.POSITIVE_INFINITY;
        double maxWx = Double.NEGATIVE_INFINITY, maxWy = Double.NEGATIVE_INFINITY, maxWz = Double.NEGATIVE_INFINITY;
        Vector3d tmp = new Vector3d();
        Vector3d rp = pose.rotationPoint();
        for (double x : xs) for (double y : ys) for (double z : zs) {
            tmp.set(x - rp.x, y - rp.y, z - rp.z);
            pose.orientation().transform(tmp);
            tmp.add(pose.position());
            if (tmp.x < minWx) minWx = tmp.x;
            if (tmp.y < minWy) minWy = tmp.y;
            if (tmp.z < minWz) minWz = tmp.z;
            if (tmp.x > maxWx) maxWx = tmp.x;
            if (tmp.y > maxWy) maxWy = tmp.y;
            if (tmp.z > maxWz) maxWz = tmp.z;
        }
        return new AABB(minWx, minWy, minWz, maxWx, maxWy, maxWz);
    }

    public void setMode(HullMode next, long gameTime) {
        if (this.mode == next) return;
        this.mode = next;
        this.lastModeChangeTick = gameTime;
        if (next == HullMode.CHEAP) quietTicks = 0;
        ShipCadence.setFromHull(id, next);
    }

    public Map<InteractiveCell.Kind, Integer> interactiveCounts() {
        EnumMap<InteractiveCell.Kind, Integer> m = new EnumMap<>(InteractiveCell.Kind.class);
        for (InteractiveCell c : interactive.values()) {
            m.merge(c.kind(), 1, Integer::sum);
        }
        return m;
    }
}
