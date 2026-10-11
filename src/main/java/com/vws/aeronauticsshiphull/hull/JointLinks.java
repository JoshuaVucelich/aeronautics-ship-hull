package com.vws.aeronauticsshiphull.hull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;

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

    public static List<Edge> snapshot() {
        synchronized (EDGES) {
            EDGES.entrySet().removeIf(e -> e.getKey() instanceof PhysicsConstraintHandle handle && !handle.isValid());
            return new ArrayList<>(EDGES.values());
        }
    }
}
