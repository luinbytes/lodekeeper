# Product direction

Lodekeeper should earn adoption through useful automation and clear control, not an unmeasured claim about competitors. These are design targets until their runtime scenarios pass.

## Beyond one item at a time

- Projects: `projects` lists the built-in inventory loadouts and `project <name>` queues their exact item targets as foreground work. Completion is reported only when every declared count is present together; the client makes at most three bounded reconciliation passes if later work consumed a target. `shelter_supplies` and `farming_supplies` collect materials only; they do not construct or place anything.
- Maintained items: `maintain <item> <count>` sets a low-water refill target, `maintained` reports the last observed actual counts and queue state, and `unmaintain <item|all>` removes only background maintenance work. Background tasks yield to foreground `get` and project goals. Inventory is fingerprinted across the 36 storage slots every ten ticks; a full count snapshot is built only after a change. `stop` clears maintained targets, `pause` keeps them, and changing worlds resets them.
- Explainable plans: show why a tool, station or route is required; show known alternatives and the exact blocker. Preview before touching the world.
- Inventory policy: reserved tools, food, replacement gear and user-designated slots; recover crafting leftovers and avoid dropping valuables. Work only on containers the user permits.
- Situational recovery: recognize full inventory, blocked/protected blocks, vanished targets, depleted tools, damage, disconnections and changing recipes. Bound retries and retain a useful diagnostic.
- Cooperative projects: independent source providers for modded machines and tasks; every provider declares observations, conditions, costs and completion checks. Never assume an arbitrary mod's machine protocol.
- User control: stop immediately, pause safely, inspect active action, choose a bounded area and protect builds. Per-world settings and future named task macros.
- Evidence: per-goal completion time, planner/search CPU, allocation/memory, retries, placement/material cost and failure reasons. Benchmark comparable worlds and settings.

## Interaction coverage audit

The acquisition loop must eventually cover crafting grids, furnaces/smokers/blast furnaces/campfires, stonecutters, smithing, anvils, enchanting, brewing, looms, cartography, grindstones, composting, trading, bartering, fishing, agriculture, livestock, mob drops, loot structures, storage, dimension travel, bosses, vehicles, redstone and construction.

The detailed [coverage ledger](COVERAGE.md) records evidence and the next acceptance scenario for every category. Each mechanic needs a concrete executor and scenario. Merely exposing a provider interface does not mark the mechanic implemented. Version compilation alone does not mark gameplay verified.
