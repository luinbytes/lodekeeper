# Parity implementation

The 2026-10-08 source audit found eleven product gaps at `c0db9a5`. This ledger tracks the work authorised to close them. Cooperative player workflows take priority over experimental PvP and novelty modes.

Each unit needs a reachable command or acquisition source, guarded native execution, and evidence from its named artifact. A compiled actor remains unverified until the game confirms the result. Comparator timing remains a separate gate.

## Dependencies and completion gates

| Audit group | Required behavior | Dependencies | Current gate |
| --- | --- | --- | --- |
| Requested food and resource breadth | Requested meat, wool, crops, fluids, and ordinary mob drops contribute confirmed quantities to `get` and projects. | Native action dispatch, reservations, receipts, protected interaction policy | Animal acquisition compiles across the 24-profile matrix; requested sources remain restricted to 1.21.1 and 26.3. Prepared meat, leather, wool, refusal, main-inventory shears and post-interaction stop pass both. Primary cooking restoration and pending-attack AIR fail; remaining actors open |
| Renewable farms and harvests | Harvest mature crops, retain seed stock, replant, and finish repeated cycles. Support bounded milk, egg, shearing, and honey workflows. | Resource actors, inventory capacity, permitted terrain | Open |
| Fluid buckets and bucket falls | Fill and use buckets with server confirmation. Offer bucket falls only when the selected adapter can execute them. | Native interaction actors, remainder accounting, protected terrain, survival cancellation | Open |
| Containers and inventory overflow | Learn permitted containers from observed menus, withdraw exact stock, deposit, and recover capacity without discarding protected items. | Owned menu transactions, stock generations, cancellation drain | Open |
| Travel, exploration, and waypoints | Expose coordinate travel, bounded exploration, player following, waypoint management, and useful cache controls. | One foreground task owner, movement lease, world identity | Open |
| Schematics, construction, and tunnels | Execute a bounded immutable schematic with material accounting, protected terrain, cancellation, and resumed progress. | Placement receipts, resource acquisition, owned builder snapshot | Open |
| Elytra travel | Execute bounded flight with real support checks, fuel reserves, landing, and restoration. | Native flight executor, travel ownership, fall recovery | Open |
| Survival and recovery | Finish or refuse retreats without stale-route failures. Add projectile, fire, fall, sleep, and explicit respawn recovery workflows. | Shared foreground arbitration, movement drain, native survival actors | Ended-route fix pushed at `6119c80`; six prepared preservation controls pass on 1.21.1 and 26.3. Changed-branch and fresh Survival acceptance remain open; other actors open |
| Nether, structures, and progression | Enter and return through portals, acquire dimension resources, find supported structures, and finish End progression. | Travel, fluids, combat, storage, dimension transition receipts | Open |
| Modern equipment mechanics | Execute modern smithing with templates and exact slot and remainder accounting. | Version-owned recipe discovery and menu transactions | Open |
| Player services and remote commands | Follow a chosen player, hand off requested items, and accept bounded commands from explicitly authorised senders. | Foreground task owner, recipient identity, inventory receipts, sender authentication | Cooperative workflows first; open |

The audit also identified two shared capabilities and five candidate advantages. Existing mining, crafting, stock goals, protection, recipe discovery, and diagnostics need verification after the new actors are integrated.

## Delivery order

The first unit fixes the evidenced stale retreat lifecycle without interrupting a working route. Acquisition and container transactions establish the dispatch and receipt boundaries used by later actors. Travel and cooperative player services follow that foundation. Renewable farms, fluids, construction, equipment, and advanced survival then feed dimension progression and flight.

Implementation uses one writer in the main checkout. Independent agents inspect designs and diffs without changing shared source. Builds use one Gradle worker with no persistent daemon. Native verification uses one isolated game client at a time, with no build running beside it.

Existing checks run for each completed unit. New test cases require Lu's approval. On 2026-10-08 UTC, Lu approved these focused groups in the existing native verifier:

- Requested meat quantities and cooking, wool and shears, protected or wrong-item refusal, and cancellation after interaction.
- Exact container withdrawal/deposit, full-inventory stash, stale/protected stock, revocation and stop.
- Coordinate travel, moving-player follow, item handoff with a full recipient inventory, and unsigned or replayed remote-command refusal. Real certified signed-peer success remains separate.
- Air recovery during unresolved animal work; crop/fluid replanting, seed retention, remainders, milk/eggs/honey, refusal and cancellation.
- A small supplied schematic/tunnel; flight launch, fuel, landing and equipment return; projectile/fire/fall reactions; sleep/wake and one explicit respawn; refusal, interruption and ownership restoration.
- Portal round trips and ignition, dimension resources and return, stronghold/temple discovery, and guarded End entry/combat/exit. A natural progression attempt remains separate from prepared fixtures.
- Netherite upgrades and explicit armor trims, exact components and template/input leftovers, insufficient room or protected-input refusal, and stop/stale-session recovery.

Other new cases still need approval. No new test files are authorised. Logs and game receipts must identify the production artifact, supplied fixture state, outcome, cancellation state, and any unverified behavior.

The selected shared design uses typed native work and an adapter-owned lifecycle with explicit observed outcomes. [Native acquisition boundaries](NATIVE-ACTIONS.md) records the contract and the alternatives considered.

The first acquisition unit publishes requested beef, porkchop, mutton, leather, and all sixteen wool colors on 1.21.1 and 26.3. Cooking uses the existing recipe and furnace path. The actor refuses protected, named, tame, juvenile, leashed, mounted, burning, or otherwise unsafe targets. It observes each attack before another send, retains uncertain effects through cancellation, and returns borrowed ordinary shears through the owned inventory transaction. Ordinary stock can satisfy a request after drain; it does not prove which pickup caused that gain.

Pending animal effects and inventory receipts can yield movement to AIR recovery while retaining their evidence. Every later nonobserver drain checks actual movement ownership before stopping a route or restoring the hand. Static reviews pass, and both target builds and existing checks pass. Fourteen approved animal cases are present in the existing native verifier. The [first three runs](evidence/resource-actions/main-18d9d1e-animal-initial.json) pass beef acquisition on both artifacts and fail main-inventory wool on 1.21.1: a tossed drop still occupies unsupported Y=65 above the Y=64 pad when both pickup calculations fail. It retains one wool, returns ordinary shears to main slot 20 with wear 1, and ends with selection 0, empty cursor and full health. The grounded-drop admission fix passes independent review, both target builds and the [main-inventory wool reruns](evidence/resource-actions/main-8592d5f-animal-gates.json): both collect four wool, return shears to main slot 20 and restore selection, input, settings and cursor. The same record passes post-interaction stop in both clients, with one attack, zero acquired output and no later send. Pending-attack AIR fails on 1.21.1: three swimming attempts remain at the first route step, then the player drowns once. The original unknown attack remains retained without a second send or observed damage. That batch stops; the modern attack and both transfer cases remain unrun. All five original screenshots pass anonymous byte/hash read-back and logged-out decoding, followed by deletion of their local original and staging copies. This unit does not establish the remaining resource workflows or fresh Survival reliability.

The [remaining resource and refusal runs](evidence/resource-actions/main-8592d5f-animal-resources.json) pass beef, partial beef stock, porkchop, mutton, leather, white/red wool, wrong-component refusal and protected-animal refusal on both frozen `8592d5f` artifacts: eighteen passes. Primary cooking produces four cooked beef and completes the job, but fails the whole-case restoration gate because furnace placement leaves selected slot 5 instead of 0. Other unpublished failure predicates remain unproved; modern cooking remains unrun. All nineteen original captures, including that failure, pass anonymous byte/hash read-back and logged-out decoding; their thirty-eight local original and staging PNGs are deleted. Compatibility commit `8997b5d` passes all twenty-four [CI build/check jobs](https://github.com/luinbytes/lodekeeper/actions/runs/37863657282). Its older-family verifier binding correction does not expand requested-animal runtime support.

The AIR obstruction correction passes three independent source reviews and the 1.21.1/26.3 builds, with 313/268 existing checks. Manual swim planning and movement now check actual player bounds, native block sweeps, living push bodies, vehicle push reach and local loaded-chunk coverage. A first soft overlap can admit only a guarded separating edge. A blocked turn retains the current waypoint and centers under the same observation deadline. The unchanged pending-attack, pending-transfer and roof-escape native reruns remain pending. Native AIR checks the current body and offered endpoints; full entity clearance along its complete native path remains unproved. The earlier drowning record remains a failure of its named artifact.

## Evidence rules

An inventory total alone does not settle an action that still owns a menu, route, or pending mutation. The action must drain first and provide its receipt. A container observation expires when its world, generation, menu, or expected contents change.

A route that ends before arrival can drain when the current threat list is empty. Defense completes only after cancellation and a fresh threat check. The ended route earns no arrival credit. Working retreat routes retain their admitted destinations. Unsupported mechanics fail before their plans mutate the world.

[The six prepared controls](evidence/survival-safety/main-6119c80-parity-retreat-controls.json) pass creeper clearance, four-block staircase ascent, and a health-six interruption in both families. They load frozen production and verifier output from `6119c80`, despite animal source edits at launch. Each artifact stays unchanged and has no class-origin violations. The low-health cases correctly pause with the original request. The creeper cases finish the bucket goal and retain a station whose cleanup guard refuses recovery. These controls do not directly witness the new ended-route branch. The [original screenshots](https://github.com/luinbytes/lodekeeper/releases/download/main-gameplay-evidence/main-6119c80-parity-retreat-screenshots.json) pass anonymous byte/hash read-back and logged-out browser decoding; their local original and staging PNGs are deleted.

[One fresh 26.3 Survival run](evidence/survival-safety/main-6119c80-parity-natural-modern.json) on those frozen artifacts fails after 460,248 command ms at a later no-safe-dry-retreat search. It starts at `(-7.5, 77, 18.5)` on seed `483920105`, with no supplied stock, the existing 900-second bound, and isolated `allowDownward=false`. Final health is 20, minimum health is 8.833329, deaths are zero, and the cursor is empty. All nine diamond gear targets are absent; one iron pickaxe and one raw diamond remain. The client exits normally without timeout or class-origin violations. No ended-route-clearance branch emits. This run does not establish the new branch, fresh reliability, or comparative performance.

Compile support is recorded per game profile. Runtime support is recorded per executed case. Comparative claims require compatible game versions, equivalent starting saves, every counted failure, and at least five interleaved paired attempts per case.

Decisions and verification gates are recorded in [the decision log](evidence/parity-decisions.tsv). The audit baseline is [main at c0db9a5](https://github.com/luinbytes/lodekeeper/tree/c0db9a547fb75ba0fd40927e5ee5280458e85cf9).
