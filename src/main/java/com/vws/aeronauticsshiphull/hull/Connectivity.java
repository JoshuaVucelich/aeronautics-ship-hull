package com.vws.aeronauticsshiphull.hull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** BFS connected components over the sparse voxel set (6-connected). */
public final class Connectivity {
    private Connectivity() {}

    public static List<LongSet> components(LongSet voxels) {
        List<LongSet> out = new ArrayList<>();
        LongSet remaining = new LongOpenHashSet(voxels);
        LongArrayList stack = new LongArrayList();
        while (!remaining.isEmpty()) {
            long seed = remaining.iterator().nextLong();
            LongSet comp = new LongOpenHashSet();
            stack.clear();
            stack.add(seed);
            remaining.remove(seed);
            while (!stack.isEmpty()) {
                long cur = stack.removeLong(stack.size() - 1);
                comp.add(cur);
                int x = BlockPos.getX(cur), y = BlockPos.getY(cur), z = BlockPos.getZ(cur);
                for (Direction d : Direction.values()) {
                    long n = BlockPos.asLong(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                    if (remaining.remove(n)) stack.add(n);
                }
            }
            out.add(comp);
        }
        return out;
    }
}
