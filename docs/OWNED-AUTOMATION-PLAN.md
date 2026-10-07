# Preview 11 owned automation plan

Preview 11 is an unreleased development candidate. Its navigation kernel is built into Lodekeeper under `dev.lodekeeper.navigation.kernel`. The candidate does not load a separate Baritone runtime. Its source profile map routes 24 exact Minecraft profiles through 14 navigation source families.

## Source architecture

The build flattens relocated kernel classes into the Lodekeeper artifact. The source family locks record upstream commits, archive hashes, and reviewed overrides. The LGPL-3.0-or-later notices and corresponding source stay available with the release source bundle.

The shared core and navigation modules target Java 17. Fabric adapters use the exact Minecraft and Fabric APIs for each profile. A profile is supported only after its exact artifact compiles. Runtime checks remain a separate gate.

## Protection and recovery

Claims name a world, dimension, and inclusive 3D box. Automated break and place actions check the claim snapshot during planning and again before a native action. Placement checks include adjacent support blocks. This covers every support face used for torch placement. If world identity or claim data is unavailable, automated block edits pause.

Station discovery prefers usable stations that are already loaded inside claims marked for station use. The ownership ledger distinguishes discovered stations from stations placed by the current Lodekeeper job. Recovery requires ownership and server receipts, an empty station, inventory room, and current claim permission. Pickup follows the exact item entity UUID and validates inventory receipts.

Cleanup has a limit of 60 seconds. It leaves unprocessed stations in place when ownership, loaded state, or claim permission cannot be confirmed. The client warns when it skips a station or cannot finish pickup.

Backfill uses confirmed break receipts and only spare stone or cobblestone after reserving job targets, project goals, maintenance stock, pending recipes, and safety supplies. It does not gather material solely to restore a route. Placement is refused where it could trap a player, item drop, fluid, station, or active route.

## Settings and views

The settings GUI exposes 23 advanced navigation options through a settings lease limited to the current session. Right Shift and `!lk config` open the editor. It suspends automation while open and saves or discards a draft. The `Protected plots` button opens the claim editor.

Claim commands run on the client. `!lk claim pos1` and `!lk claim pos2` record the targeted block. They use the player block when no block is targeted. `!lk claim add <name> [preferred]` saves the 3D box.

`!lk claim list` lists claims. `!lk claim remove <name>` removes a claim. `!lk claim prefer <name> <true|false>` sets station preference. `!lk claim clear` clears the pending corner selection.

Route, action, claim, station, and restoration views use bounded snapshots. The view reports actual movement kinds and confirmed next actions. It does not infer a parkour arc from waypoint height.

## Current evidence

The current 26.3 candidate build completed one native iron run in 119 seconds. A station check after the snapshot fix recovered both bot-placed stations. This is selected iron task evidence, not a fresh world full loadout result.

The 1.20.1 navigation kernel compiles. Its adapter still needs a fix for the host preview API, so no exact Lodekeeper artifact result is claimed for that profile.

The current primary version build and native gates remain open. CI across all 24 profiles and fresh world full loadout runs remain pending. Preview 11 remains unreleased. Preview 10 is still available from its [release page](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.10).

## Remaining gates

Compile each exact profile before declaring its Minecraft version supported. Run the native claim, torch support, station preference, station recovery, backfill, settings, and overlay checks against the current artifact. Then pass CI across the full profile matrix and run fresh world full loadout checks on the primary versions.

Review the final artifact recursively. It must keep the LGPL notices, exact source locks, modified corresponding source, and rebuild instructions. Do not call the candidate complete or publish it until those gates pass.

## Upstream sources

- [Baritone runtime](https://github.com/cabaletta/baritone/blob/10e65932e597ee5f11a654030f17798646f563f2/src/main/java/baritone/Baritone.java).
- [Baritone path execution](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/path/PathExecutor.java).
- [Baritone launch hooks](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/launch/resources/mixins.baritone.json).
- [AltoClef defense policy](https://raw.githubusercontent.com/gaucho-matrero/altoclef/main/src/main/java/adris/altoclef/chains/MobDefenseChain.java).
- [AltoClef container routines](https://raw.githubusercontent.com/gaucho-matrero/altoclef/main/src/main/java/adris/altoclef/tasks/container/DoStuffInContainerTask.java).
