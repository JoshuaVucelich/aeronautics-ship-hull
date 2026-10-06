package com.vws.aeronauticsshiphull.api;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import com.vws.aeronauticsshiphull.hull.HullMode;

/**
 * Phase 4 emitter. Own API — no compile dep on create_factory_graph.
 * When that mod is loaded, mirrors CHEAP/REBUILDING into FactoryCadence via reflection.
 */
public final class ShipCadence {
    public enum Mode { FULL, CHEAP, REBUILDING }

    private static final Map<UUID, Mode> MODES = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<BiConsumer<UUID, Mode>> LISTENERS = new CopyOnWriteArrayList<>();

    private static final Method FACTORY_SET;
    private static final Class<? extends Enum> FACTORY_MODE;
    static {
        Method set = null;
        Class<? extends Enum> mode = null;
        try {
            Class<?> c = Class.forName("com.vws.createfactorygraph.api.FactoryCadence");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> m = (Class<? extends Enum>) Class.forName("com.vws.createfactorygraph.api.FactoryCadence$Mode");
            mode = m;
            set = c.getMethod("setShipMode", Object.class, mode);
        } catch (Throwable ignored) {}
        FACTORY_SET = set;
        FACTORY_MODE = mode;
    }

    public static void set(UUID shipId, Mode mode) {
        if (mode == Mode.FULL) MODES.remove(shipId);
        else MODES.put(shipId, mode);
        for (BiConsumer<UUID, Mode> l : LISTENERS) {
            try { l.accept(shipId, mode); } catch (Throwable ignored) {}
        }
        bridgeFactory(shipId, mode);
    }

    public static void setFromHull(UUID shipId, HullMode hull) {
        set(shipId, switch (hull) {
            case CHEAP -> Mode.CHEAP;
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
        return FACTORY_SET != null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bridgeFactory(UUID shipId, Mode mode) {
        if (FACTORY_SET == null || FACTORY_MODE == null) return;
        try {
            Object fm = Enum.valueOf(FACTORY_MODE, mode.name());
            FACTORY_SET.invoke(null, shipId, fm);
        } catch (Throwable ignored) {}
    }

    private ShipCadence() {}
}
