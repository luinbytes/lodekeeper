<p align="center"><img src="docs/assets/lodekeeper-banner.svg" alt="Lodekeeper — Give it a goal. Let it find the way." width="100%"></p>

<p align="center">
  <a href="LICENSE"><img alt="Licence: MIT" src="https://img.shields.io/badge/licence-MIT-abf49b?style=flat-square&amp;labelColor=192922"></a>
  <img alt="Fabric client mod" src="https://img.shields.io/badge/Fabric-client_mod-abf49b?style=flat-square&amp;labelColor=192922">
  <img alt="Status: in development" src="https://img.shields.io/badge/status-in_development-eac97a?style=flat-square&amp;labelColor=192922">
</p>

**Your next item is a goal, not a chore.** Lodekeeper is a Minecraft Fabric mod being built to gather resources, navigate terrain and work through survival crafting chains using ordinary player actions—with its own navigation engine and no Baritone dependency.

> **Development preview:** experimental builds are available below. Full mechanic coverage and natural-world progression are still in development. Nine controlled checks each on 1.20.1, 1.21.1, 1.21.2, 1.21.11 and 26.3 passed gathering, inventory/table crafting, tool and furnace progression, iron smelting, custom-content crafting, and eating while gathering. Single-command diamond-boots bootstraps on 1.21.1 and 26.3 also passed from empty inventory. Separate distant-wood checks on 1.21.1 and 26.3 passed travel into initially unloaded resource terrain; natural-world progression remains in development. Version compatibility and features will be marked verified only when their builds and gameplay checks pass.

### Tell it what you need

```text
!lk get wood 64
!lk get diamond_boots
!lk plan diamond_boots
!lk pause
!lk resume
!lk stop
```

Matching recipes can use a smoker or blast furnace. Controlled 72-item batches passed on [1.21.1](docs/evidence/1.21.1-smoker/run.json) and [26.3](docs/evidence/26.3-blast-furnace/run.json); see the [current cooking limits](docs/PROCESSING-STATIONS.md).

For large wood requests, try `!lk config optimizeWoodTools true`. This experimental option can make axes when estimated savings cover the entire setup cost; it is disabled by default. See [how tool investment works and its measured limits](docs/HARVEST-INVESTMENT.md).

`wood` means logs. Exact items use their registry names, including `minecraft:diamond_boots` and modded names such as `example:ruby`. Change the prefix with `!lk config prefix "your-prefix "`. Commands stay on your client.

### From an empty inventory to a finished goal

The planned flow works backward from what you ask for: identify ingredients, gather supplies, make tools, place and use crafting tables and cooking stations, then check the finished item in your inventory. Navigation and actions are designed to share a bounded tick budget so the game stays responsive.

Counts are inventory targets: if you already have 20 logs, `!lk get wood 64` asks for 44 more. New requests join a queue; `!lk plan` shows the current plan and `!lk plan diamond_boots` previews a goal before starting it.

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

Experimental jars are available in [Development Preview 1](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.1), with all 24 exact-version builds inspected. Single-command diamond-boots checks currently cover **1.21.1** and **26.3**. The published [1.21.1](docs/evidence/1.21.1-preview-jar/README.md) and [26.3](docs/evidence/26.3-preview-jar/README.md) jars each passed nine controlled gameplay checks in isolated development harnesses.

1. [Install Fabric](https://docs.fabricmc.net/players/installing-fabric/) for your exact Minecraft Java version.
2. Download the matching `lodekeeper-<minecraft-version>-0.1.0-dev.jar` from the release assets and place it plus the matching Fabric API jar in your instance's `mods` folder. [Fabric's mod-installation guide](https://docs.fabricmc.net/players/installing-mods) explains the folder locations.
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
- [Cooking station expansion design](docs/PROCESSING-STATIONS.md)
- [Bootstrap planning and safe approaches](docs/BOOTSTRAP-PLANNER.md)
- [Repository instructions](AGENTS.md)

Performance comparisons with AltoClef or Baritone require equivalent gameplay benchmarks; this project makes no superiority claim before those measurements exist.

</details>
