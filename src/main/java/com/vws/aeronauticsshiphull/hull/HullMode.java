package com.vws.aeronauticsshiphull.hull;

/**
 * Physics cadence for a ship hull.
 * CHEAP: unpiloted, no imminent collision — integrate at 5–10 Hz outside Rapier.
 * PARKED (Phase 6): unpiloted and at rest (on terrain or hovering) with no player nearby —
 *   pose frozen, colliders off, skipped by Sable's per-substep loop; wakes on approach/impulse.
 * FULL: in Rapier as one rigid body (still NOT per-block).
 * REBUILDING: short full-Rapier window after explosion / connectivity split.
 */
public enum HullMode {
    CHEAP,
    PARKED,
    FULL,
    REBUILDING;

    public boolean usesRapier() {
        return this == FULL || this == REBUILDING;
    }

    /** Not simulated by Rapier: forces, pre-physics actors, mass merge and pose readback are skipped. */
    public boolean dormant() {
        return this == CHEAP || this == PARKED;
    }
}
