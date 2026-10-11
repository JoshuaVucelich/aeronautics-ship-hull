package com.vws.aeronauticsshiphull.hull;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.vws.aeronauticsshiphull.config.HullConfig;
import com.vws.aeronauticsshiphull.profile.HullProfiler;

import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

/**
 * Last-good pose. Runs at the start of {@code ServerSubLevel.tick}, before Sable deletes a
 * sub-level whose bounding box has left the keep range.
 */
public final class PoseGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double ABSURD_ANGULAR = 20.0;

    private PoseGuard() {}

    public static void beforeTick(ServerSubLevel sub) {
        try {
            if (sub == null || sub.isRemoved()) return;
            ShipHullManager mgr = ShipHullManager.getOrNull(sub.getLevel());
            if (mgr == null) return;
            ShipHull hull = mgr.get(sub);
            if (hull == null || !hull.enabled) return;
            String why = unsafe(hull, sub);
            if (why != null) restore(hull, sub, why);
        } catch (Throwable t) {
            LOGGER.warn("[shiphull] pose guard failed: {}", t.toString());
        }
    }

    public static void afterTick(ServerSubLevel sub) {
        try {
            if (sub == null || sub.isRemoved()) return;
            ShipHullManager mgr = ShipHullManager.getOrNull(sub.getLevel());
            if (mgr == null) return;
            ShipHull hull = mgr.get(sub);
            if (hull == null || !hull.enabled) return;
            if (unsafe(hull, sub) != null) {
                hull.poseGuardStableTicks = 0;
                return;
            }
            Pose3d pose = sub.logicalPose();
            hull.lastGoodPos.set(pose.position());
            hull.lastGoodRot.set(pose.orientation());
            hull.hasLastGoodPose = true;
            if (++hull.poseGuardStableTicks > 20) hull.poseGuardLogged = false;
        } catch (Throwable ignored) {
            // A failed read must not take the ship down. The next tick tries again.
        }
    }

    private static String unsafe(ShipHull hull, ServerSubLevel sub) {
        Pose3d pose = sub.logicalPose();
        if (pose == null || pose.position() == null || pose.orientation() == null) return "missing-pose";
        Vector3d pos = pose.position();
        if (!finite(pos) || !finite(pose.orientation())
                || !finite(sub.latestLinearVelocity) || !finite(sub.latestAngularVelocity)) {
            return "non-finite";
        }
        if (sub.latestLinearVelocity.length() > HullConfig.absurdSpeed()) return "speed";
        if (sub.latestAngularVelocity.length() > ABSURD_ANGULAR) return "angular";
        if (hull.hasLastGoodPose && hull.lastGoodPos.distance(pos) > HullConfig.absurdJumpBlocks()) return "jump";
        if (approachingRemove(sub, pos.y())) return "remove-bounds";
        return null;
    }

    private static boolean approachingRemove(ServerSubLevel sub, double y) {
        double min = SableConfig.SUB_LEVEL_REMOVE_MIN.getAsDouble();
        double max = SableConfig.SUB_LEVEL_REMOVE_MAX.getAsDouble();
        double margin = HullConfig.poseGuardMargin();
        double minY = y;
        double maxY = y;
        try {
            BoundingBox3dc box = sub.boundingBox();
            if (box != null) {
                minY = Math.min(minY, box.minY());
                maxY = Math.max(maxY, box.maxY());
            }
        } catch (Throwable ignored) {
            // Pose Y is enough when the box cannot be read.
        }
        return minY < min + margin || maxY > max - margin;
    }

    private static void restore(ShipHull hull, ServerSubLevel sub, String why) {
        Pose3d pose = sub.logicalPose();
        if (pose == null) return;
        if (pose.position() == null) return;
        double min = SableConfig.SUB_LEVEL_REMOVE_MIN.getAsDouble();
        double max = SableConfig.SUB_LEVEL_REMOVE_MAX.getAsDouble();
        double margin = HullConfig.poseGuardMargin();
        double lo = min + margin;
        double hi = max - margin;
        if (hull.hasLastGoodPose) {
            pose.position().set(hull.lastGoodPos);
            if (pose.orientation() != null) pose.orientation().set(hull.lastGoodRot);
        } else {
            if (!finite(pose.position())) pose.position().set(0.0, 64.0, 0.0);
            double y = pose.position().y();
            if (y < lo || y > hi || !Double.isFinite(y)) pose.position().y = Math.min(hi, Math.max(lo, Double.isFinite(y) ? y : 64.0));
            if (pose.orientation() == null || !finite(pose.orientation())) {
                if (pose.orientation() != null) pose.orientation().identity();
            }
        }
        sub.latestLinearVelocity.zero();
        sub.latestAngularVelocity.zero();
        hull.linearVel.zero();
        hull.angularVel.zero();
        // The quiet counter must start over. Leaving it above the clear threshold
        // made the following afterTick drop the log latch and warn again next tick.
        hull.poseGuardStableTicks = 0;
        SubLevelPhysicsSystem phys = SubLevelPhysicsSystem.get(hull.level);
        PhysicsPipeline pipe = phys == null ? null : phys.getPipeline();
        if (pipe != null && pose.orientation() != null) {
            ShipHullManager.INTERNAL = true;
            try {
                pipe.teleport(sub, pose.position(), pose.orientation());
                pipe.resetVelocity(sub);
            } catch (Throwable t) {
                LOGGER.warn("[shiphull] pose restore teleport {} failed: {}", hull.id, t.toString());
            } finally {
                ShipHullManager.INTERNAL = false;
            }
        }
        if (!hull.poseGuardLogged) {
            hull.poseGuardLogged = true;
            HullProfiler.POSE_RESTORES.incrementAndGet();
            var p = pose.position();
            LOGGER.warn("[shiphull] restored pose {} reason={} pos={},{},{}",
                    hull.id, why, p.x(), p.y(), p.z());
        }
    }

    private static boolean finite(Vector3d v) {
        return v != null && Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }

    private static boolean finite(Quaterniond q) {
        return q != null && Double.isFinite(q.x()) && Double.isFinite(q.y()) && Double.isFinite(q.z()) && Double.isFinite(q.w());
    }
}
