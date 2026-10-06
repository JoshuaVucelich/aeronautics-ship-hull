package com.vws.aeronauticsshiphull.hull;

/**
 * Physics cadence for a ship hull.
 * CHEAP: unpiloted, no imminent collision — integrate at 5–10 Hz outside Rapier.
 * FULL: in Rapier as one rigid body (still NOT per-block).
 * REBUILDING: short full-Rapier window after explosion / connectivity split.
 */
public enum HullMode {
    CHEAP,
    FULL,
    REBUILDING;

    public boolean usesRapier() {
        return this != CHEAP;
    }
}
