# Navigation and survival rebuild

The current release candidate is Preview 8 at `6ca3e283cffc553a5885eaa5b9ff5bff1329541a`. [All 24 exact builds pass](https://github.com/luinbytes/lodekeeper/actions/runs/37435670896). The full fresh-world diamond benchmark remains unpassed.

The current prepared-world checks did not cover the repeated departure failures and elevated drops seen in a natural village. Lu authorized replacing navigation with Baritone on 2026-10-06, followed by AltoClef routines where they improve survival automation. This supersedes the original independent-navigation restriction.

The working period ends at 2026-10-06 15:03 UTC. The acceptance benchmark is one command in a new normal survival world, starting without items, obtaining all five diamond tools and all four armor pieces. This needs 35 diamonds. Command time and world startup time are recorded separately. The target is 10–15 minutes; success on one seed does not guarantee that time on every seed.

## Stack and ownership

Use the official, version-matched Baritone API Fabric binary as the first movement and mining base. It already calculates later path segments during movement. Lodekeeper owns the goal queue, dependency planning, exact inventory receipts, station transactions, configuration and task UI. AltoClef's resource and survival routines will be assessed and ported with their original license notices where useful. Its old Minecraft interfaces need adaptation before use.

The first implementation keeps Minecraft-dependent API calls in the Fabric adapters. `core` stays a Java 17 recipe and goal model. `nav` stays the route-view and existing diagnostic proof model; it no longer drives default movement after migration. A route view copies bounded Baritone path positions without querying terrain during rendering.

Movement ownership is explicit. A task holds a navigation/mining/pickup request and a scoped settings lease. Only one controller writes player input. Route calculation or a paused path is not arrival. Lodekeeper verifies interaction reach and actual inventory gain. Cancellation lets an airborne movement finish safely before an inventory transaction or another controller takes control.

Mining uses a cluster process instead of collecting after every confirmed block removal. The process searches, breaks and collects; Lodekeeper's exact output count remains the completion condition. Required tool tier and safe durability are checked before delegation and during execution. Predicted drops never satisfy crafting requirements.

Building uses only unreserved stock. A type whitelist cannot enforce a protected count, so the first implementation excludes any scaffold type that has a reservation. Maintained targets, active recipes, project goals and owned station blocks remain protected. Expanded count-based allocation needs its own verification.

## Alternatives considered

Replacing just the old A* queue would retain expensive native geometry and the planner/executor interaction mismatch. Repeated source retries would still recreate the same failed departure.

Porting all of AltoClef's Minecraft 1.18 runtime first would require a broad game API migration before proving movement. The chosen path establishes the current Baritone base, keeps the already tested native crafting transactions, then ports survival routines in verified units.

## Verification sequence

1. Compile and package the exact 1.21.1 and 26.3 APIs, preserving their license texts, hashes and source links.
2. Verify ordinary movement, calculation during movement, pickup, required tool handling, inventory protection and cancellation with the exact packaged jars.
3. Verify nearby gathering and tool bootstrapping in a natural world. Add the user's repeated DROP and elevated-drop cases as regression checks.
4. Run the full diamond benchmark in disposable worlds without resource placement, item grants or post-spawn teleportation. Record all failures and client resource use.
5. Build every exact profile, publish validated artifacts, then improve observed bottlenecks and expand mechanics.

The public Preview 7 remains immutable. It predates this rebuild and does not contain the reported pickup or repeated obstruction fixes. This document describes planned and in-progress work, not completed features.

## Sources and licensing

Baritone's [integration setup](https://github.com/cabaletta/baritone/blob/1.21.4/SETUP.md) specifies the API artifact for other mods. Its [LGPL license](https://github.com/cabaletta/baritone/blob/10e65932e597ee5f11a654030f17798646f563f2/LICENSE) applies to the upstream library. Lodekeeper's original code keeps its MIT license. Packaged dependencies must include notices, license texts, exact corresponding source and replacement/relinking instructions.

AltoClef's [MIT license](https://github.com/gaucho-matrero/altoclef/blob/af22e3bc2f03dde45da703f5f7535baae18ea486/LICENSE) must accompany any ported code. Its [task framework](https://github.com/gaucho-matrero/altoclef/tree/af22e3bc2f03dde45da703f5f7535baae18ea486/src/main/java/adris/altoclef/tasksystem) and resource routines are research inputs until adapted and tested.

## First development checkpoint

Both 1.21.1 and 26.3 compile with their pinned Baritone APIs. A prepared 1.21.1 tree-to-table check passed. The first natural-world iron attempt spent too long selecting an absent wood type and then failed to finish iron mining within five minutes. Grouped log discovery reached birch in 7 seconds on the next attempt; that run stopped after 24 seconds because station placement searched only the current height. The next change searches other heights and approaches valid placement sites. This recovery still needs runtime proof. Different random spawn positions prevent treating these two attempts as a paired speedup result.

The independent wrapper review also found that Silk Touch tools can produce ore blocks instead of the requested raw material. Output-aware tool selection is required before release. Exact candidate hashes and failed outcomes are recorded in [checkpoint evidence](evidence/navigation-rebuild/checkpoint-01.json). The full diamond benchmark has not passed.


## Second development checkpoint

The first rebuilt checkpoint passed every exact build profile from 1.20 through 26.3 in [CI](https://github.com/luinbytes/lodekeeper/actions/runs/37413541751). A natural 26.3 wood request received a birch log after 6,052 ms and reached idle after 9,268 ms. The client then hit its shutdown watchdog because upstream worker threads remained alive. This was a gameplay pass and a failed complete run. The next changes close the pinned upstream executor only when Minecraft quits.

A natural 1.21.1 iron attempt reached wooden tools and three cobblestone, then failed returning to a table placed on the canopy. Station supports now exclude leaves, logs and damaging blocks. New actions recover reachable, self-placed workbenches before gathering travel and acquire meat from nearby ordinary cows, pigs and sheep when hungry. Eating respects requested inventory targets. Output-aware mining avoids Silk Touch when it would yield the wrong item; generic planner tool forecasts conservatively assign those stacks no ordinary-harvest capacity.

Both adapter endpoints compile with these changes. Native station transport, hunting, output compatibility and shutdown tests are next. These changes have not passed the full diamond benchmark. [Checkpoint evidence](evidence/navigation-rebuild/checkpoint-02.json) retains the failed outcomes and exact artifact identities.

## Third development checkpoint

Fresh normal-survival worlds, empty inventories, and the packaged `eae836c` jars completed `!lk get iron_pickaxe` on both tested endpoints. The 26.3 run finished in 167,556 ms with health and hunger 20. The 1.21.1 run finished in 184,130 ms with no deaths, but health fell to 6.17 and one workbench pickup exceeded its timeout. Both clients exited with code zero. These are two unpaired runs with different spawn positions, not a competitor speed comparison. [Exact receipts and artifacts](evidence/navigation-rebuild/checkpoint-03.json) retain those limits.

The next source changes merge fresh discoveries into the active native mining process, preserving per-position rejection across refreshes. Generated accessors are selected by the full upstream artifact digest, with field roles verified from all 14 pinned jars and their corresponding source. An incremental palette cursor replaces unbounded chunk scans. Native Mixin execution still needs a packaged-client run.

Tool forecasts now preserve physical stack wear and Silk Touch behavior. Native gather contracts declare compatibility when the block item matches the requested drop. Food preparation before ore travel, earlier eating for healing, and recipe-input reservations are being verified. The project timer spans its full goal queue, and the HUD can identify the actual current mining route target. The full diamond benchmark remains unpassed.


## Fourth development checkpoint

The packaged `755fd70` build passed all 24 exact version profiles in [CI](https://github.com/luinbytes/lodekeeper/actions/runs/37417589995). The next normal-survival 1.21.1 run attempted the full `gear_diamond` project. It began moving after 1,578 ms and ran for 783,347 ms before the health safeguard paused it. No death occurred. The inventory held a diamond pickaxe, axe, boots, and chestplate, plus two spare diamonds. The armor slots were empty. This is a failed full-set benchmark. [Checkpoint receipts](evidence/navigation-rebuild/checkpoint-04.json) preserve the exact artifact and integrated-server observations.

The native mining accessors applied in the packaged client. Debug logs show fresh discoveries merging into an active mining request and later path segments calculating during movement. Food approach failures consumed time. A skeleton was visible in the final frame, and crafted armor had not been equipped.

The next changes plan project materials together, preserve planned tool upgrades before bulk gathering, and cook ordinary spare meat before further travel. Armor quick-moves use native slot predicates and receipt checks. Equipped stock counts toward goals but stays outside crafting ingredients. Threat response and the continuous project timer still need native verification. These changes do not establish the full-set target or support for every Minecraft mechanic.


## Fifth development checkpoint

The `f7f7281` normal-survival run began moving after 1,200 ms and acquired three raw iron before failing at furnace placement after 105,992 ms. Health and hunger stayed at 20, with no deaths. The saved world contained a usable air square directly beside the player, but candidate selection rejected it because its squared block-position distance was less than two. This is a failed full-set benchmark. [The server receipt and artifact identity](evidence/navigation-rebuild/checkpoint-05.json) retain the failure.

Prepared native armor-transfer checks passed in 1.21.1 and 26.3. Those fixtures seed an ordinary helmet and verify its transfer into the native head slot, with no extra crafting and an empty cursor. They do not prove survival acquisition of armor. The full diamond target remains unpassed.

The next patch removes the adjacent-site exclusion and clears at most two exact natural blocks for a station pocket when no valid air site exists. Project goals remain queued on a station-placement failure. Food reservations, protected offhand stock, threat handling and cramped-room receipts are being checked in isolated clients. The intermediate-version API corrections still need a fresh complete CI run.


## Stored-material and ownership regression checkpoint

The packaged `918bf43` 1.21.1 offhand fixture passed its food and bucket phase, then paused on a stick request despite two stored oak logs and a held crafting table. Eight additional logs were reserved in offhand, and breaking was disabled. The planner selected a gathering step instead of a plan using stored logs. [The failed native receipt](evidence/survival-safety/offhand-stored-materials-before-1211.json) and [artifact identity](evidence/survival-safety/offhand-stored-materials-before-1211-artifact.json) preserve that regression.

The fix gives `planFast` a small stored-material recipe search before its general seed, sharing the original deadline and node budget. It respects protected stock and preserves station availability and cooking duration ordering. Both adapters remove gathering sources from planning when breaking is disabled, including exploration recovery. Wood aliases count offhand stock toward their goal. The debug console records the chosen step before execution begins.

Survival actors release native entity, player and world references after cancellation or completion. Food actions restore hotbar selection only for the player and world that started the action. Prepared offhand, food, equipment, threat and station-pocket cases are being rerun against the packaged changes. These fixes do not establish the full diamond target.


## Sixth development checkpoint

The packaged `21beb07` normal-survival run began moving after 1,325 ms and reached an iron pickaxe after 297,475 ms. It failed at 383,128 ms during its first diamond descent. Health stayed at 20, hunger ended at 17, and no deaths occurred. Four cooked beef remained. A creeper stayed within the ten-block clearance distance, and retreat stopped in water beneath a low gravel roof until the 15-second defense budget expired. [The native receipt and artifact identity](evidence/navigation-rebuild/checkpoint-06.json) preserve this failed full-set run.

All four prepared 1.21.1 safety clients passed, covering stock reserved in offhand, ordinary food use, native melee without friendly damage, armor transfer and a cramped furnace pocket. The first 26.3 station-pocket client failed because a 256-fuel catalog cutoff omitted coal, forcing a wood dependency despite held coal. Prepared fixtures grant their starting materials and do not establish fresh-world survival acquisition. The fuel context now rejects overflow above 512 instead of silently publishing a partial list. Native verification of that correction is pending.

Mining request limits now distinguish a productive yield from zero collected output. A productive yield replans with the same source and retains bounded per-position rejections; zero output pauses with explicit counts. Food batching and safe retreat recovery are being checked before the next full-set attempt. The full diamond target remains unpassed.

## Seventh development checkpoint

The exact `6ca3e283` 1.21.1 jar started moving after 1,387 ms in a fresh normal-survival world with an empty inventory. After 900,115 ms it had equipped all four diamond armor pieces and made a diamond pickaxe, axe and hoe. Three spare diamonds remained, but the sword and shovel were missing. It began gathering another birch log from underground because no sticks remained and only one plank was stored. The run had no deaths, minimum health 7.33 and ending hunger 6. This is a failed 15-minute full-set benchmark. [The native receipt, final image and artifact identity](evidence/navigation-rebuild/checkpoint-07.json) preserve the result.

The same source passes prepared native water-retreat cases on 1.21.1 and 26.3. Both preserve the same unharmed creeper and end on dry support at least twelve blocks away. Other prepared cases cover 26.3 station-room clearing with held coal, offhand reservations, armor transfer, and ordinary melee. One earlier modern melee fixture failed before the command because its zombie died during setup; a diagnostic rerun passed without changing production behavior. That setup failure remains preserved.

A 1.21.1 64-log fixture completes across 21 productive ten-second mining-request boundaries. A separate zero-yield distance-cap case produces the expected explicit failure. These are prepared cases with supplied terrain and inventory, not fresh-world progression tests. [The safety evidence](evidence/survival-safety/checkpoint6ca3.json) records each outcome and its limits.

## Moving food and native wood refresh

The next development changes use the native follow process for a tracked animal. Health drops reset failed-route accounting, so successful knockback does not exhaust the retry budget. Foreign follow ownership pauses Lodekeeper without cancelling the replacement process or restoring a stale selected slot. Independent source reviews covered callback, cancellation and engine ownership handling. A foreign-process takeover remains unverified in a native client.

The [1.21.1](evidence/survival-safety/preview9-pursuit-1211/run.json) and [26.3 prepared moving-cow cases](evidence/survival-safety/preview9-pursuit-263/run.json) passed with normal animal AI. The integrated server observed the cow's movement and death, food received, bucket crafting and stopped navigation. The fixture supplied three iron ingots, a crafting table and a bedrock pen. It proves a bounded pursuit and food interruption, not empty-inventory progression.

A bounded project replan can consolidate equivalent initial wood sources when it preserves feasible recipes and does not add gather operations. Focused Java regressions cover fuel quantities, species-specific targets and catalogue view limits. The eighth run confirmed a mixed initial wood plan and the consolidated plan used one wood species. It also showed two later costs unrelated to species selection: burning raw logs as furnace fuel and returning to an old furnace for a small cooking batch.


## Eighth development checkpoint

The exact development jar with the `a5f89966` production sources started moving after 1,372 ms. It failed the full-set target at 900,163 ms. The player had a diamond pickaxe, axe, helmet, chestplate and boots, plus two spare diamonds. The leggings, hoe, shovel and sword were missing. Minimum and final health were 20, final hunger was 20 and no deaths occurred. [The native receipt, four screenshots, planning trace and artifact identity](evidence/navigation-rebuild/checkpoint-08.json) preserve the failure. The runner received a seven-character source label, so its original `sourceCommit` remains null. A separate source confirmation checks seven recorded file digests against the full commit. All 24 version builds passed for that commit.

Iron smelting burned two birch logs even though crafting one log into four planks preserves more fuel capacity. A later two-beef cooking request returned to an old furnace and delayed project work from 11:16:23 until 11:18:41 local time. The next candidate prefers a proved conversion using held ingredients, keeps sufficient held coal first, and can replace a distant station when held materials and local or carried crafting stations permit it. Failed local replacement plans retry with the original station inventory. Focused regressions cover native log alternatives, protected and rounded fuel quantities, mixed station ingredients, carried station consumption and proof deadlines. Native verification of this candidate is pending.
