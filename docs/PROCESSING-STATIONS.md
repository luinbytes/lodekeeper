# Cooking station expansion

The core fuel-capacity contract is implemented; this document also describes the adapter work for smoker and blast-furnace support. Those executors are not yet verified or advertised as available. Campfires need a separate executor because their interaction and recovery differ from the three-slot cooking menus.

## Preserve the station contract

Keep each native cooking recipe's station in `RecipeWork`, its planner `StationRequirement` and its opened menu. Native smelting, smoking and blasting map to furnace, smoker and blast furnace respectively. Integrated-server recipe types and recipe-display stations must agree. Remote displays must name one of the supported stations; unknown or contradictory displays stop before spending items.

The existing station ownership map and transaction accounting should be reused. An opened menu must match the planned station, including its input rules. Preserve empty-station checks, ownership of inserted input/fuel/output, server-observed completion and safe drain on interruption.

## Fuel depends on the station

`SmeltingSource.fuelProgressTicks` now supplies a bounded immutable per-fuel capacity map in each recipe's progress units. A nonempty map is authoritative: omitted fuels are unusable for that source. An empty map preserves the existing `CatalogSnapshot.fuelBurnTicks(item)` contract for ordinary sources and the old constructor. Adapters must reject a station with no supported fuels, rather than publish an empty override. Eligibility, ranking and quantity ceilings use the same effective capacity. Java regressions cover older fast-station quantities, ordinary-constructor compatibility, modern custom capacity, missing overrides and protected fuel.

Global `CatalogSnapshot.fuelBurnTicks(item)` alone is insufficient for fast cooking. Older smoker and blast-furnace implementations halve the standard fuel duration while their recipes commonly cook in 100 ticks. Passing that native duration to a planner using the normal 1,600-tick coal value would reserve too little coal. The override appears in [Yarn's smoker API](https://maven.fabricmc.net/docs/yarn-1.21.11%2Bbuild.1/net/minecraft/block/entity/SmokerBlockEntity.html) and [blast-furnace API](https://maven.fabricmc.net/docs/yarn-1.21.11%2Bbuild.4/net/minecraft/block/entity/BlastFurnaceBlockEntity.html); [MC-141073](https://bugs-legacy.mojang.com/browse/MC-141073) records the division-by-two implementation and affected older versions.

In 26.3, recipe duration and cooking fuel have a different contract. Recipes retain ordinary cooking times, while the cooking-fuel component supplies separate context-dependent burn time and speed. Vanilla fast-station factors happen to preserve familiar coal yields, but custom providers need not use those same factors. Resolve effective cooking progress per fuel against the exact station context, rather than assuming a universal multiplier. Verify native progress rounding and reset behavior before treating a custom burn-time/speed product as capacity; overshoot discarded at each output can require additional fuel. [Mojang's 26.3 notes](https://feedback.minecraft.net/hc/en-us/articles/48913133328013-Minecraft-Java-Edition-26-3) describe the cooking-fuel component, speed providers and fuel-remainder behavior.

Fuel selection must use this station-specific capacity for validity, ranking and quantity ceilings. Execution retains the native recipe's real cook duration and independently observes actual progress. Provider expressions remain bounded by depth, nodes and cycle checks; unsupported expressions produce a blocker.

## Remainders and verification

A consumed fuel may return a container, such as an empty bucket. Reserve destination capacity and model whether the native station leaves or drops that remainder. Recovery must return only owned contents and modeled remainders. Exclude fuels whose remainder contract cannot be conserved safely.

Focused checks must verify recipe-to-station mapping, exact menu acceptance, older fast-station coal capacity, 26.3's separate burn/speed contexts, remote display validation and ownership during pause/replan. Real clients then need one completed recipe per station plus interruption recovery, with exact adapter compilation kept separate from gameplay evidence.

## Implementation seams

- Core: `SmeltingSource` and `AcquisitionPlanner.prepareSmelting` provide the station-specific fuel-capacity contract. Global catalog capacity remains the compatibility fallback.
- Shared older adapter: `RecipeWork`, `GameCatalog`, cooking recipe classifiers in every `GameApi` family, `AutomationEngine.stationReady` and `SmeltingAction` need to preserve and validate the station.
- Modern adapter: `GameCatalog` currently rejects non-furnace cooking stations; `RecipeWork` loses that station and menu acceptance is broader than the planned type. The 26.3 `GameApi` resolver currently evaluates only furnace fuel context.

These changes require exact profiles from 1.20 through 26.3, including 1.21.1. Supporting the shared menu superclass alone is insufficient evidence of a working station.
