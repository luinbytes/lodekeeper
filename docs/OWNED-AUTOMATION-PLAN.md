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

Shield use follows `autoDefend`. When `autoDefend` is enabled, `autoUseShield` defaults to `true`. `autoCraftShield` defaults to `false` and requires `autoUseShield`. Shield crafting keeps planned recipe inputs, project goals, and other reservations before spending stock. `shieldIronReserve` defaults to 2 and `shieldPlankReserve` defaults to 16. The GUI labels are `Iron ingots to keep` and `Planks to keep`. Defense preserves non-shield offhand items and uses a plain shield only with more than 100 durability remaining. A creeper always triggers immediate escape without waiting for inventory or equipment changes or switching to melee.

Claim commands run on the client. `!lk claim pos1` and `!lk claim pos2` record the targeted block. They use the player block when no block is targeted. `!lk claim add <name> [preferred]` saves the 3D box.

`!lk claim list` lists claims. `!lk claim remove <name>` removes a claim. `!lk claim prefer <name> <true|false>` sets station preference. `!lk claim clear` clears the pending corner selection.

Route, action, claim, station, and restoration views use bounded snapshots. The view reports actual movement kinds and confirmed next actions. It does not infer a parkour arc from waypoint height.

## Current evidence

The earlier 26.3 candidate completed a native iron goal in 119 seconds. A station check after the snapshot fix recovered both bot-placed stations. These selected results predate the current inventory and shield changes. They do not establish a full gear loadout in a natural survival world. See the [Preview 11 checkpoint](OWNED-PREVIEW-CHECKPOINT.md) for the current CI and native gates.

Preview 11 remains unreleased. Preview 10 is still available from its [release page](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.10).

## Remaining gates

Compile each exact profile before declaring its Minecraft version supported. Validate the source fixes for 1.21.5 through 1.21.11 and all five 26.x profiles, then pass CI across the full profile matrix. Repeat the primary native checks with the exact final jars, including all nine shield modes and the separate repeated inventory benchmark. Run full gear loadouts in new survival worlds on the primary versions.

Review the final artifact recursively. It must keep the LGPL notices, exact source locks, modified corresponding source, and rebuild instructions. Do not call the candidate complete or publish it until those gates pass.

## Upstream sources

- [Baritone runtime](https://github.com/cabaletta/baritone/blob/10e65932e597ee5f11a654030f17798646f563f2/src/main/java/baritone/Baritone.java).
- [Baritone path execution](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/path/PathExecutor.java).
- [Baritone launch hooks](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/launch/resources/mixins.baritone.json).
- [AltoClef defense policy](https://raw.githubusercontent.com/gaucho-matrero/altoclef/main/src/main/java/adris/altoclef/chains/MobDefenseChain.java).
- [AltoClef container routines](https://raw.githubusercontent.com/gaucho-matrero/altoclef/main/src/main/java/adris/altoclef/tasks/container/DoStuffInContainerTask.java).
