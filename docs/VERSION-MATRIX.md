# Exact release coordinates

Checked against the official Minecraft manifest, Fabric Meta and Fabric Maven on 2026-10-05. These are source-family candidates and published dependency coordinates, **not compatibility declarations**. Build and gameplay evidence remain separate in [COMPATIBILITY.md](COMPATIBILITY.md).

| Minecraft | JDK | Candidate adapter family | Latest Yarn mapping | Latest matching Fabric API |
| --- | --- | --- | --- | --- |
| 1.20 | 17 | legacy | 1.20+build.1 | 0.83.0+1.20 |
| 1.20.1 | 17 | legacy | 1.20.1+build.10 | 0.92.12+1.20.1 |
| 1.20.2 | 17 | 1202 | 1.20.2+build.4 | 0.91.6+1.20.2 |
| 1.20.3 | 17 | 1202 | 1.20.3+build.1 | 0.91.1+1.20.3 |
| 1.20.4 | 17 | 1202 | 1.20.4+build.3 | 0.97.3+1.20.4 |
| 1.20.5 | 21 | 1211 | 1.20.5+build.1 | 0.97.8+1.20.5 |
| 1.20.6 | 21 | 1211 | 1.20.6+build.3 | 0.100.8+1.20.6 |
| 1.21 | 21 | 1211 | 1.21+build.9 | 0.102.0+1.21 |
| 1.21.1 | 21 | 1211 | 1.21.1+build.3 | 0.116.17+1.21.1 |
| 1.21.2 | 21 | 1212 (source implemented; build pending) | 1.21.2+build.1 | 0.106.1+1.21.2 |
| 1.21.3 | 21 | 1212 (source implemented; build pending) | 1.21.3+build.2 | 0.114.1+1.21.3 |
| 1.21.4 | 21 | 1212 (source implemented; build pending) | 1.21.4+build.8 | 0.119.4+1.21.4 |
| 1.21.5 | 21 | 1212 (matrix at c7c885f2 failed; source fix pending CI) | 1.21.5+build.1 | 0.128.2+1.21.5 |
| 1.21.6 | 21 | 1212 (matrix at c7c885f2 failed; source fix pending CI) | 1.21.6+build.1 | 0.128.2+1.21.6 |
| 1.21.7 | 21 | 1212 (matrix at c7c885f2 failed; source fix pending CI) | 1.21.7+build.8 | 0.129.0+1.21.7 |
| 1.21.8 | 21 | 1212 (matrix at c7c885f2 failed; source fix pending CI) | 1.21.8+build.1 | 0.136.1+1.21.8 |
| 1.21.9 | 21 | 1212 (matrix at c7c885f2 failed; source fix pending CI) | 1.21.9+build.1 | 0.134.1+1.21.9 |
| 1.21.10 | 21 | 1212 (matrix at c7c885f2 failed; source fix pending CI) | 1.21.10+build.3 | 0.138.4+1.21.10 |
| 1.21.11 | 21 | 1212 (matrix at c7c885f2 failed; source fix pending CI) | 1.21.11+build.6 | 0.141.6+1.21.11 |
| 26.1 | 25 | modern (matrix at c7c885f2 failed; source fix pending CI) | none | 0.145.1+26.1 |
| 26.1.1 | 25 | modern (matrix at c7c885f2 failed; source fix pending CI) | none | 0.145.4+26.1.1 |
| 26.1.2 | 25 | modern (matrix at c7c885f2 failed; source fix pending CI) | none | 0.155.3+26.1.2 |
| 26.2 | 25 | modern (matrix at c7c885f2 failed; source fix pending CI) | none | 0.161.0+26.2 |
| 26.3 | 25 | modern (matrix at c7c885f2 failed; source fix pending CI) | none | 0.161.0+26.3 |

The build selector implements the legacy, 1202, 1211, 1212, and modern profile candidates. The 1212 source family covers 1.21.2–1.21.11 with input overlays for the 1.21.2–1.21.3, 1.21.4, and 1.21.5+ API shapes. The modern selector shares one API overlay across 26.1, 26.1.1, and 26.1.2, then uses separate 26.2 and 26.3 overlays for their known API changes. These are source and dependency candidates until each exact artifact compiles. The validated 1.21.1 profile deliberately pins Fabric API `0.110.0+1.21.1`; updating it to the newer coordinate above requires another check. Loader is pinned to `0.19.5`. Loom is pinned per build family (1.6.12, 1.8.13, or 1.17.21), rather than upgraded implicitly with each game target.

## Why the families differ

- 1.20.2 changes recipe enumeration to recipe entries and renames output/cooking-time access.
- 1.20.5 introduces item components and player block-interaction range.
- 1.21.2 removes full recipe enumeration from the client interface, introduces per-world fuel registries, returns remainder stacks, and moves input booleans into `PlayerInput`. Integrated-server authoritative recipes and remote learned recipe displays need distinct handling.
- 1.21.4 changes ingredient enumeration from a list to a stream, recipe placement slots to integer lists, and input ticks to no-argument calls while retaining movement floats. Display-based normalization avoids the placement seam. 1.21.5 changes input movement to a vector. The 1.21.8 matrix-type change does not affect this mod's current text-only HUD. These are candidate adapter overlays until compiled.
- 26.1 begins the unobfuscated Mojang-name source family and uses Java 25.
- 26.2 moves screen access to the GUI.
- 26.3 renames `InputConstants.Type.KEYSYM` to `KEYBOARD`, adds cooking-fuel components, and moves recipes into dynamic registries.

Sources: [Minecraft release manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json), [Fabric Yarn metadata](https://meta.fabricmc.net/v2/versions/yarn/1.20.1), [Fabric API Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml), [Fabric Loom](https://docs.fabricmc.net/develop/loom/), [1.21.2 recipe manager](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/recipe/RecipeManager.html), [Fabric 26.1](https://fabricmc.net/2026/03/14/261.html), [Fabric 26.2](https://fabricmc.net/2026/06/15/262.html), [Fabric 26.3](https://fabricmc.net/2026/09/15/263.html).

## Recipe knowledge from 1.21.2

Local worlds permit a server-thread scan of `ServerRecipeManager.values()` and `forEachRecipeDisplay`; immutable normalized additions are published on the client thread before the catalog becomes ready. Remote clients flatten their learned recipe-book displays. Their network recipe IDs are session-local and do not reveal resource recipe IDs or locked recipes. Only supported displays with consistent outputs and ingredient requirements become executable sources. Display-only entries remain explicit blockers.

Shared station executors should consume normalized output stacks, indexed ingredient predicates, layout and cooking duration rather than version-specific `Recipe` objects. Recipe authority, catalog readiness and reload generation need separate state. Recipe-specific remainders require an authoritative recipe or a declared contract; the item-level remainder alone cannot model every custom recipe.

Remote crafting supports only learned display forms whose ingredient predicates and output stacks match the synchronized metadata. A `WithRemainder` slot supplies a declared remainder; an unwrapped ingredient with a default item remainder is rejected. An ordinary display cannot reveal a server-only custom recipe remainder override, so the executor treats omitted metadata as a declared empty contract and checks the observed grid after output transfer. If the server produces unmodeled contents, automation stops with the crafting container open for recovery. This remote path is intentionally narrower than integrated-server crafting, which calls the native recipe remainder resolver on the server thread.

Verified primary API references: [1.21.2 server manager](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/recipe/ServerRecipeManager.html), [recipe display entries](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/recipe/RecipeDisplayEntry.html), [client recipe book](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/client/recipebook/ClientRecipeBook.html), [item default remainder](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/item/Item.html), [fuel registry](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/item/FuelRegistry.html), [1.21.2 ingredients](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/recipe/Ingredient.html), [1.21.4 ingredients](https://maven.fabricmc.net/docs/yarn-1.21.4+build.8/net/minecraft/recipe/Ingredient.html), [1.21.4 input](https://maven.fabricmc.net/docs/yarn-1.21.4+build.8/net/minecraft/client/input/Input.html), [1.21.5 input](https://maven.fabricmc.net/docs/yarn-1.21.5+build.1/net/minecraft/client/input/Input.html), [1.21.5 PlayerInput](https://maven.fabricmc.net/docs/yarn-1.21.5+build.1/net/minecraft/util/PlayerInput.html), [1.21.11 ingredients](https://maven.fabricmc.net/docs/yarn-1.21.11+build.6/net/minecraft/recipe/Ingredient.html), [1.21.11 KeyboardInput](https://maven.fabricmc.net/docs/yarn-1.21.11+build.6/net/minecraft/client/input/KeyboardInput.html), [1.21.11 Vec2f](https://maven.fabricmc.net/docs/yarn-1.21.11+build.6/net/minecraft/util/math/Vec2f.html), [Fabric HUD migration](https://fabricmc.net/2025/06/15/1216.html).

The recipe-display Yarn family uses the pinned Loom remapping plugin and Gradle 9.6 wrapper. Newer Yarn unpick metadata is incompatible with the older Loom 1.8 profile. The plugin split follows [Fabric Loom plugin IDs](https://docs.fabricmc.net/develop/loom/#plugin-ids); [Fabric 1.21.11 porting notes](https://fabricmc.net/2025/12/05/12111.html) require a newer Loom generation. Exact compilation remains the compatibility gate.

Native client-access overlays retain direct storage-list access: [1.21.5 inventory accessors](https://maven.fabricmc.net/docs/yarn-1.21.5+build.1/net/minecraft/entity/player/PlayerInventory.html), [1.21.9 entity position](https://maven.fabricmc.net/docs/yarn-1.21.9+build.1/net/minecraft/entity/Entity.html), and [1.21.11 key categories](https://maven.fabricmc.net/docs/yarn-1.21.11+build.6/net/minecraft/client/option/KeyBinding.Category.html). The 1.21.11 verifier normalizes registry-backed game rules, world-screen callbacks and screenshot scaling separately. These adapters require individual runtime checks despite successful compilation.

Verifier-only API seams also follow their actual boundaries: the screenshot downscale argument starts in 1.21.6, the world-creation return callback becomes a `Runnable` in 1.21.9, and the game-rule package/value API changes in 1.21.11. These helpers are excluded from production jars. Sources: [1.21.6 screenshot API](https://maven.fabricmc.net/docs/yarn-1.21.6+build.1/net/minecraft/client/util/ScreenshotRecorder.html), [1.21.9 world creation API](https://maven.fabricmc.net/docs/yarn-1.21.9+build.1/net/minecraft/client/gui/screen/world/CreateWorldScreen.html).
