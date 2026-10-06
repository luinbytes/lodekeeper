# Navigation and survival rebuild

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
