<p align="center"><img src="docs/assets/lodekeeper-banner.svg" alt="Lodekeeper - Give it a goal. Let it find the way." width="100%"></p>

<p align="center">
  <a href="LICENSE"><img alt="Licence: MIT" src="https://img.shields.io/badge/licence-MIT-abf49b?style=flat-square&amp;labelColor=192922"></a>
  <img alt="Fabric client mod" src="https://img.shields.io/badge/Fabric-client_mod-abf49b?style=flat-square&amp;labelColor=192922">
  <img alt="Status: in development" src="https://img.shields.io/badge/status-in_development-eac97a?style=flat-square&amp;labelColor=192922">
</p>

# Lodekeeper

**Give it a goal. Let it do the work.** Lodekeeper is a Minecraft Fabric client mod for survival resource gathering and crafting. It uses ordinary player actions to collect resources and complete crafting chains. Preview 10 remains downloadable. Preview 11 is an unreleased development candidate with an owned navigation kernel.

> **Preview 10 full loadout record.** Its exact jars made all five tools and equipped all four armor pieces in fresh survival worlds. The runs took 11:59 on 1.21.1 and 9:33 on 26.3. These timings belong to Preview 10 only. [Download Preview 10](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.10). [Verified server runs](docs/NAVIGATION-REBUILD.md#exact-preview-10-263) record both results.

## Tell Preview 10 what you need

```text
!lk help
!lk get wood 64
!lk get diamond_boots
!lk project gear_diamond
!lk plan diamond_boots
!lk pause
!lk resume
!lk stop
```

Preview 10 makes prerequisite tools, places and uses crafting tables, and cooks through matching furnace, smoker, or blast furnace recipes. It also handles ordinary stonecutting. [Station limits](docs/PROCESSING-STATIONS.md) describe unsupported recipes and interactions.

Preview 10 uses its bundled Baritone runtime for movement, terrain breaking, scaffold placement, and item collection. Mining runs in batches. Compatible ore variants share a request, and bulk diamond jobs work in the deep band. Lodekeeper can carry its own crafting table between work sites and skip a blocked optional recovery. It checks the items that reach your inventory before advancing to crafting. Enable parkour with `!lk config allowParkour true`.

The compact status panel shows the current task, elapsed time, and route progress. The timer counts from the start of each goal or full project, including pauses. Use `!lk config showPath false` or `!lk config showHud false` to hide the path or panel. Preview 10 displays the executing route and whether it is calculating the next segment. It does not expose expanded search nodes.

Lodekeeper writes a short progress trace to the launcher console and your instance `logs/latest.log`. If a task stalls, copy the `[Lodekeeper]` lines from `BEGIN` through the latest `PROGRESS` or `task_end`. They include the task, elapsed time, navigation phase, route events, and retries. Logging is enabled by default. Use `!lk config debugLogging false` to turn it off.

For large wood requests, try `!lk config optimizeWoodTools true`. This experimental option makes axes when estimated savings cover the full setup cost. New installations default it to on. Saved configurations retain their previous value. See [how tool investment works and its measured limits](docs/HARVEST-INVESTMENT.md).

`wood` means logs. Exact items use their registry names, including `minecraft:diamond_boots` and modded names such as `example:ruby`. Change the prefix with `!lk config prefix "your-prefix "`. Commands stay on your client.

## From an empty inventory to a finished goal

Lodekeeper works backward from what you ask for. It identifies ingredients, gathers supplies, makes tools, uses crafting and cooking stations, then checks the finished item in your inventory. Preview 10 uses Baritone to calculate later path segments during movement. Lodekeeper waits for safe movement cancellation before taking control of inventory screens.

`!lk project gear_diamond` requests five diamond tools and four armor pieces. Armor equips automatically. Preview 10 passed one run in a new survival world on each primary version. The current source-owned Preview 11 candidate has not completed a full gear loadout in a natural survival world. Terrain and resource availability affect completion time.

Counts are inventory targets. If you already have 20 logs, `!lk get wood 64` asks for 44 more. New requests join a queue. Use `!lk plan` to show the current plan or `!lk plan diamond_boots` to preview a goal before starting it.

`get` starts automatically. Close chat or other screens to let it run. Use `!lk status` to see discovery, planning, and movement progress. Bare `!lk` and `!lk help` show command guidance.

| Command | Use it to |
| --- | --- |
| `!lk status` / `!lk queue` | Check progress and queued goals |
| `!lk pause` / `!lk resume` | Interrupt and continue automation |
| `!lk clear` | Clear queued foreground goals |
| `!lk stop` | Stop and clear goals, including maintained stock |
| `!lk maintain oak_planks 64` | Experimentally replenish a stock target |
| `!lk maintained` / `!lk unmaintain all` | Inspect or remove stock targets |
| `!lk projects` / `!lk project <name>` | List or queue experimental inventory loadouts |

Stock maintenance yields to foreground goals. Projects collect inventory targets. Supply projects do not build structures or farms. Some presets include mechanics still in development and can report a blocker. Their gameplay acceptance is pending.

Unknown recipes and unsupported mechanics report a blocker. Modded items using ordinary recipes and interactions are a design target. Special machines need providers.

## Preview 11 source candidate

Preview 11 is not released. Its source builds the licensed navigation kernel into Lodekeeper under `dev.lodekeeper.navigation.kernel`. It does not load a separate Baritone runtime.

The source map covers 24 exact Minecraft profiles across 14 navigation source families. The shared core and navigation modules target Java 17. Each Fabric adapter targets the APIs for its exact profile. The [matrix at `ce3c967`](https://github.com/luinbytes/lodekeeper/actions/runs/37609503491) passed all 24 profiles. The downloaded 1.21.1 and 26.3 production jars match the local gameplay jars byte for byte. The [checkpoint](docs/OWNED-PREVIEW-CHECKPOINT.md) records their hashes, runtime gates, and earlier failures. A successful build does not establish gameplay support.

The 23 advanced navigation options are available in the settings GUI. Open it with Right Shift or `!lk config`. The settings screen suspends automation while it is open. Save applies the draft; Escape discards it. The `Protected plots` button opens the claim editor.

Claims are 3D boxes scoped to the current world and dimension. The GUI accepts a name and two XYZ corners. These client commands select corners and create or list a claim.

Each `pos` command records the block under your crosshair. If you are not targeting a block, it records your block position.

```text
!lk claim pos1
!lk claim pos2
!lk claim add home preferred
!lk claim list
```

Use `!lk claim prefer home false` to turn off station preference. Use `!lk claim remove home` to remove a claim. `!lk claim clear` clears the pending corner selection. It does not remove saved claims.

Protected claims block automated breaking and placing. Placement checks include the target and adjacent support blocks, including the six possible faces for torch placement. If world identity or claim data cannot be checked, automated block edits pause. The candidate prefers usable stations that are already loaded in claims marked for stations. Station pickup checks the exact drop UUID and server receipts. Cleanup has a limit of 60 seconds. It reports skipped stations or incomplete pickups.

Optional backfill uses only surplus stone or cobblestone after other goals and reserves. It does not gather blocks just to restore the route.

Earlier progression and GUI checks passed nine cases on both primary adapters. The GUI at `ce3c967` adds shield controls and passes all ten checks on both primary versions. Claim face, torch support, station preference, backfill, and station recovery checks also passed before the current inventory and shield changes. All eighteen shield modes pass at `ce3c967` across 1.21.1 and 26.3, including queued-goal reservations, native blocking, occupied offhand, and simulated manual takeover. Those primary CI jars match their native gameplay inputs. The earlier inventory profiling warmup passes all nine cases and remains separate from counted timing trials. The historical inventory A/B sequence has five progression passes each, one extra baseline timeout, and one candidate station-cleanup failure. Current-build counted trials remain pending; no released-build speed claim is made. A fresh `98a7856` 1.21.1 survival attempt pauses during mixed-mob retreat after collecting two diamonds. It retains full health and zero deaths, but no full gear loadout. Current fresh survival remains a release gate.

When `autoDefend` is enabled, `autoUseShield` defaults to `true`. `autoCraftShield` defaults to `false` and requires `autoUseShield`. Shield crafting keeps the configured iron and plank reserves after planned recipes, goals, and other reservations. `shieldIronReserve` defaults to 2 and `shieldPlankReserve` defaults to 16. The settings GUI labels these values `Iron ingots to keep` and `Planks to keep`.

Shield defense preserves non-shield offhand items and uses a plain shield only when it has more than 100 durability remaining. A creeper always triggers immediate escape. That response does not wait for inventory or equipment changes and does not try to finish the creeper with melee. The fixture sources compile on all 24 profiles at `ce3c967`, where shield and GUI checks pass on both primary versions. The retreat correction at `98a7856` compiles locally on both primary versions and passes live-creeper escape with full health and no explosion. Its 26.3 controlled progression passes all nine goals with an original gameplay recording. Current shield reruns, other safety checks and fresh survival remain pending. See the [Preview 11 checkpoint](docs/OWNED-PREVIEW-CHECKPOINT.md) for current gates and the [verification guide](docs/GAME-VERIFICATION.md) for the pending fixture modes.

## Installation and compatibility

Preview 10 remains the downloadable version. Its jars include a bundled Baritone mod. Replace your previous Lodekeeper jar before launching.

1. [Install Fabric](https://docs.fabricmc.net/players/installing-fabric/) for your exact Minecraft Java version.
2. Place `lodekeeper-<minecraft-version>-0.1.0-preview.10.jar` and the matching Fabric API jar in your instance `mods` folder. Keep one Lodekeeper jar. [Fabric's mod-installation guide](https://docs.fabricmc.net/players/installing-mods) explains the folder locations.
3. Launch that Fabric profile, enter a world, and try `!lk get wood 8`.

Each Preview 10 jar targets one exact release. The release includes all 24 declared profiles from 1.20 through 26.3. Every jar passed compilation and packaging checks. Gameplay checks cover selected cases on 1.21.1 and 26.3. [Compatibility evidence](docs/COMPATIBILITY.md) separates those results from untested gameplay. Use server automation where the server permits it.

---

<details>
<summary><strong>Development, architecture, and verification</strong></summary>

Source lives in `core` for acquisition and commands, `nav` for route views and earlier navigation models, and the Fabric adapters for Minecraft integration. Preview 11 builds the owned navigation kernel described above into Lodekeeper. Builds run with one worker and no persistent daemon. Set `JAVA_HOME` to the JDK required by the exact game profile. Development artifacts still need their exact build and native checks.

Preview 10 binaries come from source `852b72f177befc1b55771cee86076544f39c9f9b` and [its successful CI run across 24 profiles](https://github.com/luinbytes/lodekeeper/actions/runs/37478984017). The exact [1.21.1](docs/evidence/navigation-rebuild/checkpoint-14.json) and [26.3 fresh world receipts](docs/evidence/navigation-rebuild/checkpoint-15.json) confirm the complete loadout. Prepared workbench checks on both primary versions use the earlier `7ba8d375` source. Their supplies and geometry are declared in the evidence. Earlier failures remain in the [rebuild record](docs/NAVIGATION-REBUILD.md).

The release includes SHA-256 checksums, packaging evidence, and corresponding upstream source archives. Lodekeeper code uses the MIT licence. The included Baritone source retains LGPL-3.0-or-later notices. [Dependency notices and source access](third-party/baritone/NOTICE.md) list the pinned source locks and rebuild details.

- [Navigation and survival rebuild](docs/NAVIGATION-REBUILD.md)
- [Dependency notices and source access](third-party/baritone/NOTICE.md)
- [Stack and delivery plan](docs/STACK.md)
- [Owned automation plan](docs/OWNED-AUTOMATION-PLAN.md)
- [Preview 11 development checkpoint](docs/OWNED-PREVIEW-CHECKPOINT.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Version and gameplay evidence](docs/COMPATIBILITY.md)
- [Isolated gameplay verifier](docs/GAME-VERIFICATION.md)
- [Custom block drop contracts](docs/CUSTOM-CONTENT.md)
- [Full mechanic coverage ledger](docs/COVERAGE.md)
- [Processing stations and their limits](docs/PROCESSING-STATIONS.md)
- [Local resource planning and discovery](docs/LOCAL-PLANNING.md)
- [Collision-shape navigation and limits](docs/NAVIGATION-SHAPES.md)
- [Navigation performance investigations](docs/NAVIGATION-PERFORMANCE.md)
- [Movement research and next experiments](docs/MOVEMENT-DESIGN.md)
- [Bootstrap planning and safe approaches](docs/BOOTSTRAP-PLANNER.md)
- [Repository instructions](AGENTS.md)

Performance comparisons with AltoClef or Baritone require equivalent gameplay benchmarks. This project makes no superiority claim before those measurements exist.

</details>
