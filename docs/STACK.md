# Stack and delivery plan

## Decisions

**Lodekeeper** uses Java, Fabric Loader/Fabric API, Gradle with Fabric Loom, and Minecraft's own inventory/network interactions. Preview 10 bundles version-matched Baritone. Preview 11 builds the licensed navigation kernel into Lodekeeper and does not load a separate Baritone runtime. Java 17 is the portable core baseline; adapters use the JVM required by their game version. Original implementation uses the MIT licence.

Separate `core` (acquisition, catalog, commands), `nav` (route views and earlier navigation models), and Fabric adapters (world discovery and tick execution). Minecraft integration is isolated because recipe, item component, input and rendering APIs change between releases. A version can be declared supported only after its exact artifact compiles. Runtime acceptance has a separate status. The current official release manifest says 26.3; snapshots are excluded from stable support. The Preview 11 source map covers 24 exact profiles across 14 navigation source families.

## Algorithms and budgets

- Acquisition: backward chaining over methods indexed by output item, quantity-aware reservations and alternative ingredients, cycle detection, bounded branching/depth. Return concrete ordered steps, not vague goals. Reconcile inventory after every action and replan only on material change. Inventory completion is the authority.
- Navigation: incremental A* over loaded terrain only, cached collision summaries scoped to a search. Hard node and elapsed-time caps, per-tick expansion limit, no world access on worker threads. Invalidate before executing changed edges. Prefer safe walking; add jump, drop, swim, climb, break and placement edges with realistic costs and explicit prerequisites. Unloaded terrain is unknown, never air.
- Discovery: registry identifiers and tags, live recipe catalogs when exposed, bounded spatial scanning spread across ticks. Server-only loot tables and modded machine protocols cannot be inferred universally; extension providers declare source conditions and execution. Missing capabilities stop with actionable reasons.
- Execution: resumable tick state machines using standard interactions, reach/raycast checks, server-confirmed slots, bounded retries and no-progress deadlines. Stop/pause/disconnect/death always release input. Protect tools, supplies and containers. Never equate a dispatched packet with completed work.
- Observability: client-only chat commands, plan preview, queue, status and diagnostics, persisted bounded config and extensibility providers. Prefix defaults to `!lk `; wood means logs, exact items use namespaced identifiers.
- Mac budget: one Gradle worker, parallel builds disabled, daemon disabled, bounded JVM heap; one game client at a time. Measure planner and search work; publish metrics with their environment instead of unsubstantiated performance comparisons.

## Milestones

1. Research API families and document stack (this plan).
2. The Java 17 core and navigation modules were implemented and checked through development harnesses. No new repository tests are added without approval.
3. Adapter work expanded from 1.20.1 to 24 exact profiles across 14 source families. The current Preview 11 matrix and remaining native gates are listed in the [owned automation checkpoint](OWNED-PREVIEW-CHECKPOINT.md).
4. The Preview 10 release has public source, jars, installation guidance, and selected native evidence. Preview 11 remains unreleased until its current matrix and gameplay gates pass.
5. Farming, fishing, trading, dimension travel, structures, and custom machine contracts remain in the coverage ledger.

## Evidence and research boundaries

Official manifest: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
Fabric 1.21.2: https://fabricmc.net/2024/10/14/1212.html
Fabric 26.1: https://fabricmc.net/2026/03/14/261.html
Fabric metadata: https://meta.fabricmc.net/

Since 1.21.2, full Recipe objects are server-side; clients receive recipe-book displays. A client-only remote-server mod cannot claim complete knowledge of locked recipes, server loot, arbitrary custom machines or server plugins. Local integrated servers offer richer inspection. Universal future compatibility and every possible mod mechanic are open-ended, so coverage and blockers must remain visible.
