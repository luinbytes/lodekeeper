# Minecraft 26.3 Fabric build

This artifact targets **Minecraft 26.3 exactly**, uses Java 25, and includes Lodekeeper's shared acquisition planner and independent navigation engine. Install the matching build only with Fabric Loader and Fabric API for 26.3.

With Java 25 installed and selected through `JAVA_HOME`, build this adapter with:

```sh
./scripts/build-version.sh 26.3 --no-daemon
```

Use client chat commands with the configured prefix (default `!lk `), for example `!lk get minecraft:oak_log 64`. Prefix commands are intercepted locally and never sent to the server. Other useful commands include `!lk plan diamond_boots`, `!lk projects`, `!lk project gear_iron`, `!lk maintain minecraft:torch 32`, `!lk maintained`, `!lk unmaintain minecraft:torch`, `!lk queue`, `!lk pause`, and `!lk stop`. Maintained targets refill after stock falls below half of the target; `stop` clears them, while `clear` only clears foreground goals. A foreground goal temporarily takes priority over maintenance.

The adapter only acts through the connected player's normal survival interaction and container protocols. It reads loaded chunks only; unexplored or unloaded terrain is treated as blocked. Route searches, world scans, and planning work use bounded budgets. No Baritone or AltoClef code is used.

Modded item names are resolved from registered items, including namespaced IDs and available aliases. Recipe automation uses the integrated world's live recipes in single-player, or only the recipe displays the remote server has revealed to the client. Custom loot tables cannot be inferred from block and item registries. To teach a verified one-item block drop, create `config/lodekeeper-sources.json`:

```json
{
  "schema": 1,
  "gather": [
    {
      "id": "examplemod:glowshroom",
      "item": "examplemod:glowshroom",
      "blocks": ["examplemod:glowshroom_block"],
      "tools": ["minecraft:iron_axe"]
    }
  ]
}
```

Each entry maps registered block IDs to a registered output item and assumes one output per broken block. Add the actual eligible tool IDs when drops require a tool; verify the mapping against that modpack's loot rules. The adapter does not guess arbitrary modded drops, recipes, station behavior, or output yields.

Recipe visibility follows the information available to the client. A client cannot infer hidden server recipes, server-only loot rules, or undisclosed mod behavior. The adapter must report unavailable or incomplete source knowledge instead of claiming universal mod recipe coverage. In local single-player, recipe inspection is permitted only through the integrated server's live registry state on its server thread; this is separate from remote-server client discovery.

Build success establishes mapped API compatibility only. Game behavior still requires isolated disposable-world verification; it does not prove every modpack mechanic or every server's permission policy.

## Adapter notes

- Keep version-specific game API code under `fabric-modern`; shared logic belongs in `core` or `nav`.
- Keep client command capture local and fail closed when actions cannot be verified.
- Keep dynamic recipe/output provenance explicit and preserve server inventory ownership.
- Do not report a Minecraft release as supported until this adapter compiles against that exact artifact.
