# Collision-shape navigation: next implementation lane

This is a proposed follow-up, not implemented traversal or gameplay evidence. The current dirt-path/farmland recovery only escapes a nearby fractional start to safe full-block ground. Full traversal across paths, slabs, stairs and snow remains open.

## Represent actual grounded positions

Keep the navigation core independent of Minecraft and on Java 17. A grounded stance needs an integer block coordinate plus a separate fractional feet height in sixteenths. The existing packed coordinate uses 26 + 26 + 12 bits, so scaling packed Y would silently sacrifice range; the fraction must be part of node/cache identity separately. Block actions retain ordinary integer block positions.

Adapters should enumerate candidate support faces from loaded native collision shapes into a bounded reusable buffer, then check the actual player body against every touched collision box. Use actual shape heights rather than inferring them from block names. Only quantizable, loaded and supported stances enter the search; unknown geometry, candidate overflow and unloaded terrain fail closed. Body dimensions, target feet height, eye position and interaction reach must agree throughout search, route execution and raycast validation.

Native shape APIs differ: older Yarn families expose `getBoundingBoxes()`, modern Mojang families expose `toAabbs()`. Dynamic or neighbor-dependent shapes need revision-aware caching. Buffer reuse and direct loops are performance goals; they need measured allocation and tick evidence before any zero-allocation claim.

## Land in bounded stages

1. Grounded walking: propagate fractional height through terrain probes, path steps, goals, heuristics, partial paths and frontier selection. Permit adjacent supported steps within the physical step limit. Preserve diagonal corner checks, continuous support, body sweeps, loaded guards and region revision validation. Re-probe the exact support height before each grounded edge.
2. Physical landing actions: extend jumps, safe drops and parkour only when fractional launch and landing are covered by the existing bounded trajectory validation. Preserve airborne continuation behavior while validating the landing stance.
3. Placement geometry: keep bridging restricted to its current full-block support contract until shape faces and placement raycasts are modeled. Relaxing the current integer `y - 1` support assumption would be unsafe.

Existing node, cache, expansion, time and retry caps remain. No world reads move to worker threads. Core changes must be integrated with both shared-Yarn and modern adapters; all exact stable profiles compile separately before a release.

## Acceptance matrix

| Scenario | Required observation |
| --- | --- |
| Full blocks | Existing routes and interaction behavior remain correct |
| Path and farmland | Begin grounded below integer Y; walk and replan without mining the starting floor |
| Slabs and oriented stairs | Actual top/bottom faces and body clearance determine each step |
| Snow layers | Each native collision height, including thin layers, is treated explicitly |
| Edges and low ceilings | Continuous support and swept body clearance reject unsafe transitions |
| Hazards and unloaded borders | Fail closed without treating unknown terrain as air |
| Changed terrain and partial routes | Revision checks invalidate stale stances and preserve the requested goal |
| Fractional interactions | Normal reach/raycast rules still govern mining and station use |
| Jumps and parkour | Confirmed fractional launches and landings, with bounded fall limits |
| Bridges | Unsupported fractional placement remains blocked until its own executor is verified |

Existing planner, edge validator, exploration and region-revision regressions must remain green. Focused new regressions are authorized for meaningful navigation hazards; native scenarios remain necessary to establish physical player behavior.

## Pickup investigation boundary

A delayed eighth log in a controlled 26.3 run eventually completed the goal and all remaining checks. It does not establish a persistent pickup bug. Cached native code shows movement packets synchronize an entity's on-ground flag in both 1.21.1 and 26.3; spawn packets can omit that state initially. Transient staleness is plausible, but persistent false state was not observed.

Cached native bytecode establishes the ordinary on-foot pickup query on both inspected versions: 1.21.1 `PlayerEntity.tickMovement()` uses `getBoundingBox().expand(1, 0.5, 1)` before `World.getOtherEntities`, while 26.3 `Player.aiStep()` uses `getBoundingBox().inflate(1, 0.5, 1)` before `Level.getEntities`. Returned non-removed entities dispatch through the native collision/pickup callback. Vehicle behavior uses a different query.

The adapter candidate heuristic expands the box by `(1, 0, 1)`, so it omits the native vertical margin and may miss candidates at that margin. A resulting premature route change or pickup failure was not demonstrated. No production pickup change is included in this release; a reproducible contact scenario with server observations should establish the impact first.
