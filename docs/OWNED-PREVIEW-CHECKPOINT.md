# Preview 11 development checkpoint

Preview 11 is an unreleased development candidate. Preview 10 remains downloadable. This checkpoint records source and selected runtime evidence. It does not declare Minecraft version support or release readiness.

## Source state

The owned navigation kernel is flattened into Lodekeeper under `dev.lodekeeper.navigation.kernel`. The source map routes 24 exact Minecraft profiles through 14 owned families. It keeps the upstream LGPL notices, source locks, exact archive hashes, and corresponding source access. It does not load a separate Baritone runtime.

The shared core and navigation modules target Java 17. Each Fabric adapter targets its exact Minecraft and Fabric APIs. Every version still needs a successful exact artifact build before it can be declared supported.

Current source includes 23 advanced navigation options in the settings GUI and 3D protected claims. It prefers usable stations in marked claims and recovers bot-owned stations with bounded pickup that checks item UUIDs. It also has surplus stone and cobblestone backfill, plus bounded route and action views.

Shield use follows `autoDefend`. When `autoDefend` is enabled, `autoUseShield` defaults to `true`. `autoCraftShield` defaults to `false` and requires `autoUseShield`. Shield crafting keeps planned recipe inputs, project goals, and other reservations before it spends stock. `shieldIronReserve` defaults to 2 and `shieldPlankReserve` defaults to 16. The GUI labels are `Iron ingots to keep` and `Planks to keep`. The defense preserves non-shield offhand items, uses a plain shield only with more than 100 durability remaining, and responds to creepers with immediate escape. It does not wait for inventory or equipment changes or finish a creeper with melee.

Unsupported mechanics report a blocker. Missing world or claim state pauses automated block edits.

## Selected evidence

The earlier 26.3 candidate completed one native iron goal in 119 seconds. A station check after the snapshot fix recovered both bot-placed stations. The initial primary native check passed 9/9. A separate native GUI check passed 9/9 across the 1.21.1 and 26.3 adapters. The settings check covered 23 typed preferences and restored all 37 leased values after close. All claim face, torch support, station preference, backfill, and station recovery checks passed before the latest inventory and shield changes. These results do not cover the current candidate or a full gear loadout in a new survival world.

An earlier exact 1.20.1 artifact and its verification sources compiled. Native gameplay on that profile remains unverified.

An earlier CI run passed all 24 profiles at [run 37592201134](https://github.com/luinbytes/lodekeeper/actions/runs/37592201134). The latest completed matrix at [run 37597621889](https://github.com/luinbytes/lodekeeper/actions/runs/37597621889), source `c7c885f2`, passed 12 profiles and failed 12. Failures cover 1.21.5 through 1.21.11 and all five 26.x profiles. The API fixes are in source and await a new matrix. The latest two goals in natural survival worlds failed before the current inventory and shield changes. The current source-owned Preview 11 candidate has not completed a full gear loadout in a natural survival world.

The nine shield modes and the repeated inventory benchmark remain pending. Repeat the previous native checks with the exact final jars. Do not treat the fixture source as gameplay evidence.

## Release status

Preview 11 is not released or ready for download. Preview 10 remains available from the [release page](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.10). Its historical full loadout timings are 11:59 on 1.21.1 and 9:33 on 26.3. Those figures do not describe Preview 11.

Release readiness requires a passing current artifact matrix, native repeats for the latest inventory and shield changes, and evidence of a complete gear loadout in a new survival world. Runtime checks and compiled-version support remain separate claims.
