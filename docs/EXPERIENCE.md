# Product direction

Lodekeeper should earn adoption through useful automation and clear control, not an unmeasured claim about competitors. These are design targets until their runtime scenarios pass.

## Beyond one item at a time

- Projects: `gear up`, `prepare expedition`, `build shelter` and `stockpile` become inspectable dependency graphs with budgets, reusable stations and explicit completion conditions.
- Maintained supplies: keep a chosen stock level, sleep when satisfied, resume only when inventory/storage changes. No constant polling of the entire world.
- Explainable plans: show why a tool, station or route is required; show known alternatives and the exact blocker. Preview before touching the world.
- Inventory policy: reserved tools, food, replacement gear and user-designated slots; recover crafting leftovers and avoid dropping valuables. Work only on containers the user permits.
- Situational recovery: recognize full inventory, blocked/protected blocks, vanished targets, depleted tools, damage, disconnections and changing recipes. Bound retries and retain a useful diagnostic.
- Cooperative projects: independent source providers for modded machines and tasks; every provider declares observations, conditions, costs and completion checks. Never assume an arbitrary mod's machine protocol.
- User control: stop immediately, pause safely, inspect active action, choose a bounded area and protect builds. Per-world settings and future named task macros.
- Evidence: per-goal completion time, planner/search CPU, allocation/memory, retries, placement/material cost and failure reasons. Benchmark comparable worlds and settings.

## Interaction coverage audit

The acquisition loop must eventually cover crafting grids, furnaces/smokers/blast furnaces/campfires, stonecutters, smithing, anvils, enchanting, brewing, looms, cartography, grindstones, composting, trading, bartering, fishing, agriculture, livestock, mob drops, loot structures, storage, dimension travel, bosses, vehicles, redstone and construction.

Each mechanic needs a concrete executor and scenario. Merely exposing a provider interface does not mark the mechanic implemented. Version compilation alone does not mark gameplay verified.
