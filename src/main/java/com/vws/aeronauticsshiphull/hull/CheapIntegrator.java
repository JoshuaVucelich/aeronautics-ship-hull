package com.vws.aeronauticsshiphull.hull;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.vws.aeronauticsshiphull.config.HullConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/**
 * 5–10 Hz hull integrator for CHEAP ships: Euler step + AABB lookahead vs world solids
 * and other ship hulls. Returns true if an imminent collision requires FULL Rapier.
 */
public final class CheapIntegrator {
    private CheapIntegrator() {}

    public static boolean step(ShipHull hull, ShipHullManager manager, double dt) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || sub.isRemoved() || !hull.poseReady()) return false;
        ServerLevel level = hull.level;
        Pose3d pose = sub.logicalPose();
        if (pose == null || pose.position() == null || pose.orientation() == null) return false;

        // Route seek
        if (!Double.isNaN(hull.routeTarget.x)) {
            Vector3d to = new Vector3d(hull.routeTarget).sub(pose.position());
            double dist = to.length();
            if (dist < 1.5) {
                advanceRoute(hull);
            } else if (dist > 1e-6) {
                to.normalize().mul(Math.min(8.0, dist)); // seek speed up to 8 b/s
                hull.linearVel.lerp(to, 0.15);
            }
        }

        // Gravity (optional; airships default 0)
        hull.linearVel.y += HullConfig.gravity() * dt;

        // Integrate
        Vector3d delta = new Vector3d(hull.linearVel).mul(dt);
        pose.position().add(delta);
        if (hull.angularVel.lengthSquared() > 1e-12) {
            Quaterniond dq = new Quaterniond().rotateXYZ(
                    hull.angularVel.x * dt, hull.angularVel.y * dt, hull.angularVel.z * dt);
            pose.orientation().mul(dq).normalize();
        }
        sub.updateBoundingBox();
        hull.cheapSteps++;

        // Lookahead collision vs world
        AABB world = hull.worldHullAABB().inflate(HullConfig.collisionLookahead());
        Vector3d look = new Vector3d(hull.linearVel).mul(Math.max(dt, 0.1));
        world = world.move(look.x, look.y, look.z);
        if (aabbHitsSolid(level, world)) return true;

        // Other ships
        for (ShipHull other : manager.all()) {
            if (other == hull || !other.hullValid) continue;
            if (world.intersects(other.worldHullAABB().inflate(1.0))) return true;
        }
        return false;
    }

    /** Same lookahead as {@link #step} without integrating: would this hull promote straight back to FULL? */
    public static boolean wouldCollide(ShipHull hull, ShipHullManager manager) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || sub.isRemoved() || !hull.poseReady()) return false;
        double dt = 0.05 * HullConfig.cheapPeriodTicks();
        AABB world = hull.worldHullAABB().inflate(HullConfig.collisionLookahead());
        Vector3d look = new Vector3d(hull.linearVel).mul(Math.max(dt, 0.1));
        world = world.move(look.x, look.y, look.z);
        if (aabbHitsSolid(hull.level, world)) return true;
        for (ShipHull other : manager.all()) {
            if (other == hull || !other.hullValid) continue;
            if (world.intersects(other.worldHullAABB().inflate(1.0))) return true;
        }
        return false;
    }

    private static void advanceRoute(ShipHull hull) {
        if (hull.route.isEmpty()) {
            hull.routeTarget.set(Double.NaN, Double.NaN, Double.NaN);
            return;
        }
        hull.routeIndex = (hull.routeIndex + 1) % hull.route.size();
        Vector3d next = hull.route.get(hull.routeIndex);
        hull.routeTarget.set(next);
    }

    public static boolean aabbHitsSolid(ServerLevel level, AABB box) {
        int x0 = (int) Math.floor(box.minX);
        int y0 = (int) Math.floor(box.minY);
        int z0 = (int) Math.floor(box.minZ);
        int x1 = (int) Math.floor(box.maxX);
        int y1 = (int) Math.floor(box.maxY);
        int z1 = (int) Math.floor(box.maxZ);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Sparse sample: step 2 to keep CHEAP cheap
        for (int x = x0; x <= x1; x += 2) {
            for (int y = y0; y <= y1; y += 2) {
                for (int z = z0; z <= z1; z += 2) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) continue;
                    BlockState st = level.getBlockState(cursor);
                    if (!st.isAir() && st.getCollisionShape(level, cursor).isEmpty() == false) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
