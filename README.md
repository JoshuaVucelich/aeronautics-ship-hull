# Aeronautics Ship Hull

NeoForge mod for Minecraft 1.21.1. Server-side ship hull physics for Create ships running on Aeronautics and Sable.

This tree is `1.0.0-phase7`. Mod id `aeronautics_ship_hull`. License [CC0 1.0](LICENSE).

Pinned versions:

- NeoForge 21.1.256
- Create 6.0.10
- Aeronautics 1.3.2
- Sable 2.0.5
- Simulated 1.3.2

The jar stays separate from `create_factory_graph`, so a Create update does not force a rebuild of this mod. Older Aeronautics 1.1 to 1.2 and Sable 1.x builds are on `mc/1.21.1-sable1` (`1.0.0-sable1`).

## What it does

Each Sable `ServerSubLevel` is one rigid body, a sparse voxel list, and interactive cells. A cell is a chest, seat, bearing, engine, or other block. Physics is never per block. A piloted ship stays one body.

- **CHEAP.** Unpiloted, and nothing is about to hit it. A hull AABB step runs outside Rapier, every 2 ticks (10 Hz) by default. After 40 quiet ticks the ship leaves full Rapier. Phase 5 soft-sleeps it by clearing Rapier local bounds so the colliders are off. The body stays in the scene. `remove()` panics the Rapier natives, so the mod never removes bodies.
- **PARKED.** Phase 6. Unpiloted, at or under 0.05 blocks/s for 60 ticks, and no player within 16 blocks. A ship resting on terrain or another ship keeps its colliders. An airborne parked ship has colliders off and is pinned so gravity does not drop it. Parked ships wake if you pilot them, apply an impulse, change 8 or more blocks in one tick, come within 16 blocks, or bring a moving ship close. A Sable center-of-mass teleport is copied onto the parked pose and does not count as a wake.
- **FULL.** One Rapier body. Used while piloted, when the hull is within 4 blocks of a solid, or when a player is nearby.
- **REBUILDING.** Full Rapier for 60 ticks after an explosion or a connectivity split. An explosion deletes voxels inside the blast, rebuilds the hull, and enters this mode. If the voxels fall into more than one connected piece, the largest piece stays on the ship and the others are stored as pending splits. It does not spawn a new sublevel, and it does not go back to per-block ticking.

Phase 6 also cuts Sable's own step. If no ship needs Rapier and the scene has no other Sable physics objects, the substep loop is skipped. Removals still run, and one real step still happens every 40 ticks. If Rapier ships exist but none are piloted, rebuilding, or within 64 blocks of a player, Sable runs 1 substep instead of its configured count. CHEAP and PARKED ships skip `prePhysicsTickBegin`, mass merge, and pose readback. Block edits on a soft-slept ship are stored and replayed on wake. Grounded parked ships get a pose check about every 20 ticks.

Phase 7 is on by default. `/shiphull phase7 off` and `on` flip it in game.

- An unpiloted FULL ship farther than 48 blocks from a player uses one AABB Rapier collider instead of per-block voxels.
- Mass is cached while that simplified collider is on, so `updateMergedMassData` is skipped.
- Distant CHEAP ships step every 4 ticks when nobody is within 64 blocks.
- Unpiloted ships farther than 48 blocks send a network pose every 10 ticks.
- Per-block collider updates wait while the hull is simplified or moving in CHEAP.
- Piloted ships, ships near a player, and REBUILDING ships keep full voxel colliders.

`/shiphull nest` pins a child ship to a parent ship with a local offset, for a bearing or crane. Each tick the child pose follows the parent. If the child is in Rapier, it is teleported to match. The two ships stay separate bodies.

`ShipCadence` fires when a ship's mode changes (CHEAP, REBUILDING, or FULL). If `create_factory_graph` is loaded, that event is mirrored into `FactoryCadence` by reflection, and a locator is registered so a factory graph can find its ship. Either jar can be updated on its own.

Numbers above are the config defaults (`ship_hull`, `phase6`, `phase7`). Cost-cut notes are in `PHASES.md`.

## Commands

Needs permission level 2.

- `/shiphull status`, `enable`, `disable`, `list`, `sync`, `cadence`
- `/shiphull profile` and `profile reset`
- `/shiphull spawn <origin> <size>`, `assemble <from> <to>`, `where <pos>`
- `/shiphull rebuild <uuid>`, `inspect <uuid>`, `items <uuid>`
- `/shiphull mode <uuid> <CHEAP|PARKED|FULL|REBUILDING>`
- `/shiphull lookup <uuid> <pos>`
- `/shiphull explode <uuid> <pos> <radius>` or `explode <uuid> here <radius>`
- `/shiphull route <uuid> add|clear|start|impulse ...`
- `/shiphull nest <parent> <child> <lx> <ly> <lz>`
- `/shiphull softsleep on|off`, `phase6 on|off`, `phase7 on|off`

## Build

Java 21.

```bash
./gradlew build
```

Create, Ponder, Flywheel, and Registrate resolve from Maven. Sable 2.0.5 resolves from `maven.ryanhcode.dev`. Aeronautics 1.3.2, Simulated 1.3.2, and the Sable companion and Rapier jars are `compileOnly` files. `build.gradle` expects them under `../create-perf-libs-1.21.1/extracted/`.
