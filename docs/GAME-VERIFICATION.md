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

On 1.21.1 and 26.3, `-Dlodekeeper.verify.baritone=true -Dlodekeeper.verify.preparedSafety=threat -Dlodekeeper.verify.threatWaterRetreat=true` checks escape from source water beneath a low bedrock roof. The declared-stock fixture supplies ordinary weapons, three ingots and a table, alongside a native NoAI creeper, distant zombie and cow. The current main fixture gives that distant zombie a leather helmet before spawning to prevent startup daylight damage; the player receives no helmet. Its health must still remain four, and all original readiness and completion checks apply. The server must observe the same live creeper at least twelve blocks away, full health, untouched mobs and weapon durability, dry supported player footing, one crafted bucket, no remaining ingots, an empty cursor and an idle engine with navigation cancelled. It isolates water/roof escape and does not prove survival gathering, pursuing-mob handling or natural cave recovery.


## Owned workbench recovery

On 1.21.1 and 26.3, `-Dlodekeeper.verify.baritone=true -Dlodekeeper.verify.preparedSafety=workbench` first gives twelve ordinary oak planks and lets the production wooden-pickaxe command create its own crafting table. Station recovery is disabled only during seeding and enabled before measurement. With the engine stopped, the fixture moves the player six blocks from that table and prepares level support plus one stone. The measured cobblestone command must approach, recover and collect the table, mine the stone, then finish with exact server inventory and stopped navigation.

`-Dlodekeeper.verify.preparedSafety=workbench-blocked` encloses the owned table in twenty-six bedrock cells before the measured command. It must observe one bounded recovery attempt, an intact table and shell, one collected cobblestone and no repeated recovery. Both cases retain their declared setup inventory, server block receipts, natural client status transitions and screenshots. They do not establish fresh-world progression.

At main `6419d9e`, both approach controls fail because the measured standalone command never starts recovery of the preceding command's table. The [paired receipts](evidence/survival-safety/main-6419d9e-station-recovery.json) preserve the unchanged expectations. The separate project table-carry path executes in the natural run recorded there.

The subsequent standalone recovery path retains the original placement record from a successfully completed command. Before gathering, it can try one nearby owned table within twenty seconds. It reserves inventory room for the pending gather, excludes live producers, and retains the session, block, claim and inventory checks. Failure resumes planning without retrying that table during the same command's final cleanup. At `96e017a`, the [unchanged prepared approach and blocked controls](evidence/survival-safety/main-96e017a-workbench.json) pass on both primary versions. Each reachable case recovers the exact table in one continuous approach; each blocked case leaves the table and bedrock shell intact and completes gathering without a cleanup retry. All four finish with full health, an empty cursor and stopped navigation. These controls verify table recovery only. Debug pickup observations record the pinned entity, native goal, inventory and removal sequences; station block observations retain the previous and next receipt values. These logs do not relax pickup acceptance.

Modern main development diagnostics can record a retreat's frozen chunk metadata when debug logging is enabled. Each owned retreat retains at most eight context captures and 65 chunk keys per capture. `RETREAT_SNAPSHOT` records non-loading live availability, pending snapshots, ready revisions, and frozen availability. Completion uses `contextAssociation=observedOnly` because an observed context may never launch a search. These logs do not change admission, path costs, snapshot copying, chunk loading, or fallback limits. The [prepared 26.3 water retreat at `0ec8e88`](evidence/survival-safety/main-0ec8e88-water.json) verifies native injection: eight freeze and six completion records emit, with at most 49 sampled keys. Every sampled interest chunk is ready and frozen live in that fixture. This does not explain the earlier natural water-calculation failure.

Later main air diagnostics add `AIR_SAMPLE` when detailed logging is enabled. Each action records at most sixteen samples, at least one second apart, with client-thread and player/world ownership checks. Samples distinguish water contact, fluid at the feet cell and eye submersion, then record air, health, ground state, collisions, route position and owned input. Applied input is the last vanilla input update. Nearby mob observations inspect at most thirty-two loaded entities and record at most eight monster-marker mobs within sixteen blocks. That prefix can omit nearby threats and includes neutral mobs; client target fields are not server attack receipts. Native path targets and forced input are labelled unavailable. These observations do not change recovery or defense decisions. Both unchanged [prepared air controls at `9cbf94e`](evidence/survival-safety/main-9cbf94e-air.json) pass and emit three `AIR_SAMPLE`s per run.

The air fixture supplies three iron ingots, two cooked beef, starting health three, and water beneath a bedrock roof with an exit ledge and placed table. It contains no hostiles. Each run recovers breathable space, refills air, and crafts one bucket. Final health is 10.433333 on 1.21.1 and 11.366667 on 26.3, with zero deaths, an empty cursor, and an idle engine. These controls verify read-only diagnostics and fixture recovery. They do not reproduce the `0ec8e88` zombie death while recovery stayed active at full air.

The air proof corrects the initial uploaded manifest caption. The primary native frame shows the crafting menu open before final idle. The manual primary capture and modern capture show the completed command. Original screenshot bytes and final native PASS outcomes are unchanged.

## Latest fresh Survival evidence

Clean main `9cbf94eaa2e57a2c28c5e04a902026851053ba52` passes 297 checks: 159 core, 98 navigation, 37 primary adapter, and three modern adapter checks. The [exact-head CI run](https://github.com/luinbytes/lodekeeper/actions/runs/37702345003) passes all 24 profiles. The immutable air and modern proofs retain their CI status at creation; the primary proof records the later CI success.

The [1.21.1 run](evidence/survival-safety/main-9cbf94e-natural-primary.json) passes `gear_diamond` after 509,245 ms (8:29). `serverInventoryCounts` contains all five diamond tools; `serverArmorCounts` contains all four equipped pieces. Minimum and final health are 20, with zero deaths, an empty cursor, and idle cancelled navigation. The generated Normal Survival world supplies no stock, uses seed `483920105`, and starts at `(1.5, 79, 33.5)`, different from prior runs. Diagnostics record two threat responses clearing the same creeper without melee. No air events or samples emit. This is one pass, with no comparative speed, shield, or 37-value native lease result. One crafting table in the final inventory does not prove all stations recovered.

The [26.3 run](evidence/survival-safety/main-9cbf94e-natural-modern.json) starts at `(-13.5, 64, 20.5)` and fails after 45,633 ms during crafting `DRAG`. Health remains 20, with zero deaths and no diamond tools or armor. The final server cursor holds one cobblestone, and the menu remains open. The first fresh full reply records an empty watched grid and cursor count four before the final expected grid and cursor count one. Later full replies arrive after the watch is removed. The first reply's origin remains unproved; the next fix is under design and has not been implemented. No air event or sample emits, so this run does not reproduce or resolve the `0ec8e88` zombie death.

The `0ec8e88` workbench, water, progression, and GUI controls remain evidence for that source only. Its earlier fresh primary no-dry-candidate failure and modern zombie death remain unresolved cases. The `96e017a` failures and released Preview 11 `88f2b52` limits also remain separate. No Preview 12 or complete interruption result follows from these main checks.

## Shield behavior fixture

All nine shield modes pass on both 1.21.1 and 26.3 with the exact `ce3c967` production jars. The corrected runner at `fbd5261` keeps the original class-origin checks when JVM log decorators vary. Queued work reserves seven iron; worn shields block native damage and restore; occupied offhand stock is preserved. Manual takeover uses simulated native key-state injection. The historical ten-step native settings GUI also passes on both primary versions at `ce3c967`. The exact `88f2b52` settings GUI passes all ten checks on each primary version, twenty checks in total. The [native QA receipt](https://github.com/luinbytes/lodekeeper/releases/download/v0.1.0-preview.11/native-qa.json) covers shield controls, saving test floors of nineteen iron and thirty-seven planks, dependent-control disabling, discard, protected plots, and restoration of the original settings. These test floors do not change the defaults of two iron and sixteen planks. Earlier `98a7856` live-creeper checks pass on both primary versions, but they predate f8.

The first fresh 1.21.1 natural survival run at `f8f4264` passes the full diamond loadout in 11:24 with full health and no deaths. It observed no hazard, so it does not verify creeper response or shield use. Shield and the 37-value native lease reruns remain pending at the released `88f2b52` source. A distant owned station remained outside the recovery range; the run does not prove complete station cleanup.

The f8 change preserves full inventory receipt contents without relaxing transfer checks. Earlier `89380e6` and `71462f2` runs still record unresolved stone-pickaxe inventory-transfer failures at 61,928 ms and 62,351 ms. The first f8 natural run did not reproduce either failure. Its same-seed repeat passed stone-pickaxe crafting, then failed after 900,181 ms when the 900-second wall timeout expired. The engine was following the long route toward the earlier station and held no diamond gear; logs show repeated recalculation from the same water cell and vertical bobbing in water. The earlier `f8f4264` 26.3 run fails after 441,801 ms when mining reaches its WALL_TIME limit with zero raw iron collected. It has full health, no deaths, an empty cursor, and no diamond gear. Its displayed remaining route stays at thirteen or fourteen positions for more than five minutes. The displayed index is relative to that remaining route and does not identify the native movement edge. The physical cause remains unproved. The [exact-source CI run](https://github.com/luinbytes/lodekeeper/actions/runs/37660233575) finished with a failed full 24-profile matrix at newer input diagnostics. Both primary jobs passed. The [packaging receipt](evidence/builds/preview11-88f2b52.json) confirms that their CI jars match the native gameplay and GUI inputs byte for byte. See the [Preview 11 checkpoint](OWNED-PREVIEW-CHECKPOINT.md) for the source-specific history and current gates.

At `88f2b52`, fresh 26.3 survival fails after 63,753 ms during the third skeleton retreat from source water. The first two retreats clear; the third path calculation fails before an executor starts. Minimum and final health are 7.499998, with no deaths, an empty cursor, and no diamond gear. The exact search stop reason remains unknown. The 1.21.1 attempt is `INCONCLUSIVE_ENVIRONMENT_ABORT`: storage writes report `ENOSPC`, the runner exits with code 120, and neither a final native JSON nor a finished runner receipt exists. The exact client termination cause is unknown. Two early dry-tree motion samples in the 1.21.1 log confirm that the adapter diagnostics emit data; water physics was not observed. A native failure requires its final receipt. An aborted environment run cannot count as a gameplay pass or failure.

For the `spare` mode, pass the verifier these full flags:

```text
--verify-flag lodekeeper.verify.baritone=true
--verify-flag lodekeeper.verify.shieldScenario=spare
```

Select one `lodekeeper.verify.shieldScenario` mode per run: `default`, `off`, `spare`, `queued`, `iron_short`, `planks_short`, `worn`, `occupied`, or `manual`. These modes cannot be combined with another active verifier mode. Invalid or mixed flags stop before world creation.

## Preview screenshots and public gameplay evidence

[Published Preview 11](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.11) includes thirty public assets, comprising twenty-three original captures and seven build, source, and QA assets. Five captures use the exact released `88f2b52` source; eighteen are historical and retain their source labels. Two original MP4s are flagged unplayable. All thirty asset links returned anonymous HTTP 200, and all ten release screenshots loaded in the browser. The [capture manifest](https://github.com/luinbytes/lodekeeper/releases/download/v0.1.0-preview.11/capture-manifest.json) records their scope.

Each new preview must include representative screenshots as release assets. Capture the exact production jars staged for that release through the artifact verifier. Keep commands and the HUD visible. Name every screenshot with the Minecraft version, preview version, source commit, scenario and `PASS`, `FAIL` or `UNFINISHED` outcome. A capture manifest must record the full source commit, jar SHA-256, screenshot SHA-256, native receipt and short caption. Label supplied-stock fixtures separately from natural survival runs. Video recording is no longer part of the verification or release workflow.

Between previews, upload screenshots to the separate public work-in-progress evidence release as runs finish. Each caption names the exact source and outcome. These screenshots do not establish the behavior of a published preview. Keep exact release-build screenshots in that preview's own release.

Upload the unedited screenshots and their manifest. Embed representative screenshots in the release notes and link the remaining originals, with captions that state what worked and what failed or remains unfinished. Verify each remote asset's size and SHA-256 through an anonymous streamed read-back. Then verify that every uploaded screenshot decodes in a logged-out browser. Both gates must pass before deleting local originals or staging copies. The release assets retain the originals. Retain compact verification, browser and upload receipts. Leave files with failed or unverified uploads in place. Previously published videos remain historical evidence.
