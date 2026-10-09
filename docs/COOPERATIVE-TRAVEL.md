# Cooperative travel commands

Travel uses the foreground queue shared with acquisition and projects. Each accepted command prints its job token. `!lk queue` lists queued jobs, and `!lk status` reports the current job.

The 1.21.1 and 26.3 artifacts compile with these commands. Coordinate travel passes the prepared native case on both. The first primary follow case fails during peer enrollment before sending the command; modern follow remains unrun. Other adapters refuse travel before starting a route. The [initial native evidence](evidence/cooperative/main-5108f5b-travel-initial.json) preserves all three results.

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
