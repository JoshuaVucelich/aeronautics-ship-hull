# Park connected ships as one group

At 05:39:59 UTC on 11 Oct 2026, phase 7 parked three grabber sub-levels that were touching or jointed to airship `26edccc0`. Parking skips Sable `prePhysicsTick`, `applyQueuedForces`, and `updateMergedMassData` while the Rapier bodies stay in the scene. One physics step later Sable removed the airship for an extreme Y coordinate range. The same session registered every ship with `voxels=0`, so the hull was never built and neighbour checks saw an empty box.

## Change

Idle ships still park. A ship parks or wakes only together with every sub-level touching or linked to it:

- Grabbers and other Aeronautics sub-levels within `groupLinkBlocks` (default 3), nested assemblies, split parents, and Rapier joints.
- Create contraptions on, in, or attached to a ship belong to that ship's group. A moving contraption wakes the group.
- The whole group freezes in one tick, every body pinned, so nothing simulates against a frozen partner.
- The whole group wakes in one tick when any member gets input, a player comes within `wakeRadius`, a block burst lands, a contraption moves, or a moving ship outside the group makes contact.
- An unknown joint partner, or a member with no pose, keeps the group live.

Ships that registered with `voxels=0` rebuild the hull once the plot or the mass tracker is ready. Each tick keeps the last sane pose. A non-finite pose, a jump past `absurdJumpBlocks`, a speed past `absurdSpeed`, or a position inside `poseGuardMargin` of Sable's remove bounds restores that pose, zeroes velocity, and logs it.

`/shiphull mode <uuid> PARKED` and `FULL` apply to the whole group. `/shiphull list` appends `group=<size>`.

## Parity

A lone idle ship still parks on its own. Connected ships stay in Rapier together until every member is still, then freeze together. Waking one member wakes the rest in that tick.
