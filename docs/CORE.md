# Pure core API

`core` is Java 17 and has no Minecraft or Fabric dependency. The adapter builds a new immutable `CatalogSnapshot` from registry, tag, recipe, fuel and extension data, captures an `InventorySnapshot` on the game thread, asks `AcquisitionPlanner` for work, then reconciles the real inventory and replans after actions.

## Build a catalog

```java
CatalogSnapshot catalog = CatalogSnapshot.builder()
    .item(ItemId.parse("minecraft:oak_log"), 0, "wood", "logs")
    .item(ItemId.parse("minecraft:coal"), 0, 1_600, "coal") // burn ticks per item
    .tag(TagId.parse("minecraft:logs"), logItems)
    .source(new GatherSource("gather:minecraft:oak_log", ItemId.parse("minecraft:oak_log"),
        1, List.of(BlockId.parse("minecraft:oak_log")), List.of()))
    .build();
```

Unqualified IDs use the `minecraft` namespace. `resolveItem` accepts canonical IDs and normalized aliases, and reports alias collisions as multiple candidates. Item tags expand to sorted concrete items. Recipe ingredients are ordered alternatives, not an instruction to consume every alternative.

Built-in source records are `GatherSource`, `CraftingSource`, `SmeltingSource` and `CustomSource`. Requirements are explicit records: `ItemRequirement` describes consumed or retained items, `ToolRequirement` reserves a selected tool, and `StationRequirement` names the station and its placeable item. A shaped recipe stores row-major grid indices and its dimensions; shapeless recipes use dimensions `0 x 0` and slot index `-1`. Each recipe ingredient count is per operation and `outputCount` is the recipe yield per operation.

The legacy `ToolRequirement` constructor remains minimum-only. Requirements with positive `wearPerOperation` reserve wear across the whole source batch and preserve one durability point per tool stack. `InventorySnapshot` accepts actual per-stack `durabilityLots`; a max-only legacy snapshot contributes one known lot, while counts alone do not imply that an unobserved tool is undamaged.

Smelting fuel alternatives are `ItemSelector`s. `ItemDefinition.fuelBurnTicks` stores the burn duration per unit, allowing the planner to compute the required fuel as `ceil(cookTicks * operations / fuelBurnTicks)` and share fuel already in inventory. Register fuel durations for every catalog item the adapter wants considered. Modded mechanics can use `CustomSource` with an adapter-owned `sourceType`, immutable attributes and common requirements.

## Plan an acquisition

```java
InventorySnapshot inventory = new InventorySnapshot(itemCounts, availableStations, remainingDurability);
PlanResult result = new AcquisitionPlanner().plan(catalog, inventory,
    ItemId.parse("minecraft:diamond_boots"), 1);
if (result.success()) {
    for (PlanStep step : result.steps()) executeOneStep(step);
} else {
    report(result.blockedReasons());
}
```

Steps are ordered with dependencies first. `PlanStep` carries a `PlanKind`, stable source ID, output and total planned output count, operation count, exact selected requirements, candidate blocks, recipe layout, station, and custom payload. For recipe inputs, `SelectedItemRequirement.count` is the exact selected-item quota for that step. A shaped recipe's `recipeSlot` identifies the grid slot; planner-generated shapeless requirements normalize the source's `-1` slots to their sequential ingredient indices. In both cases quotas for a slot sum to its ingredient count multiplied by `operationCount`, so executors can follow each slot's exact item budget across cycles. The planner processes less-flexible ingredient groups before groups with more alternatives, preserving held items needed by stricter slots. Tools and stations use separate selected-requirement records. Missing stations cause a `PLACE_STATION` step that consumes the placeable item once and makes the station reusable in the simulated plan. Existing snapshot items satisfy dependencies first; the requested final item is reserved rather than consumed.

`PlanResult.success()` only means the captured inventory and catalog admit a plan. It is not proof that the world performed any action. Executors should confirm server-visible inventory changes, refresh the snapshot, and replan. `optimal()` becomes false when the bounded search prunes candidates or reaches a depth, node or time limit. The default is 20 ms, 8,000 expanded nodes, depth 48, 12 candidates per branch, and 4,096 steps. `PlannerLimits` refuses time budgets over 25 ms.

For durable tools, adapters should provide current remaining durability in `InventorySnapshot`; if omitted, a known tool is treated as fresh. The planner reserves tools and never consumes them as ingredients.

An adapter can protect stock for maintained targets with the four-argument `InventorySnapshot(counts, availableStations, remainingDurability, protectedCounts)` constructor. Protected quantities must be present in `counts`, and the snapshot rejects a protected amount larger than its observed total. Existing one- and three-argument constructors leave `protectedCounts` empty. Consuming recipe inputs, fuel, and station placement use only spendable counts (`counts - protectedCounts`); retained `ToolRequirement`s and non-consuming `ItemRequirement`s can reuse protected items. When planning a maintained item's own target, omit that item from `protectedCounts` so the planner can see its current inventory and calculate the missing amount.

## Parse client commands

The adapter must check and remove the configured prefix before calling `new CommandParser().parse(body)`. The parser returns typed `GetCommand`, control/status/queue/clear commands, `PlanCommand`, or `ConfigCommand`, or a `ParseError` with usage. It supports quoted names and config values, caps command length at 512 characters and requested counts at 1,000,000. It never sends or interprets a command itself.

The parser also returns `ProjectCommand`, `ProjectsCommand`, `MaintainCommand`, `UnmaintainCommand`, and `MaintainedCommand` for `project <name>`, `projects`, `maintain <item> <count>`, `unmaintain <item|all>`, and `maintained`. Project names are lower-case slugs; item counts share the existing 1..1,000,000 cap.

## Named inventory projects

`ProjectCatalog.standard()` contains stable, alphabetically ordered inventory targets: `gear_stone`, `gear_iron`, `expedition`, `mining_trip`, `farming_supplies`, and `shelter_supplies`. A `ProjectSpec` contains at most 32 exact `ItemId` quantities. Projects describe inventory outcomes. In particular, `shelter_supplies` gathers materials and tools only; it does not build or place a shelter. The farming project likewise requests supplies and does not plant or construct a farm.

Adapters can register their own projects without replacing built-ins or another provider's names:

```java
ProjectCatalog projects = ProjectCatalog.builder()
    .registerProvider("my_addon", List.of(myProject))
    .build();
ProjectSpec spec = projects.require("gear_iron");
```

`registerProvider` rejects duplicate provider IDs, duplicate project names, and collisions with built-ins. `replaceProvider` explicitly replaces one provider's complete prior registration and still cannot override built-ins or another provider. Catalogs and each goal map are immutable.

## Maintained inventory

`MaintainedDemandModel` is a pure Java policy object. The adapter calls `maintain(item, targetCount)` (or supplies an explicit `refillBelow` low-water mark), feeds observed counts to `onInventoryChanged`, and enqueues each returned `MaintenanceRequest`. A request exposes `targetCount` as the total desired inventory and `deficitCount` as the missing quantity at dispatch time. The default low-water mark is half the target, rounded down; when observed stock is at or below it, one bounded task requests stock back to the full target.

The model tracks at most 32 maintained items and 32 active requests. `statuses()` reports the last observed actual count, target, low-water mark, reserved output, remaining shortfall, and state. `reservedCounts()` reports physically held counts protected for maintained targets so an adapter can subtract them before planning unrelated goals. `reservedCountsFor(targetItem)` leaves that final item available to its own maintenance request; `activeOutputReservations()` separately reports counts promised by active requests. These are accounting hints: the adapter still owns queue execution and must refresh the real inventory after actions.

On completion or failure, call `complete(taskId, observedCounts)` or `fail(taskId, observedCounts)`. A task that makes no inventory change is blocked against immediate retry; any subsequent inventory snapshot change or an explicit new `maintain` command permits another attempt. `unmaintain(item)` and `unmaintainAll()` return only the maintenance-owned task IDs that the adapter should cancel; they do not clear user-created queue entries. The model never reads Minecraft state, performs pathing, or claims that any requested task has completed.
