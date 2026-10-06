<p align="center"><img src="docs/assets/lodekeeper-banner.svg" alt="Lodekeeper — Give it a goal. Let it find the way." width="100%"></p>

<p align="center">
  <a href="LICENSE"><img alt="Licence: MIT" src="https://img.shields.io/badge/licence-MIT-abf49b?style=flat-square&amp;labelColor=192922"></a>
  <img alt="Fabric client mod" src="https://img.shields.io/badge/Fabric-client_mod-abf49b?style=flat-square&amp;labelColor=192922">
  <img alt="Status: in development" src="https://img.shields.io/badge/status-in_development-eac97a?style=flat-square&amp;labelColor=192922">
</p>

**Give it a goal. Let it do the work.** Lodekeeper is a Minecraft Fabric client mod that gathers resources and works through survival crafting chains using ordinary player actions. The current development branch uses version-matched Baritone navigation with Lodekeeper's inventory planner, station handling, and live task panel.

> **Navigation rebuild in progress.** The development branch now uses Baritone for movement and batch mining. [Preview 7](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.7) predates this rebuild and still has reported movement and pickup failures. A new preview needs gameplay checks before release. Full survival and mechanic coverage remain in development.

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

Matching recipes can use a smoker or blast furnace. Controlled 72-item batches passed on [1.21.1](docs/evidence/1.21.1-smoker/run.json) and [26.3](docs/evidence/26.3-blast-furnace/run.json); see the [current station limits](docs/PROCESSING-STATIONS.md).

Lodekeeper uses native stonecutting with server-confirmed inventory accounting and safer cleanup. Nearby ordinary stone, wood, held supplies and available stations guide tool progression. Preview 3's exact [1.21.1 iron-pickaxe check](docs/evidence/1.21.1-preview3-iron-pickaxe/run.json) starts with only a crafting table and completes with full health, without mining deepslate.

The development build delegates walking, terrain breaking, scaffold placement, and item collection to Baritone. Mining runs as a batch process. Lodekeeper checks the items that actually reach your inventory before advancing to crafting. Parkour remains configurable with `!lk config allowParkour true`.

Watch the planned path and target while it works. The compact panel shows the current task, elapsed time and route progress. The timer counts from the start of each goal or full project, including pauses. Use `!lk config showPath false` or `!lk config showHud false` to hide them. The Baritone backend displays the executing route and whether it is calculating the next segment. Expanded search nodes are not exposed by that backend.

Lodekeeper also writes a short progress trace to the launcher console and your instance's `logs/latest.log`. If a task stalls, copy the `[Lodekeeper]` lines from `BEGIN` through the latest `PROGRESS` or `task_end`. They include the task, elapsed time, navigation phase, route events, and retries. Logging is enabled by default; use `!lk config debugLogging false` to turn it off.

For large wood requests, try `!lk config optimizeWoodTools true`. This experimental option can make axes when estimated savings cover the entire setup cost; it is disabled by default. See [how tool investment works and its measured limits](docs/HARVEST-INVESTMENT.md).

`wood` means logs. Exact items use their registry names, including `minecraft:diamond_boots` and modded names such as `example:ruby`. Change the prefix with `!lk config prefix "your-prefix "`. Commands stay on your client.

### From an empty inventory to a finished goal

Lodekeeper works backward from what you ask for: identify ingredients, gather supplies, make tools, place and use crafting tables and cooking stations, then check the finished item in your inventory. Baritone calculates later path segments during movement. Lodekeeper waits for safe movement cancellation before taking control of inventory screens.

`!lk project gear_diamond` queues a full diamond loadout, including five tools and four armor pieces. This preset is experimental. A fresh-world completion time has not yet been established.

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

Download [Development Preview 7](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.7). All 24 exact-version jars were built and inspected. Its exact [1.21.1](docs/evidence/1.21.1-preview7-jar/README.md) and [26.3 jars](docs/evidence/26.3-preview7-jar/README.md) each passed native collision checks and a meadow wood-to-crafting-table command from an empty inventory. Earlier development snapshots also passed controlled [tool progression](docs/evidence/1.21.1-speed-iron-empty-development/README.md) and [drop execution](docs/evidence/1.21.1-drop-departure-development/README.md) checks using development classes. Broader ordinary-world acceptance remains open. Preview 7 still has reported failures for inaccessible dropped items and repeated obstruction retries; pickup recovery, batch mining and the planner/executor break-visibility mismatch are the next fixes.

1. [Install Fabric](https://docs.fabricmc.net/players/installing-fabric/) for your exact Minecraft Java version.
2. Download the matching `lodekeeper-<minecraft-version>-0.1.0-preview.7.jar` from the release assets, replace any earlier Lodekeeper jar so there is only one copy, and place it plus the matching Fabric API jar in your instance's `mods` folder. [Fabric's mod-installation guide](https://docs.fabricmc.net/players/installing-mods) explains the folder locations.
3. Launch that Fabric profile, enter a world and try `!lk get wood 8`.

Each jar targets one exact release. Preview 7 has compiled artifacts for every stable Java release from 1.20 through 26.3, including 1.21.1. The navigation rebuild has compiled locally for 1.21.1 and 26.3; its full version matrix is being checked. Gameplay evidence and remaining limitations are listed in [compatibility](docs/COMPATIBILITY.md); broader supported-version acceptance remains pending. Use server automation only where the server permits it.

---

<details>
<summary><strong>Development, architecture and verification</strong></summary>

Source lives in `core` for acquisition and commands, `nav` for route views and the earlier navigation tests, and the Fabric adapters for Minecraft integration. The current default movement and mining backend is Baritone. The shared core targets Java 17. Builds run with one worker and no persistent daemon. Exact build profiles are selected with `./scripts/build-version.sh 1.20.1`, `1.21.1`, or `26.3`; set `JAVA_HOME` to a JDK 17, 21, or 25 respectively. These development artifacts still require gameplay verification.

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
