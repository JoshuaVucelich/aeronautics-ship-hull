package com.vws.aeronauticsshiphull.api;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.vws.aeronauticsshiphull.config.HullConfig;
import com.vws.aeronauticsshiphull.hull.HullMode;

/**
 * Phase 4 emitter: the ship mod's ONE outward event — a ship's cadence changed to
 * CHEAP (hull integrator at 5-10 Hz), REBUILDING (full Rapier after explosion) or FULL.
 *
 * Own API, no compile dependency on create_factory_graph. When that mod is present the event is
 * mirrored into com.vws.createfactorygraph.api.FactoryCadence by reflection (v2 API with hull
 * period + phase when available, v1 2-arg fallback), and a ship locator
 * (ServerLevel, BlockPos.asLong) -> ship UUID is registered so factory graphs can find their ship.
 * Either jar can be rebuilt/updated (e.g. for a Create update) without rebuilding the other.
 */
public final class ShipCadence {
    private static final Logger LOGGER = LogUtils.getLogger();
    public enum Mode { FULL, CHEAP, REBUILDING }

    private static final Map<UUID, Mode> MODES = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<BiConsumer<UUID, Mode>> LISTENERS = new CopyOnWriteArrayList<>();

    private static final Method FACTORY_SET_V1;
    private static final Method FACTORY_SET_V2;
    private static final Method FACTORY_SET_LOCATOR;
    @SuppressWarnings("rawtypes")
    private static final Class<? extends Enum> FACTORY_MODE;

    public static volatile long eventsFired, cheapEnter, cheapExit, rebuildingEvents, bridgeCalls, bridgeErrors;
    private static final List<String> RECENT = new ArrayList<>();

    static {
        Method v1 = null, v2 = null, loc = null;
        @SuppressWarnings("rawtypes") Class<? extends Enum> mode = null;
        try {
            Class<?> c = Class.forName("com.vws.createfactorygraph.api.FactoryCadence");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> m = (Class<? extends Enum>) Class.forName("com.vws.createfactorygraph.api.FactoryCadence$Mode");
            mode = m;
            v1 = c.getMethod("setShipMode", Object.class, mode);
            try { v2 = c.getMethod("setShipMode", Object.class, mode, int.class, int.class); } catch (NoSuchMethodException ignored) {}
            try { loc = c.getMethod("setShipLocator", BiFunction.class); } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
        FACTORY_SET_V1 = v1;
        FACTORY_SET_V2 = v2;
        FACTORY_SET_LOCATOR = loc;
        FACTORY_MODE = mode;
    }

    /** Called once at common setup. */
    public static void registerFactoryLocator() {
        if (FACTORY_SET_LOCATOR == null) return;
        try {
            BiFunction<Object, Long, Object> f = ShipLocator::locate;
            FACTORY_SET_LOCATOR.invoke(null, f);
            LOGGER.info("[shiphull] registered ship locator with create_factory_graph (cadence API v2)");
        } catch (Throwable t) {
            LOGGER.warn("[shiphull] could not register ship locator: {}", t.toString());
        }
    }

    /** Fire only on real change. */
    public static void set(UUID shipId, Mode mode) {
        Mode prev = MODES.getOrDefault(shipId, Mode.FULL);
        if (prev == mode) return;
        if (mode == Mode.FULL) MODES.remove(shipId);
        else MODES.put(shipId, mode);
        eventsFired++;
        if (mode == Mode.CHEAP) cheapEnter++;
        if (prev == Mode.CHEAP) cheapExit++;
        if (mode == Mode.REBUILDING) rebuildingEvents++;
        int period = period();
        int phase = phaseOf(shipId, period);
        synchronized (RECENT) {
            RECENT.add(System.currentTimeMillis() % 100000000 + " " + shipId + " " + prev + "->" + mode + " period=" + period + " phase=" + phase);
            if (RECENT.size() > 16) RECENT.remove(0);
        }
        LOGGER.debug("[shiphull] cadence {} {} -> {}", shipId, prev, mode);
        for (BiConsumer<UUID, Mode> l : LISTENERS) {
            try { l.accept(shipId, mode); } catch (Throwable ignored) {}
        }
        bridgeFactory(shipId, mode, period, phase);
    }

    public static void setFromHull(UUID shipId, HullMode hull) {
        set(shipId, switch (hull) {
            case CHEAP, PARKED -> Mode.CHEAP;
            case REBUILDING -> Mode.REBUILDING;
            case FULL -> Mode.FULL;
        });
    }

    public static Mode get(UUID shipId) {
        return MODES.getOrDefault(shipId, Mode.FULL);
    }

    public static void addListener(BiConsumer<UUID, Mode> listener) {
        LISTENERS.add(listener);
    }

    public static boolean factoryBridgeActive() {
        return FACTORY_SET_V1 != null;
    }

    public static String bridgeInfo() {
        return "bridge=" + (FACTORY_SET_V2 != null ? "v2" : FACTORY_SET_V1 != null ? "v1" : "none")
                + " locator=" + (FACTORY_SET_LOCATOR != null)
                + " events=" + eventsFired + " cheapEnter=" + cheapEnter + " cheapExit=" + cheapExit
                + " rebuilding=" + rebuildingEvents + " bridgeCalls=" + bridgeCalls + " bridgeErrors=" + bridgeErrors;
    }

    public static List<String> recent() {
        synchronized (RECENT) { return new ArrayList<>(RECENT); }
    }

    /** Hull step phase: ShipHullManager steps CHEAP hulls when gameTime % period == phase. */
    public static int phaseOf(UUID id, int period) {
        return (id.hashCode() & 0x7fffffff) % Math.max(1, period);
    }

    private static int period() {
        try { return HullConfig.cheapPeriodTicks(); } catch (Throwable t) { return 2; }
    }

    /** Server stop: forget modes (factory side resets too). */
    public static void reset() {
        MODES.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bridgeFactory(UUID shipId, Mode mode, int period, int phase) {
        if (FACTORY_SET_V1 == null || FACTORY_MODE == null) return;
        try {
            Object fm = Enum.valueOf(FACTORY_MODE, mode.name());
            if (FACTORY_SET_V2 != null) FACTORY_SET_V2.invoke(null, shipId, fm, period, phase);
            else FACTORY_SET_V1.invoke(null, shipId, fm);
            bridgeCalls++;
        } catch (Throwable t) {
            bridgeErrors++;
            LOGGER.debug("[shiphull] factory bridge failed: {}", t.toString());
        }
    }

    private ShipCadence() {}
}
