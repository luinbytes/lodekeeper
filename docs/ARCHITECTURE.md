# Lodekeeper architecture

A client-only survival automation mod with no Baritone dependency. The pure Java 17 acquisition planner builds a bounded dependency graph from a live catalog and inventory snapshot. Incremental navigation searches loaded terrain with a bounded per-tick expansion budget. Fabric bridges discover registry items, recipes, loot candidates and handle real interactions on the game thread. Actions are tick state machines with timeouts, progress checks and cancellation; no background thread reads the world. Commands never reach the server.

## Ownership and API

- `core`: `dev.lodekeeper.core` catalog/planner/command types (worker owns).
- `nav`: `dev.lodekeeper.nav` geometry/A* interfaces (worker owns).
- `fabric`: Minecraft integration, execution and configuration (root owns).

Integration uses public worker APIs; workers document their interfaces in their delivery. Runtime inventories are authoritative, replanning after completion or failure. Unknown sources remain explicit blocked goals, never success. Exact support includes only independently compiled versions; runtime acceptance has its own matrix.
