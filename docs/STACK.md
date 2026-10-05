# Stack and delivery plan

## Decisions

**Lodekeeper** uses Java, Fabric Loader/Fabric API, Gradle with Fabric Loom, and Minecraft's own inventory/network interactions. No Baritone, Kotlin runtime, native library, remote AI service, or embedded database. Java 17 is the portable core baseline; adapters use the JVM required by their game version. MIT licence for original implementation.

Separate `core` (acquisition, catalog, commands), `nav` (incremental terrain search), and `fabric` (world discovery and tick execution). Minecraft integration is isolated because recipe, item component, input and rendering APIs change between releases. A binary may only declare versions for which it was compiled and validated. The current official release manifest says 26.3; snapshots are excluded from stable support.

## Algorithms and budgets

- Acquisition: backward chaining over methods indexed by output item, quantity-aware reservations and alternative ingredients, cycle detection, bounded branching/depth. Return concrete ordered steps, not vague goals. Reconcile inventory after every action and replan only on material change. Inventory completion is the authority.
- Navigation: incremental A* over loaded terrain only, cached collision summaries scoped to a search. Hard node and elapsed-time caps, per-tick expansion limit, no world access on worker threads. Invalidate before executing changed edges. Prefer safe walking; add jump, drop, swim, climb, break and placement edges with realistic costs and explicit prerequisites. Unloaded terrain is unknown, never air.
- Discovery: registry identifiers and tags, live recipe catalogs when exposed, bounded spatial scanning spread across ticks. Server-only loot tables and modded machine protocols cannot be inferred universally; extension providers declare source conditions and execution. Missing capabilities stop with actionable reasons.
- Execution: resumable tick state machines using standard interactions, reach/raycast checks, server-confirmed slots, bounded retries and no-progress deadlines. Stop/pause/disconnect/death always release input. Protect tools, supplies and containers. Never equate a dispatched packet with completed work.
- Observability: client-only chat commands, plan preview, queue, status and diagnostics, persisted bounded config and extensibility providers. Prefix defaults to `!lk `; wood means logs, exact items use namespaced identifiers.
- Mac budget: one Gradle worker, parallel builds disabled, daemon disabled, bounded JVM heap; one game client at a time. Measure planner and search work; publish metrics with their environment instead of unsubstantiated performance comparisons.

## Milestones

1. Research API families and document stack (this plan).
2. Pure core and navigation implementation with direct temporary harness verification; no new repository tests without approval.
3. First real adapter and build: 1.20.1. Dogfood log gathering, tool chain and crafting; fix review findings.
4. Build each remaining stable release separately, including recipe-display and unobfuscated families. Record compile and runtime status separately.
5. Expand mechanics through explicit providers: farming, mob drops/combat, fishing, trading, dimensions, structures and custom machine contracts. Coverage audit must expose gaps.
6. Independent review, public source and artifacts, installation guide, runtime evidence and user acceptance.

## Evidence and research boundaries

Official manifest: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
Fabric 1.21.2: https://fabricmc.net/2024/10/14/1212.html
Fabric 26.1: https://fabricmc.net/2026/03/14/261.html
Fabric metadata: https://meta.fabricmc.net/

Since 1.21.2, full Recipe objects are server-side; clients receive recipe-book displays. A client-only remote-server mod cannot claim complete knowledge of locked recipes, server loot, arbitrary custom machines or server plugins. Local integrated servers offer richer inspection. Universal future compatibility and every possible mod mechanic are open-ended, so coverage and blockers must remain visible.
