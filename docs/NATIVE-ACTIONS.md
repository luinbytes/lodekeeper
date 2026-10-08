# Native acquisition boundaries

Native acquisition extends the existing planner and foreground task owner. Core describes work and requirements. Each Fabric adapter owns entities, menus, packet evidence, movement, and the pending action.

## Typed work survives planning

`NativeAcquisitionSource` preserves immutable operation facts through `CatalogSnapshot`. `PlanStep.nativeWork` carries those facts under `PlanKind.NATIVE`. The old non-native constructor remains available. Step-copy sites retain native work explicitly.

Animal output quantities describe requested stock. They do not predict the number of kills or claim a fixed random drop. The engine executes the first step and replans from observed stock after completion or partial yield. Native world acquisition has a positive heuristic cost. Finite stored stock retains its existing reference, generation, and allocation budget.

Actor capability entries publish only implemented source modes. Source restrictions cannot change an operation under the same source identity. Generic CUSTOM execution remains unsupported. Native work cannot justify resource exploration until an actor supplies an explicit search contract.

## One action owns evidence and drain

An adapter-private `NativeRun` exposes ticking, drain requests, pause, session abandonment, and a release fence. Its outcomes distinguish pending work, delivered output, partial stock, completed drain, and a blocker.

The engine owns the current job, queue priority, and reservation floors. The action owns its target and pending native effect. Existing provenance owns server observations. Callbacks record evidence. Client ticks advance the action.

The generic inventory-count shortcut cannot complete native work. Reset cannot discard an unsafe action handle. Stop forbids new targets and effects, then drains pending evidence and owned movement. Resume observes an uncertain interaction without resending it. Session or ownership changes invalidate old evidence and prevent restoration over another owner.

The same rules participate in recipe refresh, screen handling, food, equipment, maintenance yielding, health and air recovery, pause, stop, disconnect, and exception recovery. Source publication follows that integration.

## The first resource actor

`AnimalHarvestAction` replaces the separate hunger hunter in both primary adapter families. Requested modes acquire ordinary cow beef and leather, pig porkchop, sheep mutton, and exact-color wool through shearing. Urgent food keeps its existing raw-or-cooked acceptance and native food-safety policy.

Requested kills initially use a safe empty hand. Shearing uses selected ordinary shears with conservative durability reservations. Animal eligibility excludes babies, named, tamed, leashed, mounted, burning, unsafe, and protected targets. Entity interaction checks the animal bounds against the current unlocked claim scope immediately before each native effect.

Before interaction, the actor captures coherent ordinary server stock and nearby matching drops. It pins the target and observed new drop identities. Owned pickup requires later matching inventory evidence and safe cancellation. Death, route arrival, or disappearance alone cannot prove delivery. Ambiguous merges and immediate pickups can yield coherent stock without claiming exclusive causal credit.

Ordinary-component counting applies only to newly supported native commodity outputs and their explicitly ordinary requirements. Existing enchanted-tool and equipped-stock goal semantics remain intact.

Attempt bounds retain the existing 32-block scope, 30-second target deadline, 32-attack cap, and failed-target cache. One engine-owned quota permits at most 32 animal attempts and ten minutes of accumulated active work per logical job. Replanning and resume cannot replenish it.

## Storage and cooperative work

Permitted containers publish finite stock only from acknowledged menus. References bind the physical slot, native session, permit generation, and observation generation. Reopening and content changes invalidate old observations. Retrieve and deposit use the existing exact transfer and coherent full-ACK rules, including an empty acknowledged cursor and protected floors.

Travel, player following, deposit, and delivery are foreground goals. They do not produce synthetic recipe items. Acquisition children share their parent's job and payload reservations. Player services bind UUID identity. Delivered status requires recipient evidence. Remote commands require explicit sender authorisation and current-session authentication.

## Alternatives and verification

Two independent designs were compared. A private CUSTOM decoder registry preserves the current step format but introduces an implicit argument schema. Typed native work exposes that schema to the compiler. The selected design combines typed work with retained action outcomes, keeping the existing planner and native receipt owners.

A universal effects protocol would migrate existing station and survival routines before delivering the first provider. The selected design keeps native operation knowledge inside concrete actors.

Existing checks and exact artifact compilation precede support claims. Native cases use one isolated client at a time and disclose supplied resources. Lu approved focused new cases for requested meat quantities and cooking, wool and shears accounting, protected or wrong-item refusal, and cancellation after interaction. New cases outside that set require approval.

The eleven audit groups remain tracked in [Parity implementation](PARITY-IMPLEMENTATION.md). This design alone establishes no runtime support or comparative advantage.
