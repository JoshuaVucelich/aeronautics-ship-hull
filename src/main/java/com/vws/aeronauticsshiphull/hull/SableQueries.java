package com.vws.aeronauticsshiphull.hull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3dc;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;

/**
 * Reads Sable contraption position only after the mass tracker exists.
 * Sable 2.0.3 fills {@code sable$massTracker} from an inject partway through
 * {@code AbstractContraptionEntity.tick}. Calling {@code sable$getPosition} before that
 * NPEs on {@code MassTracker.getCenterOfMass()}.
 */
public final class SableQueries {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private SableQueries() {}

    /**
     * Null when the contraption is gone or Sable has not finished mass init.
     * Does not call {@code sable$getPosition} in that case.
     */
    public static Vector3dc contraptionPosition(KinematicContraption contraption) {
        if (contraption == null || !contraption.sable$isValid()) return null;
        MassTracker tracker = contraption.sable$getMassTracker();
        if (tracker == null || tracker.getCenterOfMass() == null) return null;
        return contraption.sable$getPosition();
    }

    /** Log the first failure of each gate, then let the vanilla tick run. */
    public static void failOpen(String where, Throwable error) {
        if (LOGGED.add(where)) {
            LOGGER.warn("[shiphull] {} failed open; falling back to the unthrottled tick", where, error);
        }
    }
}
