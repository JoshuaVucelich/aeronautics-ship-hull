package com.vws.aeronauticsshiphull.hull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
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
    private static final Logger LOGGER = LogUtils.getLogger();
    /** World-space cell for the touch test. Ships that do not share a cell are not compared. */
    private static final double CELL = 32.0;
    /** A usable box that covers more cells than this is tested against every ship, not linked to all of them. */
    private static final int MAX_CELLS = 64;
    private static int lastUnusableLogged = -1;

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

        linkTouching(live, parent, rank, linkBlocks);
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
     * Touch tests only ships that share a 32-block cell. An unreadable box still links to every
     * ship, so one partner cannot freeze alone. A usable box that covers too many cells is compared
     * with every ship instead of being unioned with all of them.
     */
    private static void linkTouching(List<ShipHull> live, int[] parent, int[] rank, double linkBlocks) {
        int n = live.size();
        List<Integer> linkAll = new ArrayList<>();
        List<Integer> huge = new ArrayList<>();
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < n; i++) {
            BoundingBox3dc box = sableBox(live.get(i));
            if (!usable(box)) {
                linkAll.add(i);
                continue;
            }
            int x0 = cell(box.minX() - linkBlocks);
            int y0 = cell(box.minY() - linkBlocks);
            int z0 = cell(box.minZ() - linkBlocks);
            int x1 = cell(box.maxX() + linkBlocks);
            int y1 = cell(box.maxY() + linkBlocks);
            int z1 = cell(box.maxZ() + linkBlocks);
            long count = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            if (count <= 0 || count > MAX_CELLS) {
                huge.add(i);
                continue;
            }
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        cells.computeIfAbsent(cellKey(x, y, z), k -> new ArrayList<>()).add(i);
                    }
                }
            }
        }
        Set<Long> seen = new HashSet<>();
        for (List<Integer> bucket : cells.values()) {
            for (int a = 0; a < bucket.size(); a++) {
                for (int b = a + 1; b < bucket.size(); b++) {
                    int i = bucket.get(a);
                    int j = bucket.get(b);
                    if (i == j || !seen.add(pairKey(i, j))) continue;
                    if (near(live.get(i), live.get(j), linkBlocks)) union(parent, rank, i, j);
                }
            }
        }
        for (int i : huge) {
            for (int j = 0; j < n; j++) {
                if (i != j && near(live.get(i), live.get(j), linkBlocks)) union(parent, rank, i, j);
            }
        }
        if (linkAll.size() != lastUnusableLogged && linkAll.size() >= 4) {
            lastUnusableLogged = linkAll.size();
            LOGGER.warn("[shiphull] anomaly: {} ships have unreadable bounds and link to every group", linkAll.size());
        } else if (linkAll.isEmpty()) {
            lastUnusableLogged = -1;
        }
        for (int i : linkAll) {
            for (int j = 0; j < n; j++) {
                if (i != j) union(parent, rank, i, j);
            }
        }
    }

    private static int cell(double value) {
        return (int) Math.floor(value / CELL);
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) | (((long) y & 0x1FFFFFL) << 21) | (((long) z & 0x1FFFFFL) << 42);
    }

    private static long pairKey(int a, int b) {
        int i = Math.min(a, b);
        int j = Math.max(a, b);
        return ((long) i << 32) | (j & 0xffffffffL);
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
