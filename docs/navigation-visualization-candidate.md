# Rich navigation visualization candidate

This candidate keeps the game renderer behind `NavigationOverlay.Lines`. The existing render API signatures stay intact. `NavigationSnapshot` keeps its existing constructor; its optional `scene` value carries copied native movement metadata and bounded world markers.

The scene is attached to the existing navigation snapshot so the tick adapter can publish route geometry and native movement metadata as one immutable value. A separate engine getter would add a second handoff and require edits to the engine's guarded navigation accessors. Renderer-side native queries were rejected because they would cross the thread boundary and make line generation depend on live game state.

## Data flow and bounds

`MovementController.samplePath()` runs on the client thread at its existing four-tick cadence. It copies at most 256 route positions and 256 movement kinds from `IPath.positions()` and `IPath.movements()`. The staged generated `IPath.java` declares `List<IMovement> movements()`. The generated owned `Movement.java` exposes the executor-populated `toBreakCached` and `toPlaceCached` lists. A curve is enabled only when the corresponding movement is an instance of the staged owned `dev.lodekeeper.navigation.kernel.pathing.movement.movements.MovementParkour` class. Waypoint height and distance do not classify a jump as parkour.

The next movement's already-populated `Movement.toBreakCached` and `toPlaceCached` lists supply planned native action outlines. The capture skips null caches, copies at most 16 coordinates, and never calls `toBreak`, `toPlace`, or a world accessor. Those lists are native path plans; they do not prove that an input was sent or that the server changed a block. `ActionEvidence` reserves separate states for an input attempt and server confirmation so a later adapter can report those events without relabeling a plan.

`WorldVisualization` snapshots at most every four client ticks. Claim regions come from the current immutable `WorldProtection.PolicySnapshot`, only when its world scope is known and unlocked. It also retains source markers supplied through `NavigationSceneSnapshot`, then keeps the nearest 64 markers within the configured distance. Claims can render while automation is idle. All displayed line segments are clipped to a sphere with a radius clamped to 8..128 blocks.

Navigation code contains only primitive coordinates, enums, and immutable records. The render thread sees a `NavigationSnapshot`, a `NavigationSceneSnapshot`, and the existing line callback. It makes no Minecraft world queries. The per-frame upper bound is 1,852 line segments: 64 route edges total, with at most 16 replaced by eight-segment parkour previews (176 route lines), 512 search-node lines, 384 action-box lines, one target box, and 768 marker-box lines.

## Scene types

`NavigationSceneSnapshot` stores byte movement kinds, up to 16 `WorldAction` records, and up to 64 `Marker` records. A claim marker stores inclusive block bounds and an optional preferred-station flag. Station and backfill markers are point bounds. Marker kinds encode the required provenance: `CONFIRMED_OWNED_STATION`, `STATION_RECOVERY_PENDING`, `BACKFILL_PENDING_MATCH`, and `BACKFILL_MATCHED`.

The purple parkour curve is a conservative geometric preview attached to an actual native `MovementParkour` edge. It does not model Baritone input timing or guarantee the player will reach the landing point. The line-only render API has no text channel; the `showParkour` setting and this document name the curve as a preview. Purple means preview. Gold means confirmed station ownership. Blue outlines claims; green outlines preferred-station claim regions.

## Root-owned adapter follow-up

The scene marker API is ready for station and backfill sources, but those provenance adapters are not part of this candidate. To attach them, extend the existing guarded `AutomationEngine.visualizationNavigation()` method after it obtains the movement snapshot:

```java
NavigationSnapshot snapshot = movement.visualization(includeNodes);
return snapshot.withScene(snapshot.scene().withAdditionalMarkers(visualizationMarkers()));
```

`visualizationMarkers()` should return at most 64 marker records. Build station markers only from `OwnedStationLedger.records()` in the current session. Emit `confirmedStation` only for those confirmed records. Emit `stationRecoveryPending` only when the same confirmed record is the active recovery target. Do not use `knownStations`, nearby-station scans, or discovered locations as ownership evidence.

Build backfill markers only from the future backfill ledger's confirmed break receipts. Use `backfillPendingMatch` while a confirmed broken position still awaits matching surplus material. Use `backfillMatched` only after the adapter has evidence that the exact restoration material is available and paired with that position. Do not derive either marker from a mining target, route waypoint, or predicted block change. Keep the existing `visualizationActive`, pause, and world-session guards around the engine method.

## Proof limits

This source candidate has not been compiled or run in Minecraft. The root owner must compile the relevant profiles and inspect the rendered depth-tested overlays. The navigation tests cover geometry clipping, preview type gating, marker toggles, finite output, and snapshot limits.

The candidate can show planned native break/place targets when Baritone has populated its caches. A null cache produces no outline. It does not claim that a native input is active or that the server confirmed an edit. Station ownership and matching backfill remain absent until the root-owned adapters provide their records through the marker API.
