# Making tools when they earn their cost

Large `wood` or `logs` requests can optionally acquire wooden axes before gathering the requested stock. Enable the experimental policy with `!lk config optimizeWoodTools true`; disable it with `!lk config optimizeWoodTools false`. Current development builds enable it by default. Existing saved configurations retain their value; Preview 8 users can enable it explicitly.

The normal goal must already have a complete plan. The optional planner then checks nearby known logs, exact native axe recipes, safe tool durability, protected inventory, crafting-table access and space for intermediate materials. It prices the entire bootstrap, including wood spent from the requested stock, and proceeds only when estimated savings exceed setup cost plus a margin. A usable held tool reduces the work attributed to new axes. Unknown wear or unsupported auxiliary work skips the investment.

This first policy covers foreground wood requests. It does not claim an optimal tool tier or universal mining-time prediction. Breaking-speed estimates currently use native block hardness and raw stack speed; player effects, enchantments and server latency can change actual results. Planning rejection keeps the ordinary plan. Execution blockers pause safely for inspection. Changing the option affects subsequent replans; an already started inventory transaction can finish or drain safely.

## Controlled comparison protocol

The development verifier uses separate disposable worlds for baseline and enabled runs. Each uses the same world seed, starting position, empty inventory, 80 oak logs, bedrock floor, health, difficulty, client settings and one command: `!lk get wood 64`. It grants no tools or goal items. Baseline must finish with exactly 64 logs, no axes and no crafting-table opening; enabled must finish with exactly 64 logs, at least two wooden axes and a server-observed crafting-table opening.

Evidence records command-to-completion wall time, world/client ticks, full server inventory, health and each axe stack's remaining durability. Client startup is excluded from case timing. Server observations occur every 20 client ticks, adding bounded detection delay. Run one resource-capped client at a time. A single pair is preliminary scenario evidence; repeat equivalent pairs before making a general speed claim. These checks do not compare Lodekeeper against another mod or establish performance in natural worlds.

The scenario uses `lodekeeper.verify.bulkWood=true`; add `lodekeeper.verify.woodTools=true` for the enabled run. Verification content is development-only and excluded from distributed jars.

## Initial evidence

A single controlled 26.3 pair passed from empty inventory: [baseline](evidence/26.3-bulk-wood-baseline/run.json) took 278,043 ms and 5,561 world ticks; [enabled](evidence/26.3-bulk-wood-tools/run.json) took 242,159 ms and 4,844 world ticks. Both finished with exactly 64 logs and full health. Enabled finished with two axes at 1 and 53 durability, showing the first was retained until its safe reserve. This is approximately 12.9% less command-to-completion time in that pair; repeatability and natural-world results remain pending.

The [1.21.1 enabled check](evidence/1.21.1-bulk-wood-tools/run.json) also passed with exactly 64 logs, two axes at 1 and 53 durability and full health: 241,232 ms and 4,825 world ticks. No paired baseline was run for that version, so this verifies the behavior without a comparative timing claim.

## Native mining refresh repair

The native batch miner discovers its own targets. The engine now imports a bounded snapshot of those loaded targets before reconsidering tool investment. Auxiliary tool work retains its identity across replans, so the 40-tick refresh cannot restart a hand-mined log before it breaks.

The repaired development jars passed the same prepared 64-log case on [1.21.1](evidence/survival-safety/preview9-wood-tools-1211/run.json) and [26.3](evidence/survival-safety/preview9-wood-tools-263/run.json). Command-to-receipt times were 149,507 ms and 149,536 ms. Both received exactly 64 logs, opened a crafting table, retained two axes with 1 and 52 durability, and finished with full health. These artifacts came from the working tree; their hashes are in the adjacent artifact receipts. They do not establish whole-project survival performance or a speed advantage over another mod.

The preceding Preview 8 check had the option enabled but completed without an axe. That [failed investment check](evidence/survival-safety/checkpoint6ca3-bulk-wood-tools-1211.json) confirms why source compilation alone did not establish this behavior.
