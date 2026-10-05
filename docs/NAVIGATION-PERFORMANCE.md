# Navigation performance

The immediate acceptance target is responsive local resource gathering: a tree about 20 blocks away should not leave the player waiting for minutes. Route search, resource discovery, movement, mining and item collection are measured separately. A prepared-world success does not establish performance in a natural village or superiority over another mod.

## Architecture research

We inspected Baritone's published `1.21.4` branch to understand its design. Lodekeeper remains an independent implementation; it does not embed Baritone or copy its code.

- [Pathing design](https://github.com/cabaletta/baritone/blob/1.21.4/FEATURES.md): segmented A*, work on the next segment while executing the current one, and bounded useful partial results.
- [World-query layer](https://github.com/cabaletta/baritone/blob/1.21.4/src/main/java/baritone/utils/BlockStateInterface.java): reuse nearby chunk access and repeated block-state queries during search.
- [State classification](https://github.com/cabaletta/baritone/blob/1.21.4/src/main/java/baritone/pathing/precompute/PrecomputedData.java): reuse position-independent block properties while keeping contextual queries separate.
- [Search loop](https://github.com/cabaletta/baritone/blob/1.21.4/src/main/java/baritone/pathing/calc/AStarPathFinder.java): a priority queue, bounded termination, reused movement results and measured search work.
- [Search/execution coordination](https://github.com/cabaletta/baritone/blob/1.21.4/src/main/java/baritone/behavior/PathingBehavior.java): background calculation and guarded path handoff.

These are architectural ideas, not evidence that Lodekeeper has matched that implementation's speed. Lodekeeper's native shape queries currently run on the client thread. Moving them to a worker requires a proper immutable terrain snapshot; accessing a live Minecraft world asynchronously is not an acceptable shortcut.

## Identified costs

Preview 3 sampled local resource preferences within eight blocks. A crafting plan could therefore choose an unknown wood species while suitable trees stood twenty blocks away. Each failed species triggered another chunk scan. Shared discovery must feed actual available materials into recipe selection without changing explicit item requests.

Its native shape adapter also repeatedly reads the same cells while validating neighboring stances and sweeps. Search was limited per tick but had no short total wait bound. Expensive or unsuccessful searches could consume thousands of ticks before reaching the node limit. Increasing the per-tick budget alone would worsen frame stalls.

The current changes target repeated native reads, material discovery and search termination. Geometry, hazards, fractional feet heights and live execution checks remain part of the correctness contract. Visualization observes bounded snapshots; it must not trigger world searches during rendering.

## Benchmark method

The isolated 1.21.1 verifier's `lodekeeper.verify.nearbyWood=true` mode places wood twenty blocks away and begins with an empty inventory. It also measures four identical native route searches; the first is marked as warmup. `lodekeeper.verify.nearbyWoodTerrain=meadow` adds stepped grass terrain, flowers and a short dirt path. The movement/mining case uses normal survival inputs and verifies server inventory.

Each route measurement reports elapsed search time, advance-call count, expansions, discovered nodes and route length. These synchronous diagnostic searches are distinct from the normal game's incremental scheduling. Timing is recorded rather than asserted against a machine-dependent threshold. Before/after comparisons must use the same fixture, game version, JVM limits and render settings, and report any route or expansion differences.

## Current findings

The development candidate reuses native block-state, shape and loaded-chunk queries within a terrain epoch. A sweep now checks each overlapping body union once; separate endpoint scans covered volumes already contained in those unions. Context, world and terrain revision changes invalidate cached reads. Native mixed courses passed on 1.21.1 and 26.3 with all 692 protected blocks unchanged and minimum health 20.

Recipe prerequisites share a palette-pruned search across eligible log sources. An observed available species can change the recipe plan before discovery finishes. Explicit item goals keep their exact identity. Only a completed search can mark fully covered sources absent.

Navigation can hand off a validated forward route segment after a bounded search interval. A search with no useful forward segment keeps its frontier, including detours that initially lead away from the goal. It does not report an inaccessible target merely because its first interval expires.

The overlay observes a bounded immutable navigation snapshot every four ticks. Drawing reads no world blocks and limits geometry to 64 route segments and 256 optional search markers within 64 blocks. Native APIs stay behind exact version profiles. The 1.21.9/1.21.10 profiles need their own hook because [Fabric removed world render events in that API generation](https://fabricmc.net/2025/09/23/1219.html).

Timing remains preliminary. The unprofiled exact Preview 3 meadow route samples were 105.27, 110.43 and 96.12 ms. Candidate samples after cache reuse and duplicate sweep removal were 88.18, 65.39 and 48.79 ms, with the same 56 expansions, 187 discovered nodes and 21 route steps. Falling candidate times show JIT warmup drift. These are three consecutive samples from one process per side, not independent paired trials; they do not establish a reliable speedup.

The [empty-inventory iron-pickaxe development check](evidence/1.21.1-speed-iron-empty-development/README.md) completed the entire controlled progression with wood 20 blocks away, using native tables and a furnace at full health. Its 122,607 ms command time includes mining, travel, station use and smelting, so it is not a route-search measurement.

A separate development-source meadow run acquired one crafting table from an empty inventory with logs 20 blocks away. Server-observed first movement occurred 2,229 ms after the command; completion took 17,211 ms. That distinguishes command responsiveness from travel, mining and crafting time. It is a prepared fixture, not the reported natural village, and does not meet a comparative performance claim.

A subsequent 1.21.1 empty-inventory iron-pickaxe repetition failed while collecting cobblestone: the planner rejected the starting stance before expanding any nodes. A subsequent diagnostic identified the DROP point guard rejecting safe partial support during departure from an already validated full-support launch. The correction preserves launch, landing, collision and hazard checks. A later empty-inventory chain passed, but a dedicated platform check is still needed to prove that transition consistently. The [1.21.9 renderer development check](evidence/1.21.9-visualization-development/README.md) passed with the path and target visible.

Repeated paired trials, a sufficient hot-path profile, ordinary-world chains and equivalent competitor benchmarks remain open. Released artifacts must carry separate exact-jar verification receipts.
