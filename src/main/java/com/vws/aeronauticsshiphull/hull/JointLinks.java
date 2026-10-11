package com.vws.aeronauticsshiphull.hull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/**
 * Joints currently in the Rapier scene. Recorded from {@code addConstraint} and dropped on
 * {@code remove}, because the pipeline does not expose its constraint list.
 */
public final class JointLinks {
    public static final class Edge {
        public final PhysicsPipelineBody a;
        public final PhysicsPipelineBody b;

        Edge(PhysicsPipelineBody a, PhysicsPipelineBody b) {
            this.a = a;
            this.b = b;
        }
    }

    private static final Map<Object, Edge> EDGES = new IdentityHashMap<>();
    /** Body pairs already warned for anchors under a block, or overlapping hulls. */
    private static final Set<String> CLOSE_WARNED = new HashSet<>();
    private static final int CLOSE_WARNED_CAP = 256;
    private static final double CLOSE_ANCHOR_BLOCKS = 1.0;
    /** One copy of the joint list for the physics step. Substeps read this instead of copying again. */
    private static volatile List<Edge> stepEdges;

    private JointLinks() {}

    public static void added(Object handle, PhysicsPipelineBody a, PhysicsPipelineBody b) {
        if (handle == null) return;
        synchronized (EDGES) {
            EDGES.put(handle, new Edge(a, b));
        }
    }

    public static void removed(Object handle) {
        if (handle == null) return;
        synchronized (EDGES) {
            EDGES.remove(handle);
        }
    }

    /**
     * Anchors are plot-local. Warn once per body pair when the world anchors are under about one
     * block apart, or the two hull boxes overlap.
     */
    public static void warnClose(PhysicsPipelineBody a, PhysicsPipelineBody b, PhysicsConstraintConfiguration<?> config) {
        if (!(a instanceof ServerSubLevel sa) || !(b instanceof ServerSubLevel sb)) return;
        UUID idA = sa.getUniqueId();
        UUID idB = sb.getUniqueId();
        if (idA == null || idB == null || idA.equals(idB)) return;
        String key = idA.compareTo(idB) <= 0 ? idA + "|" + idB : idB + "|" + idA;
        synchronized (CLOSE_WARNED) {
            if (CLOSE_WARNED.contains(key)) return;
        }
        boolean close = anchorsClose(sa, sb, config);
        boolean overlap = hullsOverlap(sa, sb);
        if (!close && !overlap) return;
        synchronized (CLOSE_WARNED) {
            if (CLOSE_WARNED.contains(key)) return;
            if (CLOSE_WARNED.size() >= CLOSE_WARNED_CAP) CLOSE_WARNED.clear();
            CLOSE_WARNED.add(key);
        }
        ShipHullManager.anomaly("joint between " + idA + " and " + idB
                + (close ? " anchors<1" : "")
                + (overlap ? " hulls-overlap" : ""));
    }

    private static boolean anchorsClose(ServerSubLevel a, ServerSubLevel b, PhysicsConstraintConfiguration<?> config) {
        Vector3dc p1 = anchorPos(config, true);
        Vector3dc p2 = anchorPos(config, false);
        if (p1 == null || p2 == null) return false;
        Vector3d w1 = worldOf(a, p1);
        Vector3d w2 = worldOf(b, p2);
        if (w1 == null || w2 == null) return false;
        // Adjacent plot blocks are about one block apart. Include that gap.
        double limit = CLOSE_ANCHOR_BLOCKS * CLOSE_ANCHOR_BLOCKS + 1.0e-4;
        return w1.distanceSquared(w2) <= limit;
    }

    private static Vector3dc anchorPos(PhysicsConstraintConfiguration<?> config, boolean first) {
        if (config instanceof RotaryConstraintConfiguration c) return first ? c.pos1() : c.pos2();
        if (config instanceof FixedConstraintConfiguration c) return first ? c.pos1() : c.pos2();
        if (config instanceof FreeConstraintConfiguration c) return first ? c.pos1() : c.pos2();
        if (config instanceof GenericConstraintConfiguration c) return first ? c.pos1() : c.pos2();
        return null;
    }

    private static Vector3d worldOf(ServerSubLevel sub, Vector3dc plotPos) {
        try {
            Pose3d pose = sub.logicalPose();
            if (pose == null || pose.position() == null || pose.orientation() == null) return null;
            Vector3d out = new Vector3d();
            pose.transformPosition(plotPos, out);
            if (!Double.isFinite(out.x) || !Double.isFinite(out.y) || !Double.isFinite(out.z)) return null;
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hullsOverlap(ServerSubLevel a, ServerSubLevel b) {
        try {
            BoundingBox3dc boxA = a.boundingBox();
            BoundingBox3dc boxB = b.boundingBox();
            return boxA != null && boxB != null && boxA.intersects(boxB);
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<Edge> snapshot() {
        synchronized (EDGES) {
            EDGES.entrySet().removeIf(e -> e.getKey() instanceof PhysicsConstraintHandle handle && !handle.isValid());
            return new ArrayList<>(EDGES.values());
        }
    }

    /** Copy the joint list once, at the start of the physics tick. */
    public static void captureForStep() {
        stepEdges = snapshot();
    }

    /** The list captured for this physics step. A call before the first capture copies once and keeps it. */
    public static List<Edge> forStep() {
        List<Edge> edges = stepEdges;
        if (edges != null) return edges;
        edges = snapshot();
        stepEdges = edges;
        return edges;
    }

    /** Drop warn-once keys for a ship that has left the level, so the set cannot grow for the life of the process. */
    public static void forgetShip(UUID id) {
        if (id == null) return;
        String token = id.toString();
        synchronized (CLOSE_WARNED) {
            CLOSE_WARNED.removeIf(key -> key.contains(token));
        }
    }
}
