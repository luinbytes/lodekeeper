# Isolated in-game verification

Lodekeeper's planner and navigation tests run without opening Minecraft. This optional client run checks that the Fabric adapter can carry a small set of goals through real client input, server-side block changes, inventory synchronization and crafting.

The verifier is a separate development-only Fabric mod. It is inert unless launched with `-Dlodekeeper.verify=true`; it is not part of the published Lodekeeper jar. The launcher task should use one client with a 1.5 GiB heap and one Gradle worker.

## What it exercises

The verifier creates a peaceful superflat world with a fixed seed under the development run directory's `verification/worlds` folder. It never opens or modifies the normal `saves` folder. On a small raised bedrock pad it places eight oak logs, twelve stone blocks, coal ore, iron ore and four registered ruby ore blocks, clears the player's inventory, and teleports the player to the starting point on the server thread. It then submits ordinary prefixed chat commands through Fabric's client chat hook:

1. `!lk get wood 8` — find, path to and mine the fixture logs from an empty inventory.
2. `!lk get crafting_table 1` — craft a table from the gathered inventory.
3. `!lk get stick 8` — craft sticks from the remaining inventory.
4. `!lk get wooden_pickaxe` — place and open the crafted table, then use its 3×3 grid.
5. `!lk get stone_pickaxe` — mine stone with a suitable held tool and craft the upgrade.
6. `!lk get furnace` — gather the remaining stone and craft a furnace.
7. `!lk get iron_ingot` — mine iron with the upgraded pickaxe, place and open the furnace, fuel it, and recover the smelted output.
8. `!lk get lodekeeper_verification:ruby_gear` — use the explicit custom-source contract to find and mine four registered ruby ores with a stone pickaxe, then craft the custom output with the synchronized 3×3 recipe at the crafting table. The fixture never grants ruby or ruby gear to the player.
9. `!lk get wood <current oak logs + 1>` — after setup changes the integrated server to normal difficulty, sets hunger to 7 and adds one bread plus one extra oak log, verify the player eats the bread, server hunger rises, and the oak-log target is reached.

The result file records pass/fail, engine state, elapsed ticks, inventory counts read on the integrated server thread, the player's server-side position and health, per-case crafting-table observations, server difficulty, food levels, bread counts and screenshot paths. Screenshots are saved under the evidence folder's `screenshots/` subdirectory when Minecraft's screenshot recorder can capture them. These are real survival interactions in a controlled fixture world; they do not establish success in a natural world, on a multiplayer server, or for every Minecraft version.

## Run it

Build the normal Java and Fabric checks first. To launch the optional game check, use the dedicated `runVerificationClient` Loom run task from the repository root:

```sh
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon --max-workers=1 :fabric:runVerificationClient
```

This task must run with the separate `lodekeeper-verification` development mod on the client classpath and the `lodekeeper.verify` JVM property enabled. Start it from the title screen, with no other Minecraft client running. The harness creates a new uniquely named test world and ends its isolated client when complete. It stops with a failure record if the complete run exceeds five minutes.

For the 1.21.1 adapter, select JDK 21 with `JAVA_HOME` and use its own isolated run directory and verification task:

```sh
./gradlew --no-daemon --max-workers=1 -Padapter=1211 -Pminecraft_version=1.21.1 \
  -Pyarn_mappings=1.21.1+build.3 -Ploader_version=0.19.5 \
  -Pfabric_version=0.110.0+1.21.1 :fabric-1211:runVerificationClient
```

When verification is enabled, the harness creates `lodekeeper-sources.json` inside that module's `run/config` directory if it is absent. It accepts an existing file only when its JSON matches the verifier's custom-ore contract; a different file is left untouched and causes the verifier to stop before creating a world.

If the environment does not have the JDK at the path above, set `JAVA_HOME` to a Java 17 installation. Do not launch the normal `runClient` task with the verification mod installed unless the JVM property is absent; the verifier is intended for its dedicated run configuration.

## Evidence and limits

Each version profile writes its own jar beneath that adapter's `build/libs` directory; the output filename is `lodekeeper-<minecraft-version>-<mod-version>.jar`, using `mod_version` from `gradle.properties`:

| Minecraft profile | Adapter | Jar |
| --- | --- | --- |
| 1.20.1 | `fabric` | `fabric/build/libs/lodekeeper-1.20.1-<mod-version>.jar` |
| 1.20.2–1.20.4 | `fabric-1202` | `fabric-1202/build/libs/lodekeeper-<selected-version>-<mod-version>.jar` |
| 1.21.1 | `fabric-1211` | `fabric-1211/build/libs/lodekeeper-1.21.1-<mod-version>.jar` |
| 26.3 | `fabric-modern` | `fabric-modern/build/libs/lodekeeper-26.3-<mod-version>.jar` |

The 26.x adapter uses JDK 25 and `fabric-modern/run-verification`; its evidence is nested under `verification/<run-id>/evidence`.

The development verifier itself is never included in those production jars. Each adapter writes verification output to its own run directory: `fabric/run` for 1.20.1 and `fabric-1211/run` for 1.21.1. A run writes:

- `<module>/run/verification/evidence/run-<id>.json`
- screenshots under `<module>/run/verification/evidence/screenshots/`
- its isolated world under `<module>/run/verification/worlds/run-<id>/`

Keep the world and evidence when diagnosing a failure. After the client has stopped, the module's entire `run/verification` directory is disposable. The nine-case source suite includes acquisition, crafting-table use, tool progression, smelting, custom registry content and food use. Recorded nine-case runs on 1.20.1, 1.21.1, 1.21.2, 1.21.11 and 26.3 include the custom-content and food cases; exact records are linked in the compatibility ledger. Diamond equipment, parkour, bridging and natural-world exploration remain separate scenarios. Table and furnace menu observations come from the server, independently of client screenshots.

## Recorded 1.20.1 check

The [2026-10-05 controlled run](evidence/1.20.1-basic/run.json) passed the original three cases with full server-side health. Eight logs took 979 game ticks; the table took 101 ticks and eight sticks took 120 ticks. These timings include discovery, normal bare-hand mining, movement, inventory clicks and confirmation. They are fixture timings, not a comparison with other mods. This historical result predates the expanded nine-case source; its screenshots belong to that exact run.

## Recorded expanded legacy run

The [1.20.1 nine-case run](evidence/1.20.1-progression/run.json) passed on 2026-10-05 in 162,653 ms. The integrated server observed each target in inventory, four crafting-table openings, a furnace opening, and bread consumption with hunger increasing from 7 to 12. All nine observations report full health. Screenshots accompany the JSON. The run uses the deterministic resource pad and does not establish natural-world exploration or complete mechanic coverage.

## Distant-resource scenario

The optional JVM flag `-Dlodekeeper.verify.exploration=true` selects a single wood goal instead of the nine-case progression suite. The disposable fixture extends the supported floor and puts eight logs at X=80–87. The verifier requires their client chunk to be unloaded when the empty-inventory goal starts, then checks server inventory, travel beyond X=48, a nonzero exploration-attempt count and an idle engine. It grants no logs or tools.

The [recorded 1.21.1 run](evidence/1.21.1-exploration/run.json) passed with eight logs, health 20, X=86.63 and two exploration attempts: 1,209 goal ticks and 78,052 ms for the isolated verification run. Its [artifact record](evidence/1.21.1-exploration/artifact.json) includes the exact archived working-tree source patch used by the development client. This verifies controlled travel into initially unloaded resource terrain; it does not establish natural-world exploration or persistent resource knowledge.

The [recorded 26.3 run](evidence/26.3-exploration/run.json) also passed the distant-resource goal with eight server-observed logs, health 20 and two exploration attempts. Its [artifact record](evidence/26.3-exploration/artifact.json) archives the exact source patch, including bounded fallback among dropped and scanned log outputs. Both clients were run sequentially with no Gradle process during gameplay.

## Single-command diamond equipment

The optional JVM flag `-Dlodekeeper.verify.diamondBoots=true` replaces the nine-case suite with one `!lk get diamond_boots` command from an empty inventory. It is mutually exclusive with exploration mode. The disposable pad contains logs, stone, coal, three iron ores and four diamond ores. It grants no tools, ingredients or target equipment.

A pass requires an idle engine, one server-observed pair of diamond boots, a retained iron pickaxe, and server-observed crafting-table and furnace openings during the case. This checks dependency bootstrapping and actual player interactions together; the prepared resource layout remains separate from natural-world acquisition.

The [1.21.1 check](evidence/1.21.1-diamond-boots/run.json) passed in 166,032 ms with full health and 2,995 goal ticks. Its [artifact manifest](evidence/1.21.1-diamond-boots/artifact.json) and archived source patch identify the development client. The [earlier failed check](evidence/1.21.1-diamond-boots-failed/run.json) is retained with its own source evidence. The planning and approach changes are described in [bootstrap planning](BOOTSTRAP-PLANNER.md).

The [26.3 check](evidence/26.3-diamond-boots/run.json) passed the same command from empty inventory in 171,892 ms and 3,069 goal ticks, with full health, a retained iron pickaxe and both native menus observed on the server. Its [paired artifact manifest](evidence/26.3-diamond-boots/artifact.json) preserves the exact source patch. This modern harness records server evidence without a screenshot. The clients ran sequentially, without Gradle during gameplay.

## Iron pickaxe from a crafting table

The optional flag `-Dlodekeeper.verify.ironPickaxe=true` instead runs one `!lk get iron_pickaxe` goal from exactly one held crafting table. It records ordinary table and furnace openings, full server inventory and four deep deepslate fixture blocks beneath the bedrock pad. It also exports a typed Minecraft-free catalog snapshot and records no-hint planner probes as separate planning evidence. Prepared resources are not natural-world ore-discovery evidence.

## Native stonecutting batch and stop

`-Dlodekeeper.verify.stonecutting=true` selects a 144-slab native batch from exactly 128 stone and one stonecutter. Adding `-Dlodekeeper.verify.stonecuttingDrain=true` sends `!lk stop` after owned input is observed in the native menu and before output is collected. The drain outcome requires a fresh server observation after stop, exactly 128 returned stone, zero slabs, an idle engine and full health. Both modes are mutually exclusive with the other acquisition fixtures and keep the normal resource bounds.

## Native fast-cooking batch

The optional JVM flag `-Dlodekeeper.verify.cookingStation=smoker` or `blast_furnace` selects one native 72-output batch. It is mutually exclusive with the exploration, diamond-boots and bulk-wood fixtures; an invalid selector fails before world creation. Only this mode extends the verifier limit to 10,000 ticks and 500 seconds.

The disposable bedrock pad contains no acquisition resources. The verifier supplies two 64-item raw stacks, nine coal and one station item: porkchops for a smoker or raw iron for a blast furnace. It supplies no cooked porkchops or ingots. Before sending one command it requires that exact starting inventory and a visible supported floor. Completion requires exactly 72 outputs, 56 raw items remaining, zero inventory coal, full health, an idle engine and an integrated-server observation of the correct native menu. Initial stock, final inventory and command timing are recorded separately from world startup.

This checks station placement and native multi-stack cooking with an exact coal budget. It does not establish acquisition of the supplied resources, cancellation, cold-start waiting, custom short timers or remote-server behavior. The controlled [26.3 blast-furnace batch](evidence/26.3-blast-furnace/run.json) passed with the declared quantities and full health. Completion took 361,958 ms from the command. The [artifact manifest](evidence/26.3-blast-furnace/artifact.json) identifies its frozen source; the [1.21.1 smoker batch](evidence/1.21.1-smoker/run.json) also passed with the same quantities and health, in 361,457 ms from the command. Its screenshot and [artifact manifest](evidence/1.21.1-smoker/artifact.json) preserve the corresponding development client.

## Inaccessible coal recovery

`-Dlodekeeper.verify.coalRecovery=true` selects one ordinary `!lk get coal` command from only a stone pickaxe. The nearer ore at `(6,64,2)` is enclosed in bedrock, while ore at `(16,64,2)` is reachable. The outcome requires the exact nearer target to appear in the engine rejection set, the encased ore to remain, the reachable ore to be mined, exactly one coal and the pickaxe in server inventory, full health and an idle engine. It is exclusive with other modes. This fixture proves bounded target recovery when it passes; it does not prove natural underground coal discovery.

`-Dlodekeeper.verify.coalStartSurface=dirt_path` or `farmland`, together with `coalRecovery=true`, replaces the starting 3×3 floor with the actual fractional block shape. Before the command, a server observation must show feet at 63.9375 and all nine floor blocks intact. Completion still requires coal fallback, exact inventory and full health, and additionally all nine floor blocks unchanged. Farmland is hydrated to prevent unrelated random-tick drying. Invalid values or use outside coal-recovery mode refuse startup. These checks cover a bounded escape onto nearby full-block ground, not general slab/stair/path traversal.

## Mixed collision-shape course

Add `-Dlodekeeper.verify.navigationCourse=mixed` together with `-Dlodekeeper.verify.coalRecovery=true`. This replaces the normal coal pad with a one-cell corridor across bottom slabs, oriented stairs, dirt path, hydrated farmland, several snow heights, a top slab and a bare full-block jump ledge. The goal remains one ordinary `!lk get coal` command from only a stone pickaxe. The accessible ore is beyond the ledge, so interaction reach cannot bypass the jump.

A pass requires 15 grounded server-observed cell/height checkpoints, recorded completed WALK and JUMP path edges, all protected block states unchanged (692 with the widened drop-catching landing pad), minimum health 20, trapped ore rejected and intact, accessible ore mined, exactly one coal plus the retained pickaxe, and an idle engine. A separate native fence query must reject point, body and sweep contact with the fence extending from the block below the feet. The JSON retains native stance and route diagnostics.

Run only one isolated client at a time, without a concurrent Gradle build. Development-class proofs must archive their exact working-tree patch; release acceptance must repeat with the exact downloaded production jar and independently verify its loaded classes. This controlled course does not cover fractional airborne moves, all stair orientations, changing terrain during flight, natural-world recovery or multiplayer behavior.

## Native collision changes and route epochs

`-Dlodekeeper.verify.geometryEpoch=true`, together with `nearbyWood=true`, runs native collision and context checks before the ordinary wood command. It requires a fully loaded dynamic-block fixture, proves unchanged searches stay active, then changes a collision shape without changing its block state and requires a stale search with no path. It also checks query-free world/player changes, actual sneak input, offhand components, standing scale and fail-closed watch-capacity exhaustion. The fixture lives only in verification sources and is excluded from production jars.

The [paired native evidence](evidence/native-geometry-epoch/README.md) records expected failures in the immutable Preview 6 jars and passing unpublished candidate jars on 1.21.1 and 26.3. This optional mode skips the route benchmark so the same verifier can exercise the older published planner. It grants no wood or goal items; acquisition after the checks uses the ordinary command path.


## Baritone request yields and water retreat

With `-Dlodekeeper.verify.baritone=true -Dlodekeeper.verify.bulkWood=true`, add `-Dlodekeeper.verify.miningRequestLimit=true` to exercise a ten-second mining request cap. The fixture supplies 80 logs in terrain, starts with no inventory, and requires exactly 64 collected logs, an idle engine and cancelled navigation. The console records each productive `MINING_REQUEST_YIELD`, including actual initial/current counts and retained per-position rejections. This mode allows 360 seconds for the whole verifier.

Adding `-Dlodekeeper.verify.miningZeroYield=true` sets a one-block distance cap, before the first log six blocks away. This deliberately produces a failed diagnostic receipt. A valid expected-negative result requires a native `DISTANCE` yield with initial/current/collected counts all zero, a matching paused-engine reason, and no target inventory. A generic timeout does not prove the guard. Production configuration limits remain unchanged.

On 1.21.1 and 26.3, `-Dlodekeeper.verify.baritone=true -Dlodekeeper.verify.preparedSafety=threat -Dlodekeeper.verify.threatWaterRetreat=true` checks escape from source water beneath a low bedrock roof. The declared-stock fixture supplies ordinary weapons, three ingots and a table, alongside a native NoAI creeper, distant zombie and cow. The server must observe the same live creeper at least twelve blocks away, full health, untouched mobs and weapon durability, dry supported player footing, one crafted bucket, no remaining ingots, an empty cursor and an idle engine with navigation cancelled. It isolates water/roof escape and does not prove survival gathering, pursuing-mob handling or natural cave recovery.
