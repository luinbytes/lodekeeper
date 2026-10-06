# Native geometry epoch checks

The published Preview 6 jars fail the dynamic-shape repeat check on both 1.21.1 and 26.3. The unpublished candidate jars pass that check and the remaining epoch checks. Each pair uses identical frozen verifier sources, fully loaded fixture blocks and isolated clients. Production classes load from the jar; development production outputs are removed from the classpath.

| Minecraft | Published Preview 6 | Candidate |
| --- | --- | --- |
| 1.21.1 | [Expected cache failure](1211-before/run.json) | [Passed native checks and wood acquisition](1211-candidate/run.json) |
| 26.3 | [Expected cache failure](263-before/run.json) | [Passed native checks and wood acquisition](263-candidate/run.json) |

The test keeps the exact block-state object and changes its native collision shape from a full cube to empty. Registering the first watched shape leaves the revision unchanged. Repeated queries bypass the dynamic-shape cache. An unchanged world tick advance preserves the search; a changed shape makes it stale without accepting a path.

Query-free world, player, sneak, offhand-component and standing-scale changes also advance the revision. Invalid standing dimensions reject native proofs. Watch-capacity exhaustion rejects the overflowing proof and later ordinary probes, makes an existing planner stale, rejects a new planner, and resets only when a new search begins. All 525 fixture cells must be loaded before these checks start.

After the checks, one ordinary wood command runs from an empty inventory. The integrated server observes one log, full health and an idle engine. First movement took 1,480 ms on 1.21.1 and 1,933 ms on 26.3. Completion took 9,943 ms and 10,930 ms. These are single controlled functional runs, not paired speed measurements or natural-world acceptance.

Each directory records jar hashes, frozen verifier hashes and production class origins. [Candidate production source hashes](candidate-production-sources.json) identify the uncommitted source used at run time. Candidate artifacts are unpublished local jars with the Preview 6 development version string; they are not replacements for the immutable public release.

[1.21.1 active route](1211-candidate/active-route.png) and [26.3 active route](263-candidate/active-route.png) show the task timer, path and target. Clients ran sequentially, with 1,536 MiB heaps and two active processors, without a concurrent Gradle build. Both candidate clients exited with code zero.

The earlier fixture needed an ordinary adjacent support pad to keep its unchanged planner in progress. Sneak mutation uses the actual client input getter rather than an entity setter. Both published-jar baselines were repeated after those fixture corrections with the identical final sources used by their candidate runs.

Dynamic shape callbacks are polled once per world tick and read live during native queries. This does not prove arbitrary hidden mod state changes within the same tick, unsupported player mechanics or multiplayer behavior. Polling costs are not represented by voxel-query counts; this correctness change does not establish a CPU improvement.

The subsequent uneven meadow checks on [1.21.1](1211-meadow/run.json) and [26.3](263-meadow/run.json) both acquired and crafted one table from an empty inventory, with full health and an idle engine. Every measured benchmark sample retained the prior complete path fingerprint. These checks exercise height changes during travel after the context observer change. Their unpaired timings do not establish a speed gain.
