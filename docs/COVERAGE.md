# Survival automation coverage

This ledger keeps the full product scope visible. A planned interface or a successful compile is not proof of a working mechanic. “Controlled” means an isolated game scenario passed; natural-world and remote-server checks are separate gates. Coverage must be checked again for each adapter family.

| Capability | Current evidence | Next acceptance scenario |
| --- | --- | --- |
| Client commands, configurable prefix, plan preview, queues | Implemented; parser regression checks, inventory-refreshed previews with one request in flight | Remote-server command interception and bounded preview spam |
| Quantity planning, ingredient alternatives, tool and fuel reservations | Java regression checks; controlled single-command boots from empty inventory on [1.21.1](evidence/1.21.1-diamond-boots/run.json) and [26.3](evidence/26.3-diamond-boots/run.json) | Long acquisition chains with interruptions and inventory changes |
| Optional bulk-wood tool investment | Focused Java checks; controlled [1.21.1 enabled](evidence/1.21.1-bulk-wood-tools/run.json), [26.3 baseline](evidence/26.3-bulk-wood-baseline/run.json) and [enabled](evidence/26.3-bulk-wood-tools/run.json), exact 64 logs from empty inventory, server-confirmed two axes and safe durability | Repeated equivalent pairs, worn incumbent tools, interruptions and natural forests |
| Walking and mining reachable resources | Controlled 1.20.1, 1.21.1, 1.21.2, 1.21.11 and 26.3 logs and stone | Natural forest, cave and underground ore discovery |
| Jumping, drops, swimming, climbing, parkour | Navigation model and hazard regressions; physical behavior pending | Separate obstacle courses, submerged exits and safe fall limits |
| Breaking obstacles and building paths | Navigation model; physical behavior pending | Reversible bridge and tunnel with material limits |
| Inventory 2×2 and crafting-table 3×3 crafting | Controlled 1.20.1, 1.21.1, 1.21.2, 1.21.11 and 26.3 logs, sticks and tool progression | Mixed alternatives, remainders, repeated crafts and cancellation |
| Furnace construction and ownership | Controlled 1.20.1, 1.21.1, 1.21.2, 1.21.11 and 26.3 furnace construction and iron smelting | Stop a bulk smelt, full inventory, outsider edits |
| Automatic eating | Controlled 1.20.1, 1.21.1, 1.21.2, 1.21.11 and 26.3 bread consumption, hunger recovery and resumed gathering | Natural-world food policy and other adapter families |
| Modded registry items and ordinary recipes | Controlled 1.20.1, 1.21.1, 1.21.2, 1.21.11 and 26.3 custom ore to custom 3×3 recipe, without target-item grants | Additional modded recipes, remainders and adapter families |
| Project inventory loadouts and maintained stock | Implemented; quantity and hysteresis regressions | Foreground preemption, removal and protected stock during real crafting |
| Full recipe knowledge | Legacy synchronized catalogs; modern local-server/learned-display prototype | Local reload and remote recipe-book updates; explicit locked-recipe blocker |
| Exploration and persistent resource knowledge | Controlled [1.21.1](evidence/1.21.1-exploration/run.json) and [26.3 distant wood](evidence/26.3-exploration/run.json): initially unloaded resource, empty inventory, two bounded waypoints, eight server-observed logs. Source-aware acquisition recovery has Java regressions and a native-catalog replay; its multi-stage game scenario is pending | Natural forests, varied terrain, other adapter families and persistent world memory |
| Inventory policy and storage | Owned station cleanup in development | Reserved slots, permitted containers, deposit/retrieve and full-inventory recovery |
| Smokers and blast furnaces | Implemented and independently reviewed; exact 1.21.1 and 26.3 builds pass; controlled [26.3 blast-furnace batch](evidence/26.3-blast-furnace/run.json) passed exact 72 outputs with nine coal and multi-stack input refill | Smoker runtime, other adapter families, interruption and outsider edits |
| Campfires | Pending | Separate interaction, recovery and extinguishing behavior |
| Stonecutting and smithing | Pending | Alternate stone recipes; templates, trims and netherite upgrades |
| Anvils, grindstones and enchanting | Pending | Repair, combine, remove enchantments and account for experience |
| Brewing, cauldrons, looms and cartography | Pending | Multi-stage brewing, liquid contents, banners and maps |
| Crops, trees, composting and renewable farms | Pending | Harvest ripe plants, replant, preserve soil and maintain supplies |
| Livestock, mobs and combat | Pending | Breeding, selective harvesting, equipment, defense and retreat |
| Fishing, trading and bartering | Pending | Observable yields, villager stock/restocking and bounded barter cost |
| Structures, loot and archaeology | Pending | Permitted-container loot, suspicious blocks and exploration objectives |
| Fluid collection and placement | Pending | Buckets, source-fluid checks, water safety and lava handling |
| Nether/End travel and bosses | Pending | Portal construction, dimension transitions, return route and staged boss goals |
| Vehicles, mounts and elytra | Pending | Enter/exit, route changes, supplies and safe landing |
| Construction and redstone | Pending | Placement plan, orientation/state checks, protected areas and material accounting |
| Custom machines and server-specific mechanics | Provider model only; executors pending | A real independent provider with conditions, costs and observed completion |

## Product differences to validate

The intended advantage is a coherent goal system: projects retain all requested items together, maintenance yields to foreground work, ingredient budgets survive repeated crafts, navigation reacts to changes in the searched area, and every station action accounts for its own contents. These behaviors have individual implementation and verification gates; they are not comparative performance claims.

Further work should make those guarantees useful in ordinary play: protected areas, per-world policies, local route/resource memory, renewable supply loops, checkpointed projects, understandable blockers and bounded recovery. Faster movement is valuable only when it reduces completed-goal time without losing items or damaging builds.

## Completion gates

Each mechanic needs a real executor, a successful game scenario, safe interruption/recovery and documented adapter coverage. Natural-world chains must start with the inventory stated in their evidence. Comparative speed claims require equivalent worlds, inventories, goals, game settings and completion checks. Every remaining row stays visible until those gates pass.
