<p align="center"><img src="docs/assets/lodekeeper-banner.svg" alt="Lodekeeper — Give it a goal. Let it find the way." width="100%"></p>

<p align="center">
  <a href="LICENSE"><img alt="Licence: MIT" src="https://img.shields.io/badge/licence-MIT-abf49b?style=flat-square&amp;labelColor=192922"></a>
  <img alt="Fabric client mod" src="https://img.shields.io/badge/Fabric-client_mod-abf49b?style=flat-square&amp;labelColor=192922">
  <img alt="Status: in development" src="https://img.shields.io/badge/status-in_development-eac97a?style=flat-square&amp;labelColor=192922">
</p>

**Your next item is a goal, not a chore.** Lodekeeper is a Minecraft Fabric mod being built to gather resources, navigate terrain and work through survival crafting chains using ordinary player actions—with its own navigation engine and no Baritone dependency.

> **Development preview:** there is no playable release yet. Basic log gathering and inventory crafting have passed a controlled 1.20.1 game check; broader progression remains in development. Version compatibility and features will be marked verified only when their builds and gameplay checks pass.

### Tell it what you need

```text
!lk get wood 64
!lk get diamond_boots
!lk plan diamond_boots
!lk pause
!lk resume
!lk stop
```

`wood` means logs. Exact items use their registry names, including `minecraft:diamond_boots` and modded names such as `example:ruby`. Change the prefix with `!lk config prefix "your-prefix "`. Commands stay on your client.

### From an empty inventory to a finished goal

The planned flow works backward from what you ask for: identify ingredients, gather supplies, make tools, place and use crafting tables or furnaces, then check the finished item in your inventory. Navigation and actions are designed to share a bounded tick budget so the game stays responsive.

You will be able to inspect a plan, queue goals, pause or stop. Unknown recipes or unsupported mod mechanics will produce a clear blocker rather than pretend the task succeeded. Modded items using ordinary recipes and interactions are a design target; special machines need providers.

### Installation and compatibility

Installable artifacts and exact instructions will appear here after verification. **No Minecraft version is currently claimed as supported.** The target is every stable Java release from 1.20 through the current stable release, 26.3, including an explicit 1.21.1 build; each needs its own compatibility evidence. Use server automation only where the server permits it.

---

<details>
<summary><strong>Development, architecture and verification</strong></summary>

Source lives in `core` (acquisition and commands), `nav` (custom navigation), and `fabric` (Minecraft integration). The shared core targets Java 17. Builds run with one worker and no persistent daemon.

- [Stack and delivery plan](docs/STACK.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Version and gameplay evidence](docs/COMPATIBILITY.md)
- [Isolated gameplay verifier](docs/GAME-VERIFICATION.md)
- [Custom block drop contracts](docs/CUSTOM-CONTENT.md)
- [Repository instructions](AGENTS.md)

Performance comparisons with AltoClef or Baritone require equivalent gameplay benchmarks; this project makes no superiority claim before those measurements exist.

</details>
