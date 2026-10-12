package com.vws.aeronauticsshiphull.hull;

import com.vws.aeronauticsshiphull.config.HullConfig;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * Per-tick cache of "may this plot chunk run its block entities / ship entities".
 * Sable forces plot chunks to stay in ticking range even when the ship is parked far from
 * every player, so the vanilla tick loops still walk them. The decision is per chunk, not per
 * block entity.
 */
public final class ShipTickGate {
    private static long tick = Long.MIN_VALUE;
    /** 1 = run, 2 = skip block entities this tick. */
    private static final Long2ByteOpenHashMap BLOCKS = new Long2ByteOpenHashMap();
    /** 1 = run entities, 2 = skip non-player entities. */
    private static final Long2ByteOpenHashMap ENTITIES = new Long2ByteOpenHashMap();

    private ShipTickGate() {}

    public static boolean allowBlockEntity(ServerLevel level, BlockPos pos) {
        if (!HullConfig.syncSafe() && !ShipHullManager.blockEntityTick()) return true;
        long now = level.getGameTime();
        beginTick(now);
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        byte cached = BLOCKS.get(key);
        if (cached == 1) return true;
        if (cached == 2) return false;
        boolean allow = decide(level, key, now, false);
        BLOCKS.put(key, allow ? (byte) 1 : (byte) 2);
        return allow;
    }

    /** Plot-chunk test, plus the ship's world box for entities Sable has moved to the pose. */
    public static boolean skipEntity(ServerLevel level, Entity entity) {
        if (skipEntity(level, entity.blockPosition())) return true;
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr == null) return false;
        if (HullConfig.syncSafe()) return mgr.untrackedContains(entity.getX(), entity.getY(), entity.getZ());
        return mgr.parkedUnattendedContains(entity.getX(), entity.getY(), entity.getZ());
    }

    public static boolean skipEntity(ServerLevel level, BlockPos pos) {
        if (!HullConfig.syncSafe() && !ShipHullManager.blockEntityTick()) return false;
        long now = level.getGameTime();
        beginTick(now);
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        byte cached = ENTITIES.get(key);
        if (cached == 1) return false;
        if (cached == 2) return true;
        boolean allow = decide(level, key, now, true);
        ENTITIES.put(key, allow ? (byte) 1 : (byte) 2);
        return !allow;
    }

    /** Actors on a parked, unattended ship follow the same period as vanilla block entities. */
    public static boolean allowActor(ServerLevel level, ServerSubLevel sub) {
        if (HullConfig.syncSafe()) {
            return !trackingEmpty(sub);
        }
        if (!ShipHullManager.blockEntityTick()) return true;
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr == null) return true;
        ShipHull hull = mgr.get(sub);
        if (hull == null || !hull.enabled || !hull.poseReady() || hull.mode != HullMode.PARKED) return true;
        if (mgr.isPlayerNear(hull, HullConfig.wakeRadius())) return true;
        int period = HullConfig.blockEntityPeriod();
        if (period <= 1) return true;
        return Math.floorMod(level.getGameTime() + (hull.id.hashCode() & 1023), period) == 0;
    }

    private static boolean trackingEmpty(ServerSubLevel sub) {
        try {
            var players = sub.getTrackingPlayers();
            return players == null || players.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void beginTick(long now) {
        if (now == tick) return;
        tick = now;
        BLOCKS.clear();
        ENTITIES.clear();
    }

    /** @param entities when true, unattended parked chunks skip every tick; block entities keep a period */
    private static boolean decide(ServerLevel level, long chunkKey, long gameTime, boolean entities) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return true;
        LevelPlot plot = container.getPlot(new ChunkPos(chunkKey));
        if (plot == null || !(plot.getSubLevel() instanceof ServerSubLevel sub)) return true;
        ShipHullManager mgr = ShipHullManager.getOrNull(level);
        if (mgr == null) return true;
        ShipHull hull = mgr.get(sub);
        if (HullConfig.syncSafe()) {
            if (hull == null || !hull.enabled || !hull.poseReady()) return true;
            return !trackingEmpty(sub);
        }
        if (hull == null || !hull.enabled || !hull.poseReady() || hull.mode != HullMode.PARKED) return true;
        if (mgr.isPlayerNear(hull, HullConfig.wakeRadius())) return true;
        if (entities) return false;
        int period = HullConfig.blockEntityPeriod();
        if (period <= 1) return true;
        return Math.floorMod(gameTime + (chunkKey & 1023), period) == 0;
    }
}
