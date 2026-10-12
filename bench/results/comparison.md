# Headless pack comparison

Pack: NeoForge 21.1.233, Minecraft 1.21.1, Create 6.0.10, Sable 2.0.3, Aeronautics 1.3.0, Simulated 1.3.0. Sodium loaded: False.

Ship hull on the mod run: `1.0.0-phase8-hardening.6`.
Baseline ship hull loaded: `False`. Mod run loaded: `True`.

RMS of the second finite difference of position, blocks per tick squared
same-tick (integrated). Desync is Sable's interpolator, not ping.
Moving layout: platforms half-extent 12, centers 27 apart at y=600, stone pad at y=40 so the chunk columns stay loaded, one shared impulse, 200 ticks.
RMS of the second finite difference of (client - server), blocks per tick squared

## parked

| metric | baseline | with mod |
| --- | ---: | ---: |
| MSPT mean | 0.890 | 0.566 |
| MSPT p99 | 2.268 | 5.586 |
| MSPT stddev | 0.600 | 0.847 |
| MSPT max | 9.941 | 6.365 |
| desync mean (blocks) | 0.000 | 0.000 |
| desync RMS (blocks) | 0.000 | 0.000 |
| desync p99 (blocks) | 0.000 | 0.000 |
| MSPT samples | 400 | 400 |
| client jitter RMS | 0.000000 | 0.000000 |
| server jitter RMS | 0.000000 | 0.000000 |
| error jitter RMS | 0.000000 | 0.000000 |
| move packets | 0 | 0 |
| stop packets | 0 | 0 |
| ships removed | 0 | 0 |

Mod-run ship rows:

- `park-0` mode=PARKED removed=False y=80.497..80.497 maxSpeed=0.000 moves=0 stops=0 travel=0.000 path=0.000 throttled=360 pulse=40 gapThrottle=0.000 gapPulse=0.000
- `park-1` mode=PARKED removed=False y=80.497..80.497 maxSpeed=0.000 moves=0 stops=0 travel=0.000 path=0.000 throttled=360 pulse=40 gapThrottle=0.000 gapPulse=0.000
- `park-2` mode=PARKED removed=False y=80.497..80.497 maxSpeed=0.000 moves=0 stops=0 travel=0.000 path=0.000 throttled=360 pulse=40 gapThrottle=0.000 gapPulse=0.000
- `park-3` mode=PARKED removed=False y=80.497..80.497 maxSpeed=0.000 moves=0 stops=0 travel=0.000 path=0.000 throttled=360 pulse=40 gapThrottle=0.000 gapPulse=0.000
- `park-4` mode=PARKED removed=False y=80.497..80.497 maxSpeed=0.000 moves=0 stops=0 travel=0.000 path=0.000 throttled=360 pulse=40 gapThrottle=0.000 gapPulse=0.000
- `park-5` mode=PARKED removed=False y=80.497..80.497 maxSpeed=0.000 moves=0 stops=0 travel=0.000 path=0.000 throttled=360 pulse=40 gapThrottle=0.000 gapPulse=0.000
- `park-6` mode=PARKED removed=False y=80.497..80.497 maxSpeed=0.000 moves=0 stops=0 travel=0.000 path=0.000 throttled=360 pulse=40 gapThrottle=0.000 gapPulse=0.000
- `park-7` mode=PARKED removed=False y=80.497..80.497 maxSpeed=0.000 moves=0 stops=0 travel=0.000 path=0.000 throttled=360 pulse=40 gapThrottle=0.000 gapPulse=0.000

Profiler deltas on the mod run (non-zero):

- IDLE_STEP_SKIPS: 1170
- NETWORK_THROTTLES: 2880
- POSE_SEND_SKIPS: 2880
- RAPIER_PHYSICS_TICK_CALLS: 60
- RAPIER_PHYSICS_TICK_NS: 51687171
- SABLE_TICK_CALLS: 1200
- SABLE_TICK_NS: 64081834

## moving

| metric | baseline | with mod |
| --- | ---: | ---: |
| MSPT mean | 3.086 | 7.120 |
| MSPT p99 | 17.942 | 74.497 |
| MSPT stddev | 5.589 | 26.330 |
| MSPT max | 69.547 | 336.982 |
| desync mean (blocks) | 1.041 | 149.807 |
| desync RMS (blocks) | 1.164 | 196.240 |
| desync p99 (blocks) | 1.800 | 409.676 |
| MSPT samples | 200 | 200 |
| client jitter RMS | 0.018709 | 0.000000 |
| server jitter RMS | 0.018667 | 0.018676 |
| error jitter RMS | 0.000058 | 0.018676 |
| move packets | 800 | 0 |
| stop packets | 0 | 0 |
| ships removed | 0 | 1 |

Mod-run ship rows:

- `move-0` mode=FULL removed=True y=183.084..599.986 maxSpeed=72.514 moves=0 stops=0 travel=416.902 path=416.902 throttled=180 pulse=20 gapThrottle=10018.482 gapPulse=10018.462
- `move-1` mode=FULL removed=False y=183.084..599.986 maxSpeed=72.514 moves=0 stops=0 travel=416.902 path=416.902 throttled=180 pulse=20 gapThrottle=10045.434 gapPulse=10045.368
- `move-2` mode=FULL removed=False y=183.084..599.986 maxSpeed=72.514 moves=0 stops=0 travel=416.902 path=416.902 throttled=180 pulse=20 gapThrottle=10072.386 gapPulse=10072.320
- `move-3` mode=FULL removed=False y=183.084..599.986 maxSpeed=72.514 moves=0 stops=0 travel=416.902 path=416.902 throttled=180 pulse=20 gapThrottle=10099.338 gapPulse=10099.273

Profiler deltas on the mod run (non-zero):

- IDLE_STEP_SKIPS: 390
- NETWORK_THROTTLES: 2160
- POSE_SEND_SKIPS: 2160
- RAPIER_PHYSICS_TICK_CALLS: 221
- RAPIER_PHYSICS_TICK_NS: 189189106
- RAPIER_STEPS: 800
- REDUCED_SUBSTEP_TICKS: 199
- SABLE_TICK_CALLS: 600
- SABLE_TICK_NS: 237327320
- SOLVER_LOD_TICKS: 199

## group

| metric | baseline | with mod |
| --- | ---: | ---: |
| MSPT mean | 3.575 | 6.505 |
| MSPT p99 | 9.038 | 50.414 |
| MSPT stddev | 2.284 | 20.042 |
| MSPT max | 33.067 | 301.453 |
| desync mean (blocks) | 0.096 | 21.570 |
| desync RMS (blocks) | 0.251 | 47.613 |
| desync p99 (blocks) | 1.137 | 141.708 |
| MSPT samples | 300 | 300 |
| client jitter RMS | 0.059356 | 0.000000 |
| server jitter RMS | 0.079478 | 0.965280 |
| error jitter RMS | 0.053298 | 0.965280 |
| move packets | 490 | 0 |
| stop packets | 11 | 1 |
| ships removed | 0 | 0 |

Mod-run ship rows:

- `airship` mode=PARKED removed=False y=80.337..80.499 maxSpeed=6.750 moves=0 stops=0 travel=0.161 path=0.161 throttled=270 pulse=30 gapThrottle=20000.662 gapPulse=20000.662
- `grabber-1` mode=PARKED removed=False y=80.341..86.644 maxSpeed=19.097 moves=0 stops=1 travel=5.908 path=15.911 throttled=270 pulse=30 gapThrottle=3.593 gapPulse=20012.661
- `grabber-2` mode=PARKED removed=False y=80.343..82.940 maxSpeed=12.213 moves=0 stops=0 travel=4.603 path=8.420 throttled=270 pulse=30 gapThrottle=20012.678 gapPulse=20012.681
- `grabber-3` mode=PARKED removed=False y=-59.560..93.035 maxSpeed=98.364 moves=0 stops=0 travel=142.039 path=179.210 throttled=270 pulse=30 gapThrottle=20013.460 gapPulse=20013.587

Profiler deltas on the mod run (non-zero):

- CHEAP_STEPS: 31
- GROUP_PARKS: 4
- IDLE_STEP_SKIPS: 591
- NETWORK_THROTTLES: 4202
- PARKS: 10
- POSE_SEND_SKIPS: 4201
- RAPIER_PHYSICS_TICK_CALLS: 323
- RAPIER_PHYSICS_TICK_NS: 488541054
- RAPIER_STEPS: 1422
- REDUCED_SUBSTEP_TICKS: 295
- SABLE_TICK_CALLS: 900
- SABLE_TICK_NS: 614785953
- SOLVER_LOD_TICKS: 295
- WAKES: 3

