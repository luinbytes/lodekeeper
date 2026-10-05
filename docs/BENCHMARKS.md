# Performance and acceptance benchmarks

There are no published superiority measurements yet. Every result must name its source revision, Minecraft/Fabric/JVM version, hardware, world seed/save, player position/inventory, graphics, render/simulation distances and configuration. Keep baseline and candidate conditions identical. Reset the save between paired runs and distinguish cold and warm caches.

## Algorithm checks

Core and navigation expose elapsed time, expanded/discovered nodes and route cost. Measure latency distributions after JVM warmup, allocation, and cap outcomes. Benchmarks should include broad ingredient alternatives, circular recipes, missing sources, mixed inventory, dense obstacles, hazards, breaking and bridge budgets. A small synthetic case is not a claim about in-game FPS or successful survival progression.

## Gameplay cases

| Case | Required evidence |
| --- | --- |
| 64 logs from an empty inventory | Final server inventory; completion ticks; actual route; no loss/death |
| Tool progression to iron pickaxe | Gather, inventory grid, table placement/use, furnace placement/use and fuel accounting |
| Diamond boots from nothing | Full chain, ore navigation, safe tool use and confirmed final item |
| Long path with detour | Useful route segments; no-progress recovery; unchanged safety |
| Parkour, tunnel and bridge | Live collision and landing checks; exactly reserved materials |
| Renewable supplies | Maintain a stock level without busy polling; restart after consumption |
| Expedition | Food, equipment, containers, trade, structures, dimension access and return |
| Shelter/farm project | Materials, construction plan, footprint protection and validated result |
| Custom block/recipe | Namespaced registry content, explicit drop semantics, valid interactions |
| Interruption | Stop/pause during mining, movement and cursor pickup; safe recovery |

Repeat paired comparable runs and report success rate, deaths, completion ticks/time, stuck retries, material use, main-thread stalls, CPU time and peak memory. Improve a named measure without lowering task success before claiming an advantage.

## Comparator scope

AltoClef upstream is archived and documents 1.18, while its usage warns that not every resource is obtainable. Its task catalog is largely explicit. Baritone remains maintained and has a 26.3 branch with navigation, mining, farming, exploration and schematic features. A modern full-task comparison against an old incompatible game build is not equivalent; compare documented capability coverage separately unless both builds actually run on the same game version.

Primary references: [AltoClef](https://github.com/gaucho-matrero/altoclef), [AltoClef usage](https://github.com/gaucho-matrero/altoclef/blob/main/usage.md), [Baritone 26.3 features](https://github.com/cabaletta/baritone/blob/26.3/FEATURES.md), [Baritone 26.3 usage](https://github.com/cabaletta/baritone/blob/26.3/USAGE.md).
