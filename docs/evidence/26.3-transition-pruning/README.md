# 26.3 exact-state transition pruning

Development-source comparison based on Preview 6 plus generic transition pruning. This is not an exact released-jar receipt. Timing is inconclusive. The deterministic result is fewer native queries with identical complete paths and actions.

[Raw run](run.json) and [comparison summary](summary.json) record sixteen alternating disabled/enabled native meadow searches in one JVM. Four balanced warmups leave six measured searches per side. The isolated client used 1536 MiB heap, two visible processors, a 640 by 360 window and a disposable world.

| Measurement | Disabled | Enabled |
| --- | --- | --- |
| Synchronous elapsed median | 45.57 ms | 47.26 ms |
| Synchronous elapsed range | 40.59 to 54.64 ms | 37.54 to 61.35 ms |
| Search-thread CPU median | 45.52 ms | 42.37 ms |
| Search-thread CPU range | 40.60 to 49.44 ms | 37.55 to 50.99 ms |
| Voxel queries per search | 372,275 | 369,470 |
| Dominated transitions skipped | 0 | 5 |

Every search returned FOUND, with 56 expansions, 187 discovered states, 21 path steps and the same complete ordered path/action SHA-256. The reduction of 2,805 queries is 0.75 percent. Timing ranges overlap, so the median differences do not establish faster execution. Grounded-height collection counts remain 220; this change adds no geometry memoization. Search time excludes fingerprinting and the idle gaps between normal client ticks. CPU time comes from the current render thread's enabled JVM thread clock.

The separate survival command uses the enabled production default. It acquired one crafting table from an empty inventory in 18,863 ms, with first server movement at 2,606 ms, full health and an idle engine. That single end-to-end run is a correctness check, not a paired performance result. Natural-world and competitor comparisons remain open.

To select this developer scenario, add these JVM properties to the dedicated verification client configuration described in [game verification](../../GAME-VERIFICATION.md):

```text
-Dlodekeeper.verify.nearbyWood=true
-Dlodekeeper.verify.nearbyWoodGoal=crafting_table
-Dlodekeeper.verify.nearbyWoodTerrain=meadow
-Dlodekeeper.verify.routeBenchmarkSamples=16
-Dlodekeeper.verify.routeBenchmarkWarmups=4
-Dlodekeeper.verify.compareTransitionPruning=true
```
