<p align="center"><img src="docs/assets/lodekeeper-banner.svg" alt="Lodekeeper — Give it a goal. Let it find the way." width="100%"></p>

<p align="center">
  <a href="LICENSE"><img alt="Licence: MIT" src="https://img.shields.io/badge/licence-MIT-abf49b?style=flat-square&amp;labelColor=192922"></a>
  <img alt="Fabric client mod" src="https://img.shields.io/badge/Fabric-client_mod-abf49b?style=flat-square&amp;labelColor=192922">
  <img alt="Status: in development" src="https://img.shields.io/badge/status-in_development-eac97a?style=flat-square&amp;labelColor=192922">
</p>

**Give it a goal. Let it do the work.** Lodekeeper is a Minecraft Fabric client mod that gathers resources and works through survival crafting chains using ordinary player actions. The current development branch uses version-matched Baritone navigation with Lodekeeper's inventory planner, station handling, and live task panel.

> **Preview 8 introduces Baritone navigation and batch mining.** Download the [jar for your exact Minecraft version](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.8). Full survival automation remains experimental. The latest fresh-world test equipped all four diamond armor pieces and made three diamond tools, but missed the 15-minute full-set target.

### Tell it what you need

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

Lodekeeper makes prerequisite tools, places and uses crafting tables, and cooks through matching furnace, smoker or blast-furnace recipes. It also handles ordinary stonecutting. [Station limits](docs/PROCESSING-STATIONS.md) describe unsupported recipes and interactions.

Baritone handles movement, terrain breaking, scaffold placement and item collection. Mining runs in batches. Lodekeeper checks the items that actually reach your inventory before advancing to crafting. Enable parkour with `!lk config allowParkour true`.

Watch the planned path and target while it works. The compact panel shows the current task, elapsed time and route progress. The timer counts from the start of each goal or full project, including pauses. Use `!lk config showPath false` or `!lk config showHud false` to hide them. The Baritone backend displays the executing route and whether it is calculating the next segment. Expanded search nodes are not exposed by that backend.

Lodekeeper also writes a short progress trace to the launcher console and your instance's `logs/latest.log`. If a task stalls, copy the `[Lodekeeper]` lines from `BEGIN` through the latest `PROGRESS` or `task_end`. They include the task, elapsed time, navigation phase, route events, and retries. Logging is enabled by default; use `!lk config debugLogging false` to turn it off.

For large wood requests, try `!lk config optimizeWoodTools true`. This experimental option can make axes when estimated savings cover the entire setup cost; it is disabled by default. See [how tool investment works and its measured limits](docs/HARVEST-INVESTMENT.md).

`wood` means logs. Exact items use their registry names, including `minecraft:diamond_boots` and modded names such as `example:ruby`. Change the prefix with `!lk config prefix "your-prefix "`. Commands stay on your client.

### From an empty inventory to a finished goal

Lodekeeper works backward from what you ask for: identify ingredients, gather supplies, make tools, place and use crafting tables and cooking stations, then check the finished item in your inventory. Baritone calculates later path segments during movement. Lodekeeper waits for safe movement cancellation before taking control of inventory screens.

`!lk project gear_diamond` requests five diamond tools and four armor pieces. Armor equips automatically. This preset is experimental, and the full-set fresh-world benchmark has not passed.

Counts are inventory targets: if you already have 20 logs, `!lk get wood 64` asks for 44 more. New requests join a queue; `!lk plan` shows the current plan and `!lk plan diamond_boots` previews a goal before starting it.

`get` starts automatically. Close chat or other screens to let it run; `!lk status` shows discovery, planning, and movement progress. Bare `!lk` and `!lk help` show command guidance.

| Command | Use it to |
| --- | --- |
| `!lk status` / `!lk queue` | Check progress and queued goals |
| `!lk pause` / `!lk resume` | Interrupt and continue automation |
| `!lk clear` | Clear queued foreground goals |
| `!lk stop` | Stop and clear goals, including maintained stock |
| `!lk maintain oak_planks 64` | Experimentally replenish a stock target |
| `!lk maintained` / `!lk unmaintain all` | Inspect or remove stock targets |
| `!lk projects` / `!lk project <name>` | List or queue experimental inventory loadouts |

Stock maintenance yields to foreground goals. Projects collect inventory targets; supply projects do not build structures or farms. Some presets include mechanics still in development and can report a blocker. Their gameplay acceptance is pending.

Unknown recipes or unsupported mechanics report a blocker. Modded items using ordinary recipes and interactions are a design target; special machines need providers.

### Installation and compatibility

Download [Development Preview 8](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.8). Baritone is bundled, so you do not need a separate installation. Replace your previous Lodekeeper jar before launching.

1. [Install Fabric](https://docs.fabricmc.net/players/installing-fabric/) for your exact Minecraft Java version.
2. Place `lodekeeper-<minecraft-version>-0.1.0-preview.8.jar` and the matching Fabric API jar in your instance's `mods` folder. Keep one Lodekeeper jar. [Fabric's mod-installation guide](https://docs.fabricmc.net/players/installing-mods) explains the folder locations.
3. Launch that Fabric profile, enter a world and try `!lk get wood 8`.

Each jar targets one exact release. Preview 8 includes all 24 stable Java releases from 1.20 through 26.3, including 1.21.1. Every jar passed compilation and packaging checks. Gameplay checks cover selected cases on 1.21.1 and 26.3; [compatibility evidence](docs/COMPATIBILITY.md) separates those results from untested gameplay. Use server automation where the server permits it.

---

<details>
<summary><strong>Development, architecture and verification</strong></summary>

Source lives in `core` for acquisition and commands, `nav` for route views and the earlier navigation tests, and the Fabric adapters for Minecraft integration. The current default movement and mining backend is Baritone. The shared core targets Java 17. Builds run with one worker and no persistent daemon. Exact build profiles are selected with `./scripts/build-version.sh 1.20.1`, `1.21.1`, or `26.3`; set `JAVA_HOME` to a JDK 17, 21, or 25 respectively. These development artifacts still require gameplay verification.

Preview 8 binaries come from source `6ca3e283cffc553a5885eaa5b9ff5bff1329541a` and [its successful 24-profile CI run](https://github.com/luinbytes/lodekeeper/actions/runs/37435670896). Prepared native cases verify water retreat, inventory reservations, automatic armor, held coal, and productive mining continuation. The [fresh-world failure receipt](docs/evidence/navigation-rebuild/checkpoint-07.json) records the incomplete full diamond project, including the later wood-supply problem. Prepared cases grant their documented supplies and do not establish fresh-world acceptance.

The release includes SHA-256 checksums, packaging evidence and corresponding upstream source archives. Lodekeeper's code uses the MIT licence. Bundled Baritone retains its LGPL licence and upstream notices. [Dependency notices](third-party/baritone/NOTICE.md) include pinned versions and replacement instructions.

- [Navigation and survival rebuild](docs/NAVIGATION-REBUILD.md)
- [Baritone dependency notices and replacement instructions](third-party/baritone/NOTICE.md)
- [Stack and delivery plan](docs/STACK.md)
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

Performance comparisons with AltoClef or Baritone require equivalent gameplay benchmarks; this project makes no superiority claim before those measurements exist.

</details>
