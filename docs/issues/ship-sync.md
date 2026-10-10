# Hold pose and plot-entity sync for far unpiloted ships

Sable's `SubLevelTrackingSystem.sendMovementUpdates` sends a MOVE snapshot whenever `logicalPose` is outside tolerance of `lastNetworkedPose`, and that branch sets `lastNetworkedStopped` to false. Phase 7 sets the flag in the level post-tick, which runs after tracking. A ship that is actually moving therefore sends a pose packet every tick. Plot chunks also keep their entities in tracking range, so items and contraptions on the ship run `ServerEntity.sendChanges` every tick for every tracking player.

## Change

- Inside `sendMovementUpdates` only, a far unpiloted ship on a non-pulse tick is reported as within tolerance, and marked stopped first, so Sable continues without copying `lastNetworkedPose` or enqueueing a packet.
- Pulse ticks (default every 10, spread by ship id via `ShipCadence.phaseOf`) call the real comparison and send the current pose and velocity.
- `ServerEntity.sendChanges` for non-player entities whose chunk is that plot uses the same period. The allow/skip decision is cached per chunk for the rest of the tick. Players, and any entity carrying a player, are never skipped.
- Piloted ships, REBUILDING ships, and ships with a player inside `simplifiedRadius` (48) stay at full rate.
- Start-tracking full sync and bounds packets are unchanged.

`/shiphull shipSync off|on` is the A/B switch. Config key `ship_hull.phase8.shipSync`.

## Parity

Ship motion and collisions are unchanged; this does not touch the physics step. A tracking client sees the pose and the plot entities update every `networkPeriodTicks` instead of every tick, so the pose can step between pulses. Items stay in the world; only their network deltas are withheld. A player who walks into range still receives a full sync immediately.

## Not in this change

Rapier stepping, collider rebuilds, contraption pose uploads, and block-entity ticking.
