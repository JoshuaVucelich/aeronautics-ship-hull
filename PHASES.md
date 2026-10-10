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

**Phase 8 ship sync (1.0.0-phase8-sync):** pose and plot-entity packets (`/shiphull shipSync off|on`).
- `sendMovementUpdates` compares `logicalPose` to `lastNetworkedPose` and, when they differ, clears
  `lastNetworkedStopped` and queues a MOVE. Phase 7 sets that flag in the post-tick, after tracking,
  so a moving ship still sent every tick.
- On non-pulse ticks the comparison is forced true and the ship is marked stopped first, so Sable
  continues without copying `lastNetworkedPose` or enqueueing a packet. Pulse ticks (default every 10,
  spread by ship id) run the real comparison and send the current pose and velocity.
- `ServerEntity.sendChanges` for non-player entities in that plot uses the same period. Players, and
  any entity carrying a player, stay at full rate. The chunk decision is cached for the tick.
- Piloted, REBUILDING, and ships with a player inside simplifiedRadius (48) stay at full rate.
- Start-tracking full sync and bounds packets are unchanged.
