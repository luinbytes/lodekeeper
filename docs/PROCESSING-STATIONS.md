# Cooking stations

Lodekeeper maps native smelting, smoking and blasting recipes to their exact furnace, smoker or blast-furnace station. The same station identity travels through recipe capture, planning, placement, opening and execution. Controlled [1.21.1 smoker](evidence/1.21.1-smoker/run.json) and [26.3 blast-furnace](evidence/26.3-blast-furnace/run.json) batches passed; other exact-version runtime checks remain pending. Compilation and source review do not establish gameplay support. Campfires still need a separate executor.

## Station choice and ownership

The fast acquisition planner prefers a usable owned station, then an unprotected held station item, then a station that needs construction. Within that preference it compares recipe duration. This avoids constructing a furnace when the player already has a suitable smoker or blast furnace. In 26.3, equal recipe durations can hide different fuel speeds; selecting the fastest among equally available stations remains a separate optimization.

Integrated-server recipe types and recipe-display stations must agree. Remote displays must resolve to one supported station; ambiguous or unsupported alternatives are rejected. Display expansion is bounded. Execution accepts the exact native menu class for the planned station and requires empty input, fuel, output and cursor before spending materials. It tracks only inserted materials and their component-exact results. Existing contents are never adopted.

## Fuel quantities and native timing

`SmeltingSource.fuelProgressTicks` is a bounded immutable map of effective capacity in each recipe's progress units. A nonempty map is authoritative: omitted fuels are unusable. The old constructor and an empty map retain the catalog's global fuel-capacity contract. Native adapters reject recipes with no supported fuels, rather than accidentally publish an empty override. Eligibility, ranking and quantity ceilings use the same effective capacity.

Before 26.3, smoker and blast-furnace burn durations are halved; their native recipes commonly take 100 ticks. Using ordinary 1,600-tick coal capacity for a fast recipe would reserve too little coal. See [Yarn smoker documentation](https://maven.fabricmc.net/docs/yarn-1.21.11%2Bbuild.1/net/minecraft/block/entity/SmokerBlockEntity.html), [blast-furnace documentation](https://maven.fabricmc.net/docs/yarn-1.21.11%2Bbuild.4/net/minecraft/block/entity/BlastFurnaceBlockEntity.html) and [MC-141073](https://bugs-legacy.mojang.com/browse/MC-141073).

In 26.3, cooking fuel has independent context-dependent burn-time and speed providers. The adapter resolves them against the exact station on the integrated-server thread, with depth, node and cycle bounds. Results are cached by station and recipe duration within one catalog load, with at most 64 contexts and 256 usable fuels each. Unsupported expressions are rejected. A remote client currently accepts only literal providers; referenced world-provider evaluation remains unsupported. [Mojang's 26.3 notes](https://feedback.minecraft.net/hc/en-us/articles/48913133328013-Minecraft-Java-Edition-26-3) describe this fuel system.

Cached native bytecode confirms that the effective timer is `ceil((float) recipeDuration / fuelSpeed)`, preserving float division before ceiling. For continuously supplied fuel, conservative capacity is `floor(burnTicks * recipeDuration / effectiveTimer)`. For unstackable fuel, capacity credits only complete operations: `floor(burnTicks / effectiveTimer) * recipeDuration`; leftover progress may decay between burns. Java regressions cover rounding, discontinuous fuel, authoritative overrides, protected fuel and station preference.

## Refill schedule and supported limits

Input and fuel are topped up before depletion and before ordinary output collection. Refills use actual destination room and planned quantities. A half-stack pickup avoids repeated clicks when it matches the requested amount; each transfer is capped at 64 items. Input consumption during a transfer needs an independently observed output, alongside cursor and slot conservation. Larger unexplained changes fail closed.

The current serialized transfer schedule deliberately rejects unsafe timing before inserting materials:

- Native effective cooking timers must be at least 100 ticks.
- Fuel burn duration must be at least 32 ticks. Stackable fuel must retain at least 800 ticks of burn at its half-stack low watermark.
- Bulk input and output slots must each hold at least 16 operations. Smaller-capacity and unstackable bulk inputs or outputs need explicit gap accounting and remain unsupported.
- Supply stack capacities above 99, invalid counts, changed fuel stack capacities, nondefault 26.3 cooking-fuel components and container-remainder fuels are unsupported.

These are executable limits, not a statement that Minecraft or modded recipes cannot use other values. Multiplayer latency and outsider inventory edits can still cause a transaction to stop for safe recovery. Component-exact supplies must remain available throughout a transaction.

On 26.3, an empty station that is already burning may retain an unknown previous fuel speed. The action waits untouched for it to cool, for at most 1,800 ticks, while honoring stop/drain requests. If it remains lit, execution reports a blocker. Older fixed-speed stations do not need this wait. Long burns such as a coal block can exceed this bound.

## Verification boundaries

Focused Java checks cover planning quantities and fuel arithmetic. Exact profile builds cover API compatibility. Real-client checks must additionally prove station placement, exact native menu opening, repeated output collection, cursor recovery and multi-stack input refill. The cooking verifier requests 72 native outputs from 128 supplied raw items, nine coal and one supplied station item; success requires exactly 72 outputs, 56 raw items left, zero inventory coal, full health and an idle engine. It grants no target output.

Interruption recovery, outsider edits, full-inventory recovery, natural-world acquisition and remote-server behavior remain separate acceptance scenarios. Supporting the shared menu superclass alone is insufficient evidence of a working station.

The [26.3 blast-furnace run](evidence/26.3-blast-furnace/run.json) passed the 72-output batch with exactly 56 raw iron left and nine coal consumed, in 361,958 ms from the command. Its [source and artifact record](evidence/26.3-blast-furnace/artifact.json) identifies the frozen development client.

The [1.21.1 smoker run](evidence/1.21.1-smoker/run.json) passed the same batch contract with exactly 56 raw porkchops left and nine coal consumed, in 361,457 ms from the command. The [24-version artifact inspection](evidence/builds/53c48a6.json) records exact builds and packaging separately from those game scenarios.
