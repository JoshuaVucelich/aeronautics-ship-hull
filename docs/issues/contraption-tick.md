# Skip settled contraption pose uploads and slow parked contraption entities

`RapierPhysicsPipeline.physicsTick` calls `updateContraptionPose` for every kinematic contraption before `Rapier3D.step`. That method allocates velocity vectors and may call native `setKinematicContraptionTransform`. Parked ships are not moving, so the upload is wasted whenever any other ship still forces a real step.

`ServerSubLevel.tick` also walks a splitting heat map for every ship, including dormant ones.

Create's `AbstractContraptionEntity.tick` runs storage, actors, and passenger positioning every tick. Sable's chunk map forces ship plot chunks to stay in entity-ticking range, so those entities tick even when the ship is parked far from players.

## Change

- Skip the pose upload when the contraption sits on a PARKED ship, or on a CHEAP ship whose linear and angular velocity are ~0.
- Skip the heat-map tick for CHEAP and PARKED ships.
- On a PARKED ship with no player inside the wake radius and no player passenger, tick the contraption entity every 4 ticks.

`/shiphull contraptionTick off|on` is the A/B switch.

## Parity

A moving or piloted ship still uploads poses and ticks entities every tick. A player riding a parked ship, or standing within 16 blocks, restores every-tick entity updates. Machines on an unattended parked ship advance at 5 Hz instead of 20 Hz. That rate change needs an in-game look. Pose skip does not move the contraption relative to the ship. Items stay in the plot.

## Not in this change

Rapier stepping, collider rebuilds, vanilla block-entity ticks, and network pose packets.
