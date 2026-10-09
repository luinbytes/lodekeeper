# Cooperative travel commands

Travel uses the foreground queue shared with acquisition and projects. Each accepted command prints its job token. `!lk queue` lists queued jobs, and `!lk status` reports the current job.

The 1.21.1 and 26.3 artifacts compile with these commands. Coordinate travel passes the prepared native case on both. After the peer PLAY correction, 1.21.1 passes moving-player follow, original expiry, control restoration, peer teardown and fresh post-idle observation. The 26.3 case enrolls the peer and follows both moves but returns REFUSED at the progress watchdog; its outer case then times out. Other adapters refuse travel before starting a route. The [initial native evidence](evidence/cooperative/main-5108f5b-travel-initial.json) preserves all three results.

| Command | Behavior and limits |
| --- | --- |
| `!lk goto <x> <feetY> <z>` | Travels to a loaded, supported feet stance within 256 horizontal blocks. Its two-minute deadline starts at admission. |
| `!lk explore [radius [segments]]` | Visits observed safe destinations when `allowExploration` is enabled. Defaults to radius 64 and four segments. Radius is 16 through 256, with one through sixteen segments and a two-minute deadline. |
| `!lk follow <exactName\|UUID> [seconds]` | Pins a currently loaded player by UUID. Defaults to 120 seconds, with a limit of one through 600 seconds. Waits within two blocks. |
| `!lk waypoint set <name>` | Saves the current safe integer feet stance for this world and dimension. Names use lowercase letters, digits, underscores or hyphens and contain at most 32 characters. |
| `!lk waypoint goto <name>` | Queues coordinate travel to the saved stance under the current travel limits. |
| `!lk waypoint list` | Lists waypoints in the current world and dimension. |
| `!lk waypoint remove <name>` | Removes that waypoint from the current scope. |
| `!lk cache status` | Reports source terrain and travel forecast state. |
| `!lk cache clear` | Clears source travel forecasts and query hints once owned work has released movement, settings, inventory and menus. Preserves waypoints and native region storage. |
| `!lk cancel <jobToken>` | Cancels that queued or active foreground job and retains its unresolved cleanup. |
| `!lk stop` | Stops foreground work and clears queued requests after owned cleanup drains. |
| `!lk pause` / `!lk resume` | Pauses and resumes the original request. Its admission deadline continues during pause. |

Routes use movement without breaking, placing, inventory automation or automatic tool changes. Fifteen seconds without observed progress ends a route. Follow refuses a player absent for five seconds and retains the same UUID if its native entity changes. Unsafe poses, unsupported heights, unloaded destinations and foreign process ownership stop admission.

Food and shield preparation use guarded acquisition children before travel. They retain the parent's original deadline and shared stock reservations. Cancellation inhibits new recipe sends. A pending inventory click remains owned until its real receipt and lawful return settle. AIR recovery has priority over travel.

Waypoints use at most 64 labels per world and dimension across sixteen scopes. An unreadable waypoint file blocks edits until its contents are inspected.

The [parity implementation ledger](PARITY-IMPLEMENTATION.md) records native verification and the remaining player-service work.

The [5d2ef07 follow evidence](evidence/cooperative/main-5d2ef07-follow-initial.json) records both results and all 24 passing CI jobs for that exact commit. The primary original capture passed anonymous byte/hash read-back and signed-out browser decoding; its two local copies were deleted. Modern produced no capture. The goal-settlement source correction has separate build and native gates.

The [follow goal-settlement correction](evidence/cooperative/follow-goal-settlement-source-r2.json) passes independent source review and builds for 1.21.1, 26.3 and the mapped 1.21.11 adapter, with 319, 274 and 324 existing checks. Waiting now observes the current owned native block goal and the same pinned target, while preserving the original deadline, missing-target and progress bounds. The unchanged native follow acceptance cases remain pending for these corrected jars.
