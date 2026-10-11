package com.vws.aeronauticsshiphull.hull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.vws.aeronauticsshiphull.nested.NestedAssembly;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;

/**
 * Connectivity of ships that must park and wake together: touching hulls, nested assemblies,
 * split parents, Rapier joints, and Create contraptions carried by a ship.
 */
public final class ParkGroups {
    /** Joint partner that is not a ship and not the static world. That group stays live. */
    public static final UUID UNKNOWN = new UUID(0L, 1L);

    public static final class Group {
        public final List<ShipHull> members = new ArrayList<>();
        /** False when a joint partner could not be mapped onto a known ship. */
        public boolean parkEligible = true;
    }

    private ParkGroups() {}

    public static List<Group> build(Collection<ShipHull> ships, double linkBlocks) {
        List<ShipHull> live = new ArrayList<>();
        for (ShipHull hull : ships) {
            hull.contraptionsUnreadable = false;
            ServerSubLevel sub = hull.subLevel();
            if (!hull.enabled || sub == null || sub.isRemoved()) continue;
            live.add(hull);
        }
        int n = live.size();
        int[] parent = new int[n];
        int[] rank = new int[n];
        boolean[] blocked = new boolean[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        Map<UUID, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(live.get(i).id, i);

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (near(live.get(i), live.get(j), linkBlocks)) union(parent, rank, i, j);
            }
        }
        for (int i = 0; i < n; i++) {
            ShipHull hull = live.get(i);
            for (NestedAssembly nested : hull.nested) {
                Integer other = index.get(nested.childId);
                if (other != null) union(parent, rank, i, other);
            }
            try {
                UUID splitFrom = hull.subLevel().getSplitFromSubLevel();
                Integer other = splitFrom == null ? null : index.get(splitFrom);
                if (other != null) union(parent, rank, i, other);
            } catch (Throwable ignored) {
                blocked[i] = true;
            }
        }

        Map<KinematicContraption, UUID> owners = new IdentityHashMap<>();
        for (ShipHull hull : live) {
            try {
                if (!(hull.subLevel().getPlot() instanceof ServerLevelPlot plot)) continue;
                for (KinematicContraption contraption : plot.getContraptions()) {
                    if (contraption != null) owners.put(contraption, hull.id);
                }
            } catch (Throwable ignored) {
                hull.contraptionsUnreadable = true;
            }
        }
        for (JointLinks.Edge edge : JointLinks.snapshot()) {
            UUID a = resolve(edge.a, owners, index);
            UUID b = resolve(edge.b, owners, index);
            if (a == null || b == null) continue;
            if (UNKNOWN.equals(a) || UNKNOWN.equals(b)) {
                markBlocked(blocked, index, a);
                markBlocked(blocked, index, b);
                continue;
            }
            Integer ia = index.get(a);
            Integer ib = index.get(b);
            if (ia != null && ib != null) union(parent, rank, ia, ib);
            else {
                if (ia != null) blocked[ia] = true;
                if (ib != null) blocked[ib] = true;
            }
        }

        Map<Integer, Group> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            Group group = groups.computeIfAbsent(root, k -> new Group());
            group.members.add(live.get(i));
        }
        for (int i = 0; i < n; i++) {
            if (!blocked[i]) continue;
            int root = find(parent, i);
            Group group = groups.get(root);
            if (group != null) group.parkEligible = false;
        }
        return new ArrayList<>(groups.values());
    }

    private static void markBlocked(boolean[] blocked, Map<UUID, Integer> index, UUID id) {
        if (id == null || UNKNOWN.equals(id)) return;
        Integer i = index.get(id);
        if (i != null) blocked[i] = true;
    }

    /** Null means the static world, which is not a dynamic partner. */
    private static UUID resolve(PhysicsPipelineBody body, Map<KinematicContraption, UUID> owners, Map<UUID, Integer> index) {
        if (body == null) return null;
        if (body instanceof ServerSubLevel sub) {
            UUID id = sub.getUniqueId();
            return index.containsKey(id) ? id : UNKNOWN;
        }
        if (body instanceof KinematicContraption contraption) {
            UUID owner = owners.get(contraption);
            return owner != null ? owner : UNKNOWN;
        }
        return UNKNOWN;
    }

    /**
     * Touching or nearby in the world, using Sable's world bounding box. A grabber on the end of a
     * long hull is far from the other ship's center and still linked. An unreadable box links too,
     * so one partner cannot freeze on its own.
     */
    private static boolean near(ShipHull a, ShipHull b, double linkBlocks) {
        BoundingBox3dc boxA = sableBox(a);
        BoundingBox3dc boxB = sableBox(b);
        if (!usable(boxA) || !usable(boxB)) return true;
        return !separated(boxA, boxB, linkBlocks);
    }

    private static BoundingBox3dc sableBox(ShipHull hull) {
        ServerSubLevel sub = hull.subLevel();
        if (sub == null || sub.isRemoved()) return null;
        try {
            return sub.boundingBox();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean usable(BoundingBox3dc box) {
        if (box == null) return false;
        if (!Double.isFinite(box.minX()) || !Double.isFinite(box.minY()) || !Double.isFinite(box.minZ())
                || !Double.isFinite(box.maxX()) || !Double.isFinite(box.maxY()) || !Double.isFinite(box.maxZ())) {
            return false;
        }
        if (box.maxX() < box.minX() || box.maxY() < box.minY() || box.maxZ() < box.minZ()) return false;
        return box.volume() > 0.0;
    }

    private static boolean separated(BoundingBox3dc a, BoundingBox3dc b, double gap) {
        return a.maxX() + gap < b.minX() || b.maxX() + gap < a.minX()
                || a.maxY() + gap < b.minY() || b.maxY() + gap < a.minY()
                || a.maxZ() + gap < b.minZ() || b.maxZ() + gap < a.minZ();
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int[] rank, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra == rb) return;
        if (rank[ra] < rank[rb]) parent[ra] = rb;
        else if (rank[ra] > rank[rb]) parent[rb] = ra;
        else {
            parent[rb] = ra;
            rank[ra]++;
        }
    }
}
