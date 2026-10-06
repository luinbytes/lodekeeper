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


## Ninth development checkpoint

The exact `5b7e5230` 1.21.1 development jar crafted a diamond pickaxe in a fresh normal-survival world. The run failed at 478,002 ms when the player drowned. The [native receipt, causal trace, four images and artifact identity](evidence/navigation-rebuild/checkpoint-09.json) preserve the failed full-set attempt. All 24 exact version builds passed for this source.

The new fuel branch ran in the game. Iron smelting used two birch planks after a one-log, four-plank craft. The inventory also chose to replace the distant furnace using stored materials. This establishes those decisions in one native run, not a full-set timing pass or a repeated speed comparison.

The next failure was air safety. A portable workbench action held the player underwater until its twenty-second timeout. Health recovery then stopped movement to rest underwater, and the player drowned. The next candidate rejects stationary underwater workbench recovery and places a latched air action ahead of health rest, combat and new tasks. Owned crafting transactions must drain before emergency navigation, and foreign inventory or movement ownership must remain untouched. The native escape fixture and full survival rerun are pending.


## Air recovery and held hunting tools

The air candidate uses a separate action that drains an owned station transaction, reaches a breathable loaded stance, then waits for the actual eye position and air supply to recover. It runs before health rest and portable workbench recovery. A failed or manually interrupted escape pauses the requested project rather than deleting it. The search has a shared time and probe bound and retains candidates found before that bound.

The first prepared air client failed before issuing its command because a stale post-teleport water flag prevented setup readiness. Its [setup failure receipt](evidence/survival-safety/preview9-air-setup-failure-1211/runtime.json) is kept separately from production evidence. After correcting that latch, the next client reached the production AIR action, but all three native searches failed without moving. Baritone considered 22 movement types and retained only the starting node. Its [submerged-cell rule](https://github.com/cabaletta/baritone/blob/10e65932e597ee5f11a654030f17798646f563f2/src/main/java/baritone/pathing/movement/MovementHelper.java#L215-L230) rejects water when another fluid cell sits above it. The [native failure receipt](evidence/survival-safety/preview9-air-native-before-1211/runtime.json) and causal log retain this outcome.

The candidate adds a bounded, loaded-cell swimming graph and an explicit swimming state before the native surface or dry handoff. It uses ordinary player input, checks the next body space again before steering, and preserves manual and foreign-process ownership. The prepared pass must show the production recovery sequence before food or crafting, followed by native bucket and inventory receipts. Prepared water-to-ledge and bucket checks passed on 1.21.1 and 26.3. The stricter [1.21.1 receipt](evidence/survival-safety/preview9-air-ordered-1211/runtime.json) also observes the production AIR state, its completion, and a later native snapshot with full enough air before crafting begins. Sealed dry rooms and surface-only escape remain separate unverified cases.

Fix Root Causes changed hunting selection from a sword/axe whitelist to native damage ranking across ordinary held stacks. Mining tools must retain sixteen durability after an attack, and swords or axes retain two. Project-reserved items remain excluded. A settled attack slot yields when the user changes it. The earlier barehand moving-cow fixture remains a separate proof; the new held-tool case is pending.


## Nearby stations and remaining materials

The first successful swim exposed a separate planning failure: a placed crafting table beside the dry ledge was not included in the planner inventory. A bounded loaded-cell station observer now advertises supported nearby stations. It retains all observations so a rejected closer table cannot hide a usable alternative. Only workbenches created by Lodekeeper are eligible for portable recovery; observing an existing table does not transfer ownership. The [failed swim-and-station case](evidence/survival-safety/preview9-air-swim-before-stations-1211/assessment.json) and corrected [1.21.1](evidence/survival-safety/preview9-air-stations-1211/runtime.json) and [26.3](evidence/survival-safety/preview9-air-stations-263/runtime.json) receipts preserve the sequence. These prepared cases grant their documented starting materials.

Optional food cooking now plans the remaining project first and protects held ingredients needed by its future steps. The overlay adds existing reservations and preserves native tool durability and station data. It applies only to optional work; project execution keeps its original spendable inventory. Optional cooking also requires a plan without resource gathering, avoiding a fuel or station-material detour. Seven Java regressions cover future sticks, reserved logs, surplus fuel, overflow, physical tool lots and failed-plan rejection. The full empty-inventory benchmark remains pending for this candidate.


## Ore variants and portable workbenches

[Checkpoint 12](evidence/navigation-rebuild/checkpoint-12.json) passes the full diamond project on Minecraft 1.21.1 in 835,143 ms (13 minutes 55 seconds). The empty normal-survival world uses seed 483920105, normal difficulty, no cheats and no bonus chest. The integrated server confirms all five tools and all four equipped armor pieces, full health, no deaths and an idle engine with navigation stopped. This local candidate is source `ccb01917bd70117d5d13b1af9dda7341d79fbc3e`; its CI run was cancelled by the later fuel change. It establishes one seeded gameplay result, not every seed's timing.

The preceding depth experiment failed because its request selected only ordinary diamond ore after descending into deepslate. It also restarted descent whenever branch exploration moved away from its preferred level. The candidate now combines compatible ore variants under the existing drop count, tool and wear contract, and allows the native route to explore after one descent. Different yields, tool wear, attributes and excluded sources remain separate. Six Java regressions cover these contracts.

The successful run still spent over three minutes returning for one log. Fuel conversion had rejected the log-to-planks recipe because planks also had a mining source. Source `7ba8d375102bce165819bc8579456ac3134f3f2a` keeps the proven crafting recipe for this fuel choice, preserves stock reservations and retains completed conversion proofs when its bounded scan ends. The focused regression checks the real consumed quantities and usable wood remaining for tool handles. The release candidate passes 104 core and 91 navigation tests; its full natural-world repeat is in progress.

Prepared native checks on [1.21.1](evidence/survival-safety/preview10-workbench-1211/assessment.json) and [26.3](evidence/survival-safety/preview10-workbench-263/assessment.json) recover a workbench created by the production crafting command from six blocks away, collect its drop and complete a cobblestone request. The paired [1.21.1](evidence/survival-safety/preview10-workbench-blocked-1211/assessment.json) and [26.3 blocked checks](evidence/survival-safety/preview10-workbench-blocked-263/assessment.json) keep the bedrock-sealed table intact, reject recovery after one attempt and continue mining. These fixtures grant twelve ordinary planks before the setup command and prepare their geometry only while the engine is stopped. Their measured foreground commands receive no further fixture mutations.

The first workbench verifier incorrectly split one recovery at the engine's movement-cancellation handoff. Its [failure receipt](evidence/survival-safety/preview10-workbench-verifier-gap-1211/assessment.json) remains available. The corrected observer keeps that handoff inside the existing episode and retains the cumulative twenty-second limit.


The next fresh-world repeat was stopped after a food hunt spent sixty seconds pursuing an untouched cow, then selected the same cow again after the engine's ten-second cooldown. The [interrupted experiment](evidence/navigation-rebuild/checkpoint-13-interrupted/interruption.json) retains its exact candidate, source and causal trace. Its spawn location differed from checkpoint 12, so it is not a paired overall speed comparison. The next candidate invokes the existing per-entity rejection before a hunt timeout, shortens the action bound to thirty seconds and excludes a failed target for two minutes. Manual input and player/world checks still run before this timeout handling. Native verification of this change is pending.


## User trace and exact release candidate

The [user's cobblestone trace](evidence/navigation-rebuild/user-cobblestone-stall-2026-10-06/assessment.json) records 125,745 ms to acquire one block. The old custom navigator searched for an oak log until its first 61-second gathering timeout, then repeatedly restarted the stone approach. The excerpt omits the installed jar version. Its `search_cpu_ms` and sixteenth-height fields identify the custom navigator; the current production backend emits `backend=baritone` events.

Source `852b72f177befc1b55771cee86076544f39c9f9b` passes [all 24 exact CI builds](https://github.com/luinbytes/lodekeeper/actions/runs/37478984017) and 195 core/navigation regressions. The frozen local 1.21.1 and 26.3 jars match their downloaded CI jars byte for byte. The current native full-project run still selected two whole logs for iron fuel, despite the focused conversion test passing. The full-catalog fuel choice remains an efficiency defect; the release does not claim that it eliminates the late wood resupply.


## Exact Preview 10, 1.21.1

[Checkpoint 14](evidence/navigation-rebuild/checkpoint-14.json) passes the full project in 719,372 ms (11 minutes 59 seconds) on source `852b72f177befc1b55771cee86076544f39c9f9b`. The tested jar is byte-identical to the 1.21.1 CI artifact. The integrated server confirms all nine items, all four equipped armor pieces, final health 20, no deaths and stopped navigation. First server movement occurred after 1,638 ms. Minimum health was 9.33, so this run includes damage and recovery.

The world was generated normally with seed 483920105, an empty inventory, normal survival, no cheats and no bonus chest. Its spawn differed from the earlier full pass. These runs do not establish a paired speed improvement or timing across other seeds. All observed cow hunts succeeded, leaving the new timeout cooldown unexercised in this run. The final screenshot was inspected. The exact 26.3 full-project repeat is recorded below.


## Exact Preview 10, 26.3

[Checkpoint 15](evidence/navigation-rebuild/checkpoint-15.json) passes the full project in 573,306 ms (9 minutes 33 seconds). The jar from source `852b72f177befc1b55771cee86076544f39c9f9b` is byte-identical to the 26.3 CI artifact. First server movement occurs after 238 ms. The integrated server confirms all five tools, all four equipped armor pieces, health 20 throughout, no deaths and idle, cancelled navigation.

This fresh normal-survival world starts empty with seed 483920105, normal difficulty, no cheats and no bonus chest. Iron smelting selects two spruce planks, and further wood is gathered before descending. The final screenshot was inspected. This is one world per primary version; it is not a comparison with other mods, a guarantee for other seeds or a complete Minecraft mechanic test.


## Next development fuel preference

The [held-fuel regression](evidence/planning/held-fuel-priority-2026-10-06.json) reproduces the 1.21.1 fuel waste with two logs and three planks. The original rule skipped conversion proofs whenever enough output fuel was already held. Both fuels then tied on immediate stock and quantity, allowing logs to win by item order. Keeping the same value proof for held outputs lets the planner burn the planks and retain the logs for later recipes. It does not add a craft when the fuel is already held.

The expanded existing regression failed before this change and passes afterward. Both exact 1.21.1 and 26.3 development builds pass with 104 core and 91 navigation tests. Independent source review of `dd2e516` against `a739962` found no material issue and confirmed protected inventory, recipe ambiguity, station readiness and scan bounds. All 24 exact builds pass both the production fix and the later native-fixture source `c187611`. This work remains Preview 11 development until publication; Preview 10's immutable tag and binaries retain the recorded gameplay scope above.

The native fixture reproduces the wrong fuel choice in [Preview 10](evidence/survival-safety/preview11-held-fuel-before-1211/assessment.json). The same fixture passes with Preview 11 on [1.21.1](evidence/survival-safety/preview11-held-fuel-after-1211/assessment.json) and [26.3](evidence/survival-safety/preview11-held-fuel-after-263/assessment.json). It starts with three raw iron, two logs, three planks, a furnace and a crafting table. Ordinary commands smelt three ingots using two planks, then convert one retained log to make eight sticks. No fixture stock changes occur between commands. Both native receipts require drained furnace slots, an empty cursor and stopped navigation. The setup and final screenshots were inspected.

[Checkpoint 16](evidence/navigation-rebuild/checkpoint-16.json) passes a fresh 1.21.1 world in 512,889 ms, or 8 minutes 33 seconds. It starts empty in normal survival with seed 483920105, no cheats or bonus chest. The server confirms all nine retained targets, all four equipped armor pieces, health 20 throughout, no deaths and idle navigation. First server movement occurs after 1,706 ms. This jar matches both the production-fix CI artifact and the later QA-build jar byte for byte. Its spawn differs from the prior run, so this does not establish a paired speed improvement. [Checkpoint 17](evidence/navigation-rebuild/checkpoint-17.json) fails the new 26.3 repeat at 241,538 ms after collecting three diamonds. First server movement occurs after 1,977 ms, with health 20 and no deaths. Furnace placement rejects two air candidates underground, then pauses the preserved project. The final screenshot was inspected. This candidate is not published; station placement needs a fix and another native run.
