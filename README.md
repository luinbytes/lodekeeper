<p align="center"><img src="docs/assets/lodekeeper-banner.svg" alt="Lodekeeper — Give it a goal. Let it find the way." width="100%"></p>

<p align="center">
  <a href="LICENSE"><img alt="Licence: MIT" src="https://img.shields.io/badge/licence-MIT-abf49b?style=flat-square&amp;labelColor=192922"></a>
  <img alt="Fabric client mod" src="https://img.shields.io/badge/Fabric-client_mod-abf49b?style=flat-square&amp;labelColor=192922">
  <img alt="Status: in development" src="https://img.shields.io/badge/status-in_development-eac97a?style=flat-square&amp;labelColor=192922">
</p>

**Your next item is a goal, not a chore.** Lodekeeper is a Minecraft Fabric mod being built to gather resources, navigate terrain and work through survival crafting chains using ordinary player actions—with its own navigation engine and no Baritone dependency.

> **Experimental preview:** [Preview 7](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.7) adds route pruning and live collision checks. Follow its path, target and elapsed timer, or inspect the console progress trace. Full mechanic coverage, natural-world survival and server acceptance remain in development.

### Tell it what you need

```text
!lk help
!lk get wood 64
!lk get diamond_boots
!lk plan diamond_boots
!lk pause
!lk resume
!lk stop
```

Matching recipes can use a smoker or blast furnace. Controlled 72-item batches passed on [1.21.1](docs/evidence/1.21.1-smoker/run.json) and [26.3](docs/evidence/26.3-blast-furnace/run.json); see the [current station limits](docs/PROCESSING-STATIONS.md).

Lodekeeper uses native stonecutting with server-confirmed inventory accounting and safer cleanup. Nearby ordinary stone, wood, held supplies and available stations guide tool progression. Preview 3's exact [1.21.1 iron-pickaxe check](docs/evidence/1.21.1-preview3-iron-pickaxe/run.json) starts with only a crafting table and completes with full health, without mining deepslate.

It walks across actual collision faces on paths, farmland, slabs, stairs and snow. The exact [1.21.1](docs/evidence/1.21.1-preview3-mixed/README.md) and [26.3 terrain checks](docs/evidence/26.3-preview3-mixed/README.md) include a stair climb and one-block jump at full health, with every protected block preserved. The [1.21.1 dirt-path start](docs/evidence/1.21.1-preview3-coal-path/run.json) also collects reachable coal while preserving all nine starting path blocks.

If a resource is unreachable, bounded recovery tries another target. Status and blockers retain the failed position and reason. Fractional jumps, parkour, fluid movement and broader terrain behavior still need their own gameplay checks.

Watch the planned path and target while it works. The compact panel shows the current task, elapsed time and route progress. The timer counts from the start of each goal, including pauses. Use `!lk config showPath false` or `!lk config showHud false` to hide them, and `!lk config showSearch true` to display search nodes.

Lodekeeper also writes a short progress trace to the launcher console and your instance's `logs/latest.log`. If a task stalls, copy the `[Lodekeeper]` lines from `BEGIN` through the latest `PROGRESS` or `task_end`. They include the target, elapsed time, search work and retries. Logging is enabled by default; use `!lk config debugLogging false` to turn it off.

For large wood requests, try `!lk config optimizeWoodTools true`. This experimental option can make axes when estimated savings cover the entire setup cost; it is disabled by default. See [how tool investment works and its measured limits](docs/HARVEST-INVESTMENT.md).

`wood` means logs. Exact items use their registry names, including `minecraft:diamond_boots` and modded names such as `example:ruby`. Change the prefix with `!lk config prefix "your-prefix "`. Commands stay on your client.

### From an empty inventory to a finished goal

Lodekeeper works backward from what you ask for: identify ingredients, gather supplies, make tools, place and use crafting tables and cooking stations, then check the finished item in your inventory. Navigation and actions are designed to share a bounded tick budget so the game stays responsive.

In a controlled [nearby-log check](docs/evidence/1.21.1-preview6-local-wood-jar/README.md), the exact 1.21.1 jar selected and began mining the reachable log after 246 ms. The enclosed decoy stayed intact. This is a prepared fixture; performance in your world still needs verification.

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

Download [Development Preview 7](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.7). All 24 exact-version jars were built and inspected. Its exact [1.21.1](docs/evidence/1.21.1-preview7-jar/README.md) and [26.3 jars](docs/evidence/26.3-preview7-jar/README.md) each passed native collision checks and a meadow wood-to-crafting-table command from an empty inventory. The current fixes also pass controlled [tool progression](docs/evidence/1.21.1-speed-iron-empty-development/README.md) and [drop execution](docs/evidence/1.21.1-drop-departure-development/README.md) checks using development classes. Broader ordinary-world acceptance remains open. Preview 7 still has reported failures for inaccessible dropped items and repeated obstruction retries; pickup recovery, batch mining and the planner/executor break-visibility mismatch are the next fixes.

1. [Install Fabric](https://docs.fabricmc.net/players/installing-fabric/) for your exact Minecraft Java version.
2. Download the matching `lodekeeper-<minecraft-version>-0.1.0-preview.7.jar` from the release assets, replace any earlier Lodekeeper jar so there is only one copy, and place it plus the matching Fabric API jar in your instance's `mods` folder. [Fabric's mod-installation guide](https://docs.fabricmc.net/players/installing-mods) explains the folder locations.
3. Launch that Fabric profile, enter a world and try `!lk get wood 8`.

Each jar targets one exact release. Every stable Java release from 1.20 through the current stable release, 26.3, has a passing development build, including 1.21.1. Gameplay evidence and remaining limitations are listed in [compatibility](docs/COMPATIBILITY.md); broader supported-version acceptance remains pending. Use server automation only where the server permits it.

---

<details>
<summary><strong>Development, architecture and verification</strong></summary>

Source lives in `core` (acquisition and commands), `nav` (custom navigation), and `fabric` (Minecraft integration). The shared core targets Java 17. Builds run with one worker and no persistent daemon. Exact build profiles are selected with `./scripts/build-version.sh 1.20.1`, `1.21.1`, or `26.3`; set `JAVA_HOME` to a JDK 17, 21, or 25 respectively. These development artifacts still require gameplay verification.

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
