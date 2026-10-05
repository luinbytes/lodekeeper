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

Steps are ordered with dependencies first. `PlanStep` carries a `PlanKind`, stable source ID, output and total planned output count, operation count, exact selected requirements, candidate blocks, recipe layout, station, and custom payload. For recipe inputs, `SelectedItemRequirement.count` is the exact selected-item quota for that step. A shaped recipe's `recipeSlot` identifies the grid slot, and quotas for that slot sum to its ingredient count multiplied by `operationCount`. The plan does not pin a concrete alternative to a particular recipe cycle: an executor can distribute that slot's exact item quotas across cycles while keeping each cycle valid. Shapeless slots use `-1`; identical ingredients may be grouped and allocated among those slots because they have the same alternative set. Tools and stations use separate selected-requirement records. Missing stations cause a `PLACE_STATION` step that consumes the placeable item once and makes the station reusable in the simulated plan. Existing snapshot items satisfy dependencies first; the requested final item is reserved rather than consumed.

`PlanResult.success()` only means the captured inventory and catalog admit a plan. It is not proof that the world performed any action. Executors should confirm server-visible inventory changes, refresh the snapshot, and replan. `optimal()` becomes false when the bounded search prunes candidates or reaches a depth, node or time limit. The default is 20 ms, 8,000 expanded nodes, depth 48, 12 candidates per branch, and 4,096 steps. `PlannerLimits` refuses time budgets over 25 ms.

For durable tools, adapters should provide current remaining durability in `InventorySnapshot`; if omitted, a known tool is treated as fresh. The planner reserves tools and never consumes them as ingredients.

## Parse client commands

The adapter must check and remove the configured prefix before calling `new CommandParser().parse(body)`. The parser returns typed `GetCommand`, control/status/queue/clear commands, `PlanCommand`, or `ConfigCommand`, or a `ParseError` with usage. It supports quoted names and config values, caps command length at 512 characters and requested counts at 1,000,000. It never sends or interprets a command itself.
