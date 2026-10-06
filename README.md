# Aeronautics Ship Hull

Phase 6 (1.0.0-phase6) NeoForge mod for Minecraft **1.21.1** — server-side ship hull physics over Sable/Aeronautics.

- Mod id: `aeronautics_ship_hull`
- License: CC0-1.0
- Pins: NeoForge 21.1.256 · Create 6.0.10 · Aeronautics **1.3.2** · Sable **2.0.5**
- Separate jar from `create_factory_graph` (Create updates must not force a ship-mod rebuild)

## Behaviour
- Each Sable `ServerSubLevel` = one rigid body + sparse voxel list + interactive cells (chest/seat/bearing/engine)
- Unpiloted ships: CHEAP mode at 5–10 Hz hull AABB collision; diverted out of Rapier
- Full Rapier only when piloted (still one body), imminent collision, or short REBUILDING window after explosion
- Explosion deletes voxels, rebuilds hull, splits connectivity (no per-block ticking)
- Bearing/crane: `/shiphull nest` parent←child local offset
- Phase 4 emitter: `ShipCadence` (+ optional reflection bridge into `FactoryCadence`)
- Phase 5: Rapier soft-sleep (colliders off) for CHEAP ships
- Phase 6: PARKED mode + Sable step gating (see PHASES.md)

## Commands
`/shiphull status|enable|disable|list|profile|sync|spawn|rebuild|mode|inspect|lookup|explode|route|nest|phase6 [on|off]|items <uuid>`

## Build
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew build
```
