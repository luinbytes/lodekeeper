# Movement work

A nearby target should produce movement promptly, and ordinary travel should keep making progress. Search CPU, waiting for a route, travel, mining and station use are separate costs. A faster search cannot compensate for a controller that stops at every waypoint.

## Research

We inspected Baritone's 1.21.11 source and its current [26.3 release](https://github.com/cabaletta/baritone/releases/tag/v1.20.0), pinned to commit `25111daedf1d59e6a8dfb5a3e61885cdb8d953df`. A targeted comparison found identical movement primitives and costs between those versions; selected executor/cache differences concern API access. This is architectural research, not a gameplay benchmark. Lodekeeper keeps its own implementation and has no Baritone dependency.

[Traverse execution](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/movement/movements/MovementTraverse.java) and the [path executor](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/path/PathExecutor.java) maintain sprinting across compatible moves and recognize safe overshoot. Preview 4 releases forward input for a tick at each completed waypoint. Current development code revalidates and drives the next straight, exact-level, action-free WALK edge in the same tick. The [1.21.1 paired observation](evidence/1.21.1-continuous-walk-development/README.md) recorded 18 zero-input arrivals before the change and none afterward. Ordinary sprinting remains a separate experiment.

[Parkour](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/movement/movements/MovementParkour.java) distinguishes preparation and running, checks space beyond the landing, and times takeoff. [Falls](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/movement/movements/MovementFall.java) distinguish a cancellable departure from active flight. These suggest explicit movement phases rather than applying launch conditions throughout a maneuver.

[Movement costs](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/api/java/baritone/api/pathing/movement/ActionCosts.java) estimate ticks. Lodekeeper's current weights are relative path units. They need calibration against measured execution before claiming that a chosen route minimizes completion time.

## Implementation sequence

| Unit | Design | Acceptance |
| --- | --- | --- |
| Continuous walking | Keep input across bounded, prevalidated collinear WALK edges. Stop continuity at turns, height changes, actions or media changes. Permit sprint only with adequate food and a proven stopping corridor. | Same destinations, full health and protected blocks; fewer idle ticks and faster paired travel cases. |
| Movement phases | Represent launch, departure, flight and landing separately. Preserve full launch proofs; verify the current body and safe landing during travel. Goal changes can wait for a stable boundary; explicit stop always releases controls. | Dedicated drop and jump courses, changed terrain, knockback, pause and stop. |
| Parkour preparation | Use actual velocity for run-up, takeoff and landing braking. Certify launch clearance, flight envelope and room after landing. | Separate one-, two- and three-block gap fixtures; no claim from planner-only tests. |
| Duration costs | Record ticks and CPU separately for each primitive, action and transition. Price current tools and movement capabilities. | Equivalent routes and reproducible measurements before changing weights. |
| Planning ahead | Calculate the next useful segment while executing the current one. Worker access requires an immutable, bounded terrain snapshot and revision-checked handoff. | No live-world worker reads, bounded memory, no stale path execution, measured command latency. |

The delivered DROP correction distinguishes launch from actual departure. Launch requires live full support on every tick, including when the terrain revision is unchanged. After measured departure, the controller keeps its body, hazard, landing and corridor checks without replaying a launch requirement. Live velocity guides braking; reverse input needs a clear rear sweep within the same corridor. The [dedicated 1.21.1 platform check](evidence/1.21.1-drop-departure-development/README.md) passed with a completed validated DROP, full health and all nine platform blocks preserved. It covers an ordinary-friction one-block drop, not every fall or parkour maneuver.

The next movement changes must preserve the current collision-shape model, sixteenth-block ground heights, exact action reservations and unknown-terrain blocking. A coarse [chunk cache](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/utils/BlockStateInterface.java) cannot replace those proofs. Comparison with either competitor remains unmeasured.

## Settled launch approaches

Preview 5's exact 1.21.1 and 26.3 jars failed the meadow acquisition fixture at the same one-block ledge. The player reached the source near its forward edge, and the native swept-body proof rejected the jump. The failed runs remain recorded separately; Preview 5 was not published as a release.

Preview 6 uses actual horizontal velocity when approaching a WALK waypoint before JUMP, DROP, PARKOUR or BRIDGE. Arrival requires distance below `.10` and speed below `.01`. Vanilla corrective input and its idle coast must retain loaded support, body clearance and the path corridor. Strict launch recentering follows verified world actions so it does not interrupt bridge placement's crouched approach. Crouch clearance requires its own zero-input coast proof. Unknown physics or an unsafe coast reports a blocker; the native outgoing trajectory remains unchanged.

Both development meadow repetitions passed from an empty inventory with a finished crafting table and full health. The [1.21.1 run](evidence/1.21.1-preview6-meadow-development/README.md) took 18,074 ms from the command; [26.3](evidence/26.3-preview6-meadow-development/README.md) took 18,806 ms. A bounded recorder samples WALK-to-JUMP handoffs after the engine update and before client physics, preserving position, velocity and ground state. These fixtures do not establish bridge, parkour, modified-friction or natural-world acceptance. Release-jar checks remain separate.
