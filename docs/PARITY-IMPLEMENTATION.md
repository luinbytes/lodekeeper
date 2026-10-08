# Parity implementation

The 2026-10-08 source audit found eleven product gaps at `c0db9a5`. This ledger tracks the work authorised to close them. Cooperative player workflows take priority over experimental PvP and novelty modes.

Each unit needs a reachable command or acquisition source, guarded native execution, and evidence from its named artifact. A compiled actor remains unverified until the game confirms the result. Comparator timing remains a separate gate.

## Dependencies and completion gates

| Audit group | Required behavior | Dependencies | Current gate |
| --- | --- | --- | --- |
| Requested food and resource breadth | Requested meat, wool, crops, fluids, and ordinary mob drops contribute confirmed quantities to `get` and projects. | Native action dispatch, reservations, receipts, protected interaction policy | Open |
| Renewable farms and harvests | Harvest mature crops, retain seed stock, replant, and finish repeated cycles. Support bounded milk, egg, shearing, and honey workflows. | Resource actors, inventory capacity, permitted terrain | Open |
| Fluid buckets and bucket falls | Fill and use buckets with server confirmation. Offer bucket falls only when the selected adapter can execute them. | Native interaction actors, remainder accounting, protected terrain, survival cancellation | Open |
| Containers and inventory overflow | Learn permitted containers from observed menus, withdraw exact stock, deposit, and recover capacity without discarding protected items. | Owned menu transactions, stock generations, cancellation drain | Open |
| Travel, exploration, and waypoints | Expose coordinate travel, bounded exploration, player following, waypoint management, and useful cache controls. | One foreground task owner, movement lease, world identity | Open |
| Schematics, construction, and tunnels | Execute a bounded immutable schematic with material accounting, protected terrain, cancellation, and resumed progress. | Placement receipts, resource acquisition, owned builder snapshot | Open |
| Elytra travel | Execute bounded flight with real support checks, fuel reserves, landing, and restoration. | Native flight executor, travel ownership, fall recovery | Open |
| Survival and recovery | Finish or refuse retreats without stale-route failures. Add projectile, fire, fall, sleep, and explicit respawn recovery workflows. | Shared foreground arbitration, movement drain, native survival actors | Ended-route clearance fix reviewed and compiled on 1.21.1 and 26.3; native checks pending; other actors open |
| Nether, structures, and progression | Enter and return through portals, acquire dimension resources, find supported structures, and finish End progression. | Travel, fluids, combat, storage, dimension transition receipts | Open |
| Modern equipment mechanics | Execute modern smithing with templates and exact slot and remainder accounting. | Version-owned recipe discovery and menu transactions | Open |
| Player services and remote commands | Follow a chosen player, hand off requested items, and accept bounded commands from explicitly authorised senders. | Foreground task owner, recipient identity, inventory receipts, sender authentication | Cooperative workflows first; open |

The audit also identified two shared capabilities and five candidate advantages. Existing mining, crafting, stock goals, protection, recipe discovery, and diagnostics need verification after the new actors are integrated.

## Delivery order

The first unit fixes the evidenced stale retreat lifecycle without interrupting a working route. Acquisition and container transactions establish the dispatch and receipt boundaries used by later actors. Travel and cooperative player services follow that foundation. Renewable farms, fluids, construction, equipment, and advanced survival then feed dimension progression and flight.

Implementation uses one writer in the main checkout. Independent agents inspect designs and diffs without changing shared source. Builds use one Gradle worker with no persistent daemon. Native verification uses one isolated game client at a time, with no build running beside it.

Existing checks run for each completed unit. New test cases require Lu's approval. Lu approved focused native cases for requested meat quantities and cooking, wool and shears, protected or wrong-item refusal, and cancellation after interaction on 2026-10-08. Other new cases still need approval. Logs and game receipts must identify the production artifact, supplied fixture state, outcome, cancellation state, and any unverified behavior.

The selected shared design uses typed native work and an adapter-owned lifecycle with explicit observed outcomes. [Native acquisition boundaries](NATIVE-ACTIONS.md) records the contract and the alternatives considered.

## Evidence rules

An inventory total alone does not settle an action that still owns a menu, route, or pending mutation. The action must drain first and provide its receipt. A container observation expires when its world, generation, menu, or expected contents change.

A route that ends before arrival can drain when the current threat list is empty. Defense completes only after cancellation and a fresh threat check. The ended route earns no arrival credit. Working retreat routes retain their admitted destinations. Unsupported mechanics fail before their plans mutate the world.

Compile support is recorded per game profile. Runtime support is recorded per executed case. Comparative claims require compatible game versions, equivalent starting saves, every counted failure, and at least five interleaved paired attempts per case.

Decisions and verification gates are recorded in [the decision log](evidence/parity-decisions.tsv). The audit baseline is [main at c0db9a5](https://github.com/luinbytes/lodekeeper/tree/c0db9a547fb75ba0fd40927e5ee5280458e85cf9).
