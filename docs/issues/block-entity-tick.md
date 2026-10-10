# Throttle block entities and freeze stray entities on parked ships

Sable's chunk-map mixin forces every ship plot chunk to report `inEntityTickingRange == true`. Plot chunks live in a storage region far from players, so without that force nothing on the ship would tick. With it, furnaces, hoppers, Create kinetics, and every item entity on a parked ship tick at full rate forever.

## Change

- On a PARKED ship with no player inside the wake radius, vanilla block entities and Sable `BlockEntitySubLevelActor.sable$tick` run every 4 ticks. The allow/skip decision is cached per chunk for the rest of the tick.
- Non-player entities in that plot, and entities Sable has moved onto the ship's world pose, do not tick at all until a player is within the wake radius. Players are never skipped.
- Moving, cheap, rebuilding, and piloted ships are unchanged.

`/shiphull blockEntityTick off|on` is the A/B switch.

## Parity

Items on an unattended parked ship do not fall, do not despawn, and do not get picked up until someone approaches. Hoppers and furnaces on that ship advance at 5 Hz instead of 20 Hz. Both resume at full rate when a player is within 16 blocks. That rate change needs an in-game look.

## Not in this change

Rapier stepping, collider rebuilds, contraption pose uploads, and network pose packets.
