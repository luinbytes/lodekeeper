# Collision-shape navigation

The navigation core and both native adapter families now represent grounded feet heights in sixteenths. Grounded walking uses actual player dimensions and native collision faces for paths, farmland, slabs, stairs and snow. A mixed-terrain physical course is the verification gate for this implementation; the current [1.21.1](evidence/1.21.1-shaped-navigation/README.md) and [26.3 development-source checks](evidence/26.3-shaped-navigation/README.md) pass. Exact release artifacts and ordinary-world acceptance remain separate gates. Released Preview 2 contains only nearby fractional-start recovery; it does not contain this new traversal implementation.

## Implemented core contracts

Exact feet height participates in node and probe-cache identity, goals, path steps, live edge checks and frontier selection. Negative heights use floor division; the existing packed coordinate range is preserved. Legacy integral adapters retain same-height walking through conservative default methods; fractional geometry requires an adapter proof.

Candidate enumeration holds at most 64 distinct heights, grounded candidate checks are capped at 64 per expanded node, and multi-stance goals hold at most 128 entries. Rectangle-union support proofs use a reusable 128-rectangle buffer and fail closed on overflow or malformed geometry. Frontier collection attempts consume the per-tick budget even when they return no usable stance.

Continuous-height swimming and climbing retain their medium and actual body checks. Jumps, drops, parkour and bridges require integral endpoints and full launch/landing support. A planned bridge can leave its virtual support for a real, same-height bank; execution still requires observed placement and live validation. Grounded movement supports a separate shape-aware walk proof, with a rise cost that preserves the existing minimum cost per full block.

These core contracts also have focused Java regressions. Native gameplay, exact artifact provenance and ordinary-world acceptance remain separate gates.

## Native walking and execution

Both adapters enumerate native boxes using callbacks into reusable buffers. Collision queries include neighboring cells and the row below the feet, so a 1.5-block fence cannot disappear from a body or sweep query. Shape extents, player dimensions, candidate counts and proof counts are checked; unsupported geometry and overflow reject the route. Context, pose, held-item and observed region changes invalidate cached probes.

A centered search stance requires at least half its footprint on the exact face and full lower support coverage. Actual player and transit probes require positive exact contact and full lower coverage. Lower coverage can span one block beneath the leading face, allowing a player's trailing foot to cross a stair; each physical rise still respects the live step height, capped at 9/16. This does not permit a bare full-block ledge as WALK.

Walking traces shape-boundary contact events and before/after samples. Critical contact times survive merges with regular samples, including the float-valued native player width. Collections and walk proofs are each capped at 256. A stale grounded flag while leaving a higher floor permits at most four idle settling ticks only after proving clear vertical descent to nearby real support; it sends no forward or world-action input while waiting.

One-block JUMP execution centers its launch, lifts before forward motion and releases jump input after takeoff. Its live continuation proves vertical lift below landing height, then revalidates the horizontal remainder before forward input even without another terrain revision. Overlapping constant-height sweeps cover the full landing-to-apex band, including low obstacles and ceilings ahead; this conservatively rejects some paths a more precise physics model might allow. The model assumes an ordinary vanilla jump; boosted or custom movement requires a separate proof. Fractional jumps, drops, parkour and bridge placement remain restricted to their existing integral support contracts.

Resource rejection logs and final blockers retain the actual position and navigation failure instead of silently losing the cause.

## Represent actual grounded positions

Keep the navigation core independent of Minecraft and on Java 17. A grounded stance needs an integer block coordinate plus a separate fractional feet height in sixteenths. The existing packed coordinate uses 26 + 26 + 12 bits, so scaling packed Y would silently sacrifice range; the fraction must be part of node/cache identity separately. Block actions retain ordinary integer block positions.

Adapters should enumerate candidate support faces from loaded native collision shapes into a bounded reusable buffer, then check the actual player body against every touched collision box. Use actual shape heights rather than inferring them from block names. Only quantizable, loaded and supported stances enter the search; unknown geometry, candidate overflow and unloaded terrain fail closed. Body dimensions, target feet height, eye position and interaction reach must agree throughout search, route execution and raycast validation.

Native shape APIs differ between Yarn and Mojang families; the current integration uses their direct box callbacks. Dynamic or neighbor-dependent shapes require revision-aware caching. Buffer reuse avoids temporary collections in these loops; measured allocation and tick evidence are still required before any zero-allocation or comparative performance claim.

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

## Drop-catching fixture correction

A [26.3 failed course](evidence/26.3-shaped-navigation-drop-failed/README.md) crossed all checkpoints and mined the coal, but the randomized item fell off the one-cell landing pad to Y=-60. Saved native entity data retains the coal 128 blocks below the elevated route. The follow-up widens the protected pad after the forced jump; the narrow course, all 15 checkpoints, completed movement observations, exact inventory, full health and preservation assertions remain required. No production pickup change follows from that fixture failure.
