# Continuous straight walking on 1.21.1

One matched baseline/candidate pair used the existing flat nearby crafting-table case, an empty inventory, logs 20 blocks away, and the same bounded client settings. Both completed with one server-observed crafting table, full health and idle automation. The baseline is Preview 4 production code with the observation harness; the candidate adds the bounded WALK handoff.

| Observation | Baseline | Candidate |
| --- | --- | --- |
| Eligible straight WALK arrivals | 18 | 18 |
| Zero forward intent at those arrivals | 18 | 0 |
| Positive forward intent at those arrivals | 0 | 18 |
| Client ticks between first and last interior arrival on the initial route | 88 | 74 |
| First server displacement after command | 963 ms | 919 ms |
| Whole command, including mining and crafting | 14,949 ms | 14,612 ms |

The client input observer records each route's identity, consecutive indices, exact heights, directions and empty action payloads. It reads the controller's final input intent after the engine tick. This proves removal of the one-tick input gap on the observed straight WALK edges. The travel interval is between 17 interior arrival observations, not the whole journey. Server displacement is timestamped independently on the server tick. A single pair does not establish a stable wall-clock gain or comparison with another mod.

The handoff advances one reached waypoint, then repeats the normal new-edge validation before issuing outgoing input in the same tick. It excludes turns, changed heights, actions, water, climbables and other primitives. Tick-start input reset remains in place. The [mixed-terrain check](mixed-terrain.json) also passed with the protected course preserved and full health.

[Baseline receipt](baseline.json), [candidate receipt](candidate.json). These are development classes. The walking change is outside Preview 4 and does not address the separate nearby-resource selection failure reported in natural terrain.
