# Navigation performance

The immediate acceptance target is responsive local resource gathering: a tree about 20 blocks away should not leave the player waiting for minutes. Route search, resource discovery, movement, mining and item collection are measured separately. A prepared-world success does not establish performance in a natural village or superiority over another mod.

## Architecture research

We inspected Baritone's published branches and its 26.3 release at commit `25111daedf1d59e6a8dfb5a3e61885cdb8d953df`. Lodekeeper remains an independent implementation; it does not embed Baritone or copy its code.

- [Pathing design](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/FEATURES.md): segmented A*, work on the next segment while executing the current one, and bounded useful partial results.
- [World-query layer](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/utils/BlockStateInterface.java): reuse nearby chunk access and repeated block-state queries during search.
- [State classification](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/precompute/PrecomputedData.java): reuse position-independent block properties while keeping contextual queries separate.
- [Search loop](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/calc/AStarPathFinder.java): a priority queue, bounded termination, reused movement results and measured search work.
- [Search/execution coordination](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/behavior/PathingBehavior.java): background calculation and guarded path handoff.

These are architectural ideas, not evidence that Lodekeeper has matched that implementation's speed. Lodekeeper's native shape queries currently run on the client thread. Moving them to a worker requires a proper immutable terrain snapshot; accessing a live Minecraft world asynchronously is not an acceptable shortcut.

## Nearby target selection

The Preview 4 natural-village feedback exposed a separate selection defect. `BlockSearch` retains the nearest visited positions, but visits chunks and cells in a different order from actual distance. `chooseLogs` and `gather` accept the first positive partial batch and discard the remaining scan. A closer visible log can therefore be missed while navigation works toward an enclosed or distant one.

An isolated chunk-boundary reproduction selected the enclosed log at `(-6,64,-6)` after 272 ms, despite a visible oak log at `(3,64,0)`. Mining intent for the visible log appeared only after 7,502 ms. It eventually acquired the item at full health, but failed the required first-target proof. This confirms wrong target selection in that fixture; it does not identify the exact selected block in the user's world.

Baritone's [mining process](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/process/MineProcess.java) filters, sorts and caps several targets, then passes their goals to a composite search. AltoClef's [collection task](https://github.com/gaucho-matrero/altoclef/blob/af22e3bc2f03dde45da703f5f7535baae18ea486/src/main/java/adris/altoclef/tasks/resources/MineAndCollectTask.java) filters cached targets, compares blocks with drops and blacklists attempts that stop progressing. These support evaluating viable alternatives before one target owns the gather attempt.

The independently reviewed development correction adds a bounded loaded local reach scan using actual face visibility before committing to global discovery results. Its [recipe-chain reproduction](evidence/1.21.1-local-reach-selection-development/README.md) selects and starts mining the visible log first after 283 ms. The enclosed decoy stays intact. A 20-block wood-to-table case also passes, with first server movement after 1,509 ms and 7 ms of route-search CPU. These are controlled single-run observations; the natural village still needs user verification. Subsequent work should preserve a small candidate pool, bound obstruction approaches separately, cache repeated complete grounded-height collections and plan ahead across segment boundaries. A zero-visible-stance fallback cannot simply be removed: safe obstruction mining may create a useful stance. Neither a broad worker-thread rewrite nor weaker geometry is justified by the current evidence.

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

A separate development-source meadow run acquired one crafting table from an empty inventory with logs 20 blocks away. The client observed server movement 2,229 ms after the command through snapshots polled every 20 ticks; completion took 17,211 ms. That distinguishes command responsiveness from travel, mining and crafting time, but includes polling delay. Later verifier code records first displacement on the server tick. It is a prepared fixture, not the reported natural village, and does not meet a comparative performance claim.

A subsequent 1.21.1 empty-inventory iron-pickaxe repetition failed while collecting cobblestone: the planner rejected the starting stance before expanding any nodes. A subsequent diagnostic identified the DROP point guard rejecting safe partial support during departure from an already validated full-support launch. The correction preserves launch, landing, collision and hazard checks. A later empty-inventory chain passed. The dedicated platform fixture then exposed unsupported stale-grounded departure and landing overshoot; both candidates still collected coal but failed the completed-DROP proof. Explicit departure tracking, live launch support and velocity-based braking passed the [final one-block platform check](evidence/1.21.1-drop-departure-development/README.md). Broader fall behavior remains unverified. The [1.21.9 renderer development check](evidence/1.21.9-visualization-development/README.md) passed with the path and target visible.

Repeated paired trials, a sufficient hot-path profile, ordinary-world chains and equivalent competitor benchmarks remain open. Released artifacts must carry separate exact-jar verification receipts.

## Next search experiment

The next candidate is reuse of complete grounded-height collections. Cardinal neighbors repeatedly call `collectGroundedStances` for the same `(x, referenceFeetY16, z)`, even though the planner already caches individual stance probes. A bounded per-planner cache must retain the full signed coordinates and reference height, copy only complete results, include complete empty results, and fall back to live collection when full. It must not suppress subsequent support, hazard, action or swept-body validation.

Invalidation needs work before that cache can ship. Both native adapters currently synchronize world and player collision context inside geometry queries; `revision()` reads a counter without synchronizing that context. A cache hit could bypass the query that detects a changed world or player shape. The experiment must establish an explicit context/revision boundary, reject a search that changes during collection or expansion, and test fractional starts, unknown terrain, incomplete results and cache saturation. Live world queries remain on the client thread.

Acceptance needs actual request/hit/collection counters, identical path fingerprints and repeated native CPU measurements with warmup reported separately. The existing meadow trace ranges across tens of milliseconds for a 56-node prepared search; a predicted cache win is not a measured improvement. Nearby-target alternatives and safe planning ahead remain later experiments.

## Query baseline after Preview 6

The [1.21.1 baseline](evidence/1.21.1-grounded-query-baseline/README.md) and [26.3 baseline](evidence/26.3-grounded-query-baseline/README.md) each record eight native meadow searches with two explicit warmups, terrain-query counters and a complete ordered path/action fingerprint. The six measured searches return the same path within each version. Their medians are 63.26 ms and 69.38 ms, respectively; these are development-source CPU measurements, not release or comparative speed claims.

A separate bounded 1.21.1 Flight Recorder run sampled grounded support profiles, body sweeps and hazard classification. Only twelve sampled stacks contained Planner frames, with some stacks truncated. That is sufficient to guide deeper profiling, but cannot assign reliable CPU percentages. The recording stays local under `/tmp/lodekeeper-grounded-baseline-1211/`.

The adapter audit found that the existing native read-cache lifetime can span ticks, despite its comment, and that dynamic bounds currently enter that cache. A whole-search grounded-height cache is postponed. Native context synchronization and dynamic-shape invalidation need explicit coverage before expanding reuse. The next low-risk experiment skips movement proofs whose relaxation state already has an equal or cheaper route. This extends the existing grounded-WALK lower-bound check to other movement primitives without caching additional geometry.
