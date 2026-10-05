# Choosing resources near you

Acquisition uses your usable inventory first, then advisory information about nearby gathering sources. An observed nearby oak log can guide the choice of oak planks and sticks; nearby ordinary stone can guide a stone pickaxe instead of a distant deepslate alternative. These preferences change search order. They never grant items, promise a reachable path, or declare an unobserved resource absent.

Discovery visits loaded chunks near the player first and orders their sections by distance from the player's height. It can use a positive candidate before scanning the entire region. Only a completed scan can establish local absence. While a scan makes progress, the HUD shows its chunk progress and the action's stall timer resets; movement and inventory transactions retain their own progress safeguards. A stalled action reports its phase and target coordinates when available.

Mined or unloaded observations are removed when a planning snapshot validates them, and the incremental scan restarts to find remaining candidates. A waiting gather may refresh its plan when new hints arrive; valid mining targets and owned station transactions continue. Each step retains the hint version that produced it, including hints that changed while its asynchronous plan was running.

Cooking recipes retain their station identity throughout planning and execution. An already placed station takes precedence, followed by a usable held station item. When constructing a missing station would directly consume more of the recipe's own output than the player can spend, that route receives a soft ordering penalty. For example, making a blast furnace requires five iron ingots, so ordinary furnace smelting should bootstrap initial iron acquisition. Held blast furnaces remain eligible for fast cooking.

## Resource and concurrency bounds

World observations run on the client thread. The nearby index processes at most 512 metadata units or 128 loaded-block probes per tick, sharing a cooperative 0.5 ms deadline. It indexes at most 4,096 gathering sources and 8,192 block references, with at most 32 sources per block and 256 positive source observations. Unobserved vanilla blocks may receive a hardness estimate; custom blocks remain unknown until observed. Native chunk queries never request chunk loading.

Planning receives immutable catalog, inventory and preference snapshots on a single worker. Direct source hints seed at most 512 item ranks; rank snapshots have a separate 512-entry budget. Recipe derivation uses simple crafting and smelting inputs, depth 8 and 512 visits, with deadline checks and frozen comparator keys. Station setup ranks are calculated once before sorting, with at most 64 distinct placement items and 64 placement sources per item inspected. Unknown or infeasible preferred choices retain ordinary fallback. Tool alternatives keep their declared bootstrap order. Fuel selection preserves full and partial usable held stock before comparing bounded acquisition-effort hints for missing fuel. Protected stock cannot satisfy that priority; without hints, legacy ordering remains. Plan previews use the same preference snapshot mechanism as execution.

The existing planner checks a shared elapsed and node budget and retries a small number of cold timeouts. Cooperative checks do not establish a strict wall-clock upper bound for every operation on an arbitrarily large catalog, especially during JVM warm-up. Controlled timings, stationary-clock logic checks and actual completed-goal timings must be reported separately.

## Verification boundaries

Nearby observations are neither path-cost estimates nor persistent world memory. A natural cave, a remote server, protection rules and an unseen modded machine still require separate gameplay acceptance. The iron progression fixture supplies only one crafting table but prepares its resource terrain; it verifies ordinary player mining, crafting and furnace interactions rather than natural-world ore discovery.

The controlled crafting-table-only iron-pickaxe runs passed on [1.21.1](evidence/1.21.1-iron-table/run.json) in 110471 ms and [26.3](evidence/26.3-iron-table/run.json) in 109723 ms from their commands, at full health. An earlier 1.21.1 run of the same prepared fixture took 167756 ms; this single pair is preliminary goal-time evidence, not a comparison with another mod.
