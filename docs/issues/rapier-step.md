# Rapier step LOD for separated and distant ships

Sable 2.0.5 runs `Rapier3D.step` every tick for every `FULL` ship, with 18 solver iterations (`PhysicsConfigData.solverIterations`). Phase 7 replaced distant voxel bounds with the hull AABB and dropped extra substeps when nobody is near, and `Rapier3D.step` was still the dominant server cost on moving ships.

## Change

- Unpiloted `FULL` ships whose lookahead (hull AABB, inflated by speed over the coast window) misses terrain and other hulls skip `Rapier3D.step` and coast at the last solved velocity. A real step still runs every 4 ticks so lift, drag, and gravity refresh. A piloted ship, a rebuilding ship, a block burst, or any ship about to collide forces a real step for the whole scene, because Rapier steps every body together.
- When a real step runs and no Rapier ship is piloted or within 64 blocks of a player, solver iterations drop from 18 to 8. Contact generation is unchanged. The original count is restored the tick someone is close.

`/shiphull phase8 off|on` is the A/B switch. Defaults live under `ship_hull.phase8`.

## Parity

Ship motion between real steps is constant velocity, so a ship under steady lift matches; a ship that is still accelerating is up to 3 ticks late and is corrected on the real step. Collisions are not skipped: the lookahead covers the coast window, and a hit forces a real step. Items stay in the plot. Piloted ships always step.

## Not in this change

Collider rebuilds, contraption pose uploads, block-entity ticks, and network pose packets.
