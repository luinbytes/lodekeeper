# Navigation core

`nav` is a Java 17 library with no Minecraft or third-party runtime dependencies. It searches a bounded set of block-centered player stances with incremental A*. The Fabric adapter owns world reads and actual input; it must call the planner on the client thread and give each tick a small expansion and time budget.

## Integration API

```java
Planner.Options options = new Planner.Options()
    .maxNodes(16_384)
    .maxDrop(3)
    .allowBreaking(true)
    .allowBuilding(true)
    .allowParkour(true)
    .placements(availableFullBlocks, itemToken);

Planner planner = new Planner(terrain, startX, startY, startZ,
    Goal.near(targetX, targetY, targetZ, reachRadius), options);
NavStatus status = planner.advance(128, 1_500_000L); // max expansions, nanoseconds
Path path = status == NavStatus.FOUND ? planner.getPath() : null;
```

Call `advance` again on a later client tick while it returns `IN_PROGRESS`. The result distinguishes `FOUND`, `NO_PATH`, `PARTIAL_LIMIT`, `CANCELLED`, and `STALE`. A budget of zero performs no work. One stance expansion is atomic and may run slightly over the supplied time budget. `PARTIAL_LIMIT` means the node cap prevented an exhaustive result; it also provides a path to the closest discovered frontier when one exists, so the executor can make safe progress and replan. `getExpandedNodes`, `getDiscoveredNodes`, and `getOpenNodes` support diagnostics.

`Path.length()` and `Path.step(index)` expose an immutable snapshot. Each step contains a `Movement` from its predecessor and zero or more explicit actions that must finish before movement. Step zero is the start stance. `BREAK_BLOCK` actions carry an adapter state token; `PLACE_BLOCK` actions carry an adapter item token. Recheck the token, reach, raycast, inventory, world result, and current route edge in the executor. The path's placement count is a reservation total; the planner never allows a route prefix to spend more than `Options.maxPlacements`.

`Goal.exact` requires one feet-block stance. `Goal.near` uses a 3D Euclidean radius; it is useful for pathing to an interaction area, but the executor must still perform a live reach and raycast check.

## Terrain contract

`Terrain.probeStance(feetX, feetY, feetZ, out)` reports a centered player stance, not the raw block state at that coordinate. The adapter must set every field on every call. `loaded` is false if any geometry needed for the full player AABB or its support is unknown or unloaded. `bodyClear` checks the actual player bounding box against actual collision shapes, including neighboring blocks. `fullSupport` is true only when a conservative full-block support surface safely supports the centered player; partial slabs, stairs, fences, fluids, and uncertain shapes must not be treated as ordinary floor. `hazard` rejects dangerous or policy-blocked contact. `water` and `climbable` summarize contact within the player box.

If the stance is blocked by at most two mineable body obstructions, report those exact block coordinates, stable state tokens, and positive abstract costs in `breakTargets`. Set `breakCount` above two to reject more complicated obstructions. Never list the floor as a body obstruction. `canBreakFrom` must verify current reach and any adapter policy such as tool availability. Search cost is a relative weight; scaling estimated mining ticks by ten works well. The executor still selects the tool and waits for the block to disappear.

`isMotionClear` checks the full swept player box along the stated line and sine arc, rejects unknown terrain, collision and hazards, and ignores only the source and destination probes' listed break cells because those are removed before motion. Override the overload that receives both `sourceAfterBreak` and `destinationAfterBreak` to support routes through successive mineable obstructions; the default rejects a virtual cleared source. Sample finely enough to avoid missing thin collision shapes. Use the actual player dimensions. `canPlaceBridgeFrom` must validate an empty replaceable target, a valid side face/reach, and the selected full-block inventory item. `supportWasPlanned` means an earlier bridge placement in this route will provide the source face when execution reaches this edge.

`revision()` should identify changes to terrain sampled by this search. Increment it for relevant block changes and chunk load/unload events so an in-progress search becomes `STALE`; avoid changing it for unrelated distant updates if the adapter tracks sampled dependencies. After `FOUND`, validate the next movement edge against live terrain before each input. The planner never requests chunk loads or reads world state off-thread.

Coordinates use the common packed block range of signed 26-bit X/Z and signed 12-bit Y. `Position.pack` rejects out-of-range coordinates rather than wrapping them.

## Movement coverage and limits

- Supported cardinal and diagonal walking, with both cardinal corner stances checked before a diagonal.
- One-block supported jump-up edges, swept through an elevated arc.
- Supported drops up to the configured limit, capped at three blocks.
- Optional two- and three-block cardinal parkour gaps when the adapter certifies the swept arc.
- Optional water and climbable movement when the stance probe confirms contact.
- Optional mining of up to two reachable body obstructions before a stance.
- Optional one-block-at-a-time bridge construction over an unsupported cardinal stance, with an item token and a placement count reserved in the A* state.

Navigation intentionally uses full-block support and conservative body clearance. It does not claim universal support for arbitrary collision geometry, flying, vehicles, mounts, doors, ladders beyond adapter-confirmed climbability, modded movement abilities, or every mechanic added by another mod. Those require explicit terrain/execution providers and their own validation. Unloaded space, hazards, and adapter uncertainty remain blocked.

## Performance and state

The frontier, node records, indexed binary heap, position hash table, and bounded stance cache use primitive arrays. Path objects are allocated only after success. Defaults cap a search at 16,384 nodes; callers can lower the cap for tighter machines. `advance` accepts an expansion ceiling and nanosecond budget, and no background worker touches live world data. A search caches only its stance probes; the adapter revision contract prevents those values from being reused after a relevant world change.

## Bounded resource exploration

When a known gathering source is absent from loaded terrain, the client can walk to a new surface waypoint and search again. `ExplorationFrontier` evaluates at most 224 candidates with per-tick probe/time budgets, proposes only loaded, hazard-free, fully supported stances, and remembers attempted eight-block regions. Every started attempt consumes the configured limit, including searches interrupted before choosing a waypoint. The client independently requires arrival at the exact waypoint before clearing discovery exclusions and replanning.

Exploration routes allow a one-block drop and disable breaking, building, parkour, swimming and climbing. Walking lets Minecraft load subsequent chunks normally; the selector never forces chunk loads. Default limits are 32 attempts and 512 blocks from the goal's starting position, bounded to 128 attempts and 2,048 blocks. These limits constrain surface search; they do not promise a route across arbitrary terrain or a persistent world map.

```text
!lk config allowExploration true
!lk config explorationAttempts 32
!lk config explorationDistance 512
```

For `wood`, a single palette-pruned union search finds eligible registered log sources, caches their positions and preserves nearest-output ordering. A nearby dropped log receives priority within the same bounded candidate queue. If its acquisition requirements cannot be planned, another discovered output is tried before stopping. Catalog and inventory snapshots are constructed after discovery finishes, avoiding a full modded-catalog copy on every discovery tick.
