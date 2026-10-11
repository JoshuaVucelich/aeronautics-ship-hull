# Park connected ships as one group

At 05:39:59 UTC on 11 Oct 2026, phase 7 parked three grabber sub-levels that were touching or jointed to airship `26edccc0`. Parking skips Sable `prePhysicsTick`, `applyQueuedForces`, and `updateMergedMassData` while the Rapier bodies stay in the scene. One physics step later Sable removed the airship for an extreme Y coordinate range. The same session registered every ship with `voxels=0`, so the hull was never built and neighbour checks saw an empty box.

## Change

Idle ships still park. A ship parks or wakes only together with every sub-level touching or linked to it:

- Grabbers and other Aeronautics sub-levels whose Sable `boundingBox()` overlaps or comes within `groupLinkBlocks` (default 3). A grabber on the end of a long hull is linked even when the two centers are far apart. An unreadable box links as well, so one partner cannot freeze alone. Nested assemblies, split parents, and Rapier joints link ships regardless of distance.
- Create contraptions on, in, or attached to a ship belong to that ship's group. A moving contraption wakes the group.
- The whole group freezes in one tick, every body pinned, so nothing simulates against a frozen partner.
- The whole group wakes in one tick when any member gets input, a player comes within `wakeRadius`, a block burst lands, a contraption moves, or a moving ship outside the group makes contact.
- An unknown joint partner, a member with no pose, or a member whose hull is not built keeps the group live. A parked group wakes when an unbuilt member joins it.
- A ship that joins an already parked group is parked with it only when that joiner is already still. A moving joiner wakes the group. The 60-tick wait applies to the first park, not to that joiner. `/shiphull mode PARKED` can still freeze a still-built group immediately, and it refuses while any member is unbuilt.

Ships that registered with `voxels=0` rebuild the hull once the plot or the mass tracker is ready. A later scan runs when the plot's loaded chunk count changes and then holds for 40 ticks, or when the voxel hull is smaller than `plot.getBoundingBox()` and that box has not been scanned yet. A chunk that keeps loading and unloading at the plot edge does not rescan on every change. Each tick keeps the last sane pose. A non-finite pose, an angular speed past `absurdAngular` (default 80 rad/s; a 256 RPM bearing is about 26.8 rad/s and two stacked reach about 53.6), a position inside `poseGuardMargin` of Sable's remove bounds, or a jump past `absurdJumpBlocks` together with a speed past `absurdSpeed`, restores that pose, zeroes velocity, and logs it. A jump on its own, or a high speed on its own, stays. Sable 2.0.3 removes a sub-level when its box goes below `sub_level_remove_min` (-10000) or above `sub_level_remove_max` (100000). The 512 margin restores below -9488 or above 99488, still inside the range Sable keeps. An external teleport, including `/tp` and Sable's center-of-mass correction, becomes the new last-good pose. A teleport whose target is already inside that margin is not saved. With no last-good pose the guard leaves the ship alone.

Parked mode is not saved. A reload starts every ship in FULL. Later failure cases (a half-loaded group, a crash mid-park, safe-mode) are in `docs/FAILURE_SCENARIOS.md`.

`/shiphull mode <uuid> PARKED` and `FULL` apply to the whole group. `/shiphull list` builds the groups once per command and appends `group=<size>`.

## Parity

A lone idle ship still parks on its own. Connected ships stay in Rapier together until every member is still, then freeze together. Waking one member wakes the rest in that tick.
