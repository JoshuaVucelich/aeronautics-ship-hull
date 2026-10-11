# Aeronautics Ship Hull — phases

See `../create-perf-notes-1.21.1.md`.

**Phase 3 (this jar, 1.0.0-phase3):** ship hull overlay on Sable 2.0.5 / Aeronautics 1.3.2.
CHEAP = force-skip + 5–10 Hz hull integrator + teleport/resetVelocity (Rapier `remove()` unsafe).
Phase 4 emitter: `ShipCadence` (+ FactoryCadence reflection bridge).

**Phase 5 (1.0.0-phase5):** Rapier soft-sleep (collider disable) for CHEAP ships + profilers.

**Phase 6 (1.0.0-phase6):** physics cost cuts at the Sable step level (A/B in one jar: `/shiphull phase6 off|on`).
- PARKED mode: a still (≤0.05 m/s for 60 ticks), unpiloted ship with no player within 16 blocks parks.
  Grounded parked ships keep colliders (others can land on them) and are excluded from per-substep work;
  pose drift is checked every ~20 ticks. Airborne parked ships are soft-slept + pinned.
- Idle-scene skip: when no hull needs Rapier, `tickPipelinePhysics` is cancelled (removals still processed),
  with a full step every 40 ticks.
- Distant substeps: if no Rapier ship is piloted/rebuilding/within 64 blocks of a player → 1 substep instead of 2.
- Dormant substep skip: `prePhysicsTickBegin`, `updateMergedMassData`, `updatePose` skipped for CHEAP/PARKED.
- Collider batching: block changes on soft-slept ships are deferred and replayed on wake; ≥8 changes/tick wakes.
- Wake on: impulse/velocity applied, block burst, piloted, player within 16, active ship approaching
  (AABB + lookahead), contact chain from a waking neighbour. Sable CoM teleports are adopted, not waking.
- Not done (needs natives): removing Rapier bodies (`remove()` panics, loses constraints/voxels);
  simplified/merged collider shapes (Rapier voxel collider is native, per-blockstate).

**Phase 7 (1.0.0-phase7):** in-motion cost cuts (`/shiphull phase7 off|on`).
- Simplified hull AABB Rapier collider (setLocalBounds to voxel AABB) for unpiloted FULL ships outside simplifiedRadius
- Cached mass (skip updateMergedMassData) while simplified
- Distant CHEAP integrator slowdown
- Throttled Sable network pose sync for far unpiloted ships
- Skip/defer per-block collider updates while simplified/CHEAP rigid motion
- Does NOT remove Rapier bodies; piloted / near-player / REBUILDING keep full voxels

**Phase 8 (1.0.0-phase8-combined):** Rapier step LOD (`/shiphull phase8 off|on`).
- Separated unpiloted FULL ships (lookahead clear of terrain and other hulls) skip `Rapier3D.step` and coast at the last solved velocity. A real step still runs every 4 ticks so lift, drag, and gravity refresh. Piloted, rebuilding, block-burst, or about-to-collide ships force a real step for the whole scene.
- When a real step runs but no Rapier ship is piloted or within 64 blocks of a player, solver iterations drop from Sable's 18 to 8. Contact generation is unchanged. The original iteration count is restored the tick someone is close.

**Collider cache (1.0.0-phase8-combined):** `/shiphull colliderCache off|on`.
- Hull bounds update incrementally when a block is added or an interior block is removed. A full voxel scan runs only when a block on the current min/max face is removed.
- Interactive-cell classification is cached per block state.
- Waking a ship with at least 48 deferred collider cells replays each dirty 16³ section with one `handleChunkSectionAddition` instead of seven native `changeBlock` calls per cell.
