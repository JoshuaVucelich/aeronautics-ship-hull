# Failure scenarios for parked groups

At 05:39:59 UTC on 11 Oct 2026, phase 7 parked three grabber sub-levels that were touching or jointed to airship `26edccc0` and left the airship itself in full physics. One step later Sable removed the airship for an extreme Y range. Group park (`docs/issues/group-park.md`) freezes and wakes a connected set together. This note is the next set of ways that set can still end up half live, deleted, or stuck, and what this build does about each one.

`ship_hull.safeMode` (default false) is the kill switch. While it is on, every parked ship is woken and `parkNow` refuses. `/shiphull safe-mode on` does that immediately and logs `[shiphull] anomaly: safe-mode on`. `/shiphull safe-mode off` forces parking back on until the next restart, even when the config value is true. Anomalies use the prefix `[shiphull] anomaly:`.

Parked mode is not written to the world. A ship that registers does so in FULL.

## What this build changes

| Scenario | What we do |
| --- | --- |
| Chunk unload, or only half the group loaded | Removing a member wakes the rest with `memberUnloaded` before the ship is dropped from the registry. Adding a member wakes a group that already has anyone parked (`memberLoaded`). An unreadable box still links to every ship, so a half-loaded partner cannot stay frozen against a live one. |
| Server crash or kill mid-park | Parked mode is not loaded. Startup logs that ships register in FULL. Each added body gets `pipeline.wakeUp`, so a Sable-saved sleep does not leave the body frozen. |
| Player logs in next to a parked group | Login wakes a parked or cheap ship whose voxel box, or logical pose when the voxels are not ready, contains the player within `wakeRadius`. An unreadable parked pose wakes the group as `unreadablePose`. |
| Group spans a chunk or region border | Linking uses the world bounding box, not the center, and a spatial hash of 32-block cells. A box that crosses cells is inserted into each cell it touches. |
| Ship docked or jointed to another ship | Joints still union the group. An unmapped dynamic partner keeps the group live (`unmappedJoint`). |
| Bearing, drill, or harvester still running | The first non-empty contraption sample counts as moving, and so does a bearing angle with no previous sample. A later angle change above 0.05, or contraption motion, wakes the group (`contraption`). |
| Train, elevator, or belt touching a ship | A non-player entity whose motion is above 0.05 blocks/tick inside a parked hull, or a hull that has already been still for 10 ticks, wakes the group (`movingEntity`). Elevators that are contraptions use the contraption check. |
| Explosion or split during park | Any block edit on a parked ship wakes the group immediately (`blockChange`). `/shiphull explode` promotes that ship to REBUILDING and wakes parked partners in the same call. |
| Assemble or disassemble while parked | A new sub-level is `memberLoaded`. Removing one is `memberUnloaded`. Block edits use `blockChange`. |
| Ship crosses a dimension or a portal | A sub-level whose level is no longer this one is removed here, which wakes parked partners. A Sable remove-and-add across dimensions uses the same hooks. |
| TPS lag spike, huge physics step | If the gap since the previous physics tick is at least `ship_hull.lagWakeMillis` (default 250, 0 disables), parked ships are woken at the head of the tick, before that step. The first tick after startup is ignored. The log is at most once a second. |
| Sable bounds, NaN, or zero mass | Pose guard still restores a non-finite pose, an absurd angular speed, a jump-and-speed pair, or a box inside the remove margin. A teleport already inside that margin is not saved, and that refusal is logged once per ship. A parked ship with a missing, non-finite, or non-positive mass, or a bad center of mass, wakes (`badMass`) and cannot be parked. |
| Many ships, O(n²) touch tests | Touch tests run inside shared 32-block cells. Ships that do not share a cell are not compared. A usable box that would cover more than 64 cells is compared with each ship directly and is not unioned with all of them. Four or more unreadable boxes log once. |
| Mixin target missing on a version mismatch | `aeronautics_ship_hull.mixins.json` is `required: false`, so Mixin logs the miss and the game continues. If a park or pose mixin did not apply (`SubLevelPhysicsSystemMixin`, `SubLevelPhysicsStepMixin`, `ServerSubLevelMixin`, `RapierPipelineHooksMixin`, `RapierConstraintLinkMixin`, `RapierConstraintRemoveMixin`), startup turns safe-mode on and does not crash. The apply record lives outside the mixin package. Mixin will not let game code load a class from that package. |

## What is still open

- The physics step that is already running when a player logs in, or when a contraption starts moving, is not replayed. The body is pinned for that step. A block edit and a member load or unload wake before the method returns.
- The long step that already happened is not undone. The lag check wakes ships before the next step.
- A portal or dimension change that does not remove the sub-level and does not change `getLevel()` is not visible here.
- Two FULL ships jointed with anchors a block apart can still fling each other. Group park does not freeze one side of that pair. Bad anchors are a setup failure, not a mixed-park bug.
- An existing `aeronautics_ship_hull-common.toml` keeps keys it already has. `safeMode` and `lagWakeMillis` appear when they are absent. `absurdAngular` stays at the value already in the file (an older file may still say 40).
- A belt with no moving item, and an elevator that is not a contraption and not a moving entity, does not wake the ship.
- A player login was not run headless. The login hook is the same proximity test as the end-of-tick player scan.
- A missing mixin target was not reproduced by deleting Sable. The dedicated server boot is the check that the real targets applied and safe-mode stayed off.
