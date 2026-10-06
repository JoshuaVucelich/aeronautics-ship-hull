# Aeronautics Ship Hull — phases

See `../create-perf-notes-1.21.1.md`.

**Phase 3 (this jar, 1.0.0-phase3):** ship hull overlay on Sable 2.0.5 / Aeronautics 1.3.2.
CHEAP = force-skip + 5–10 Hz hull integrator + teleport/resetVelocity (Rapier `remove()` unsafe).
Phase 4 emitter: `ShipCadence` (+ FactoryCadence reflection bridge).
