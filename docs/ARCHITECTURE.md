# Lodekeeper architecture

A client-only survival automation mod. Preview 10 bundles version-matched Baritone. [Preview 11](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.11) is published for 1.21.1 and 26.3 at `88f2b52`, with unfinished gameplay. It builds a licensed navigation kernel into Lodekeeper without loading a separate Baritone runtime. The pure Java 17 acquisition planner builds a bounded dependency graph from a live catalog and inventory snapshot. Navigation searches loaded terrain with bounded work. Fabric bridges discover registry items, recipes, loot candidates and handle real interactions on the game thread. Actions are tick state machines with timeouts, progress checks and cancellation; no background thread reads the world. Commands never reach the server.

## Ownership and API

- `core`: `dev.lodekeeper.core` catalog/planner/command types.
- `nav`: `dev.lodekeeper.nav` route views and earlier navigation models.
- `fabric`: Minecraft integration, execution and configuration.

Adapters consume public core and navigation APIs. Runtime inventories are authoritative, replanning after completion or failure. Unknown sources remain explicit blocked goals, never success. Exact support includes only independently compiled versions; runtime acceptance has its own matrix.

The Preview 11 source map routes 24 exact Minecraft profiles through 14 navigation source families. The source map is not a support declaration. The release-source full matrix failed; both published primary jars passed their CI jobs and packaging checks. Later main `b789d0c` passes all 24 exact CI adapter jobs. That later compile result does not expand Preview 11 or establish gameplay acceptance. See the [owned automation plan](OWNED-AUTOMATION-PLAN.md) for source locks, notices, and current gates.

[Local resource planning](LOCAL-PLANNING.md) describes advisory observations, invalidation, bounded ranking and discovery. [Processing stations](PROCESSING-STATIONS.md) describes native station selection and transaction ownership.
