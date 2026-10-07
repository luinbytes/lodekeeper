# Preview 11 development checkpoint

Preview 11 is an unreleased development candidate. Preview 10 remains downloadable. All 24 exact profiles compile at `ce3c967` in [CI run 37609503491](https://github.com/luinbytes/lodekeeper/actions/runs/37609503491), and all eighteen primary shield cases pass. Fresh GUI, safety, repeated inventory, and natural survival checks still gate release.

## Source state

The owned navigation kernel is flattened into Lodekeeper under `dev.lodekeeper.navigation.kernel`. The source map routes 24 exact Minecraft profiles through 14 owned families. It keeps the upstream LGPL notices, source locks, exact archive hashes, and corresponding source access. It does not load a separate Baritone runtime.

The shared core and navigation modules target Java 17. Each Fabric adapter targets its exact Minecraft and Fabric APIs. Every version still needs a successful exact artifact build before it can be declared supported.

Current source includes 23 advanced navigation options in the settings GUI and 3D protected claims. It prefers usable stations in marked claims and recovers bot-owned stations with bounded pickup that checks item UUIDs. It also has surplus stone and cobblestone backfill, plus bounded route and action views.

Shield use follows `autoDefend`. When `autoDefend` is enabled, `autoUseShield` defaults to `true`. `autoCraftShield` defaults to `false` and requires `autoUseShield`. Shield crafting keeps planned recipe inputs, project goals, and other reservations before it spends stock. `shieldIronReserve` defaults to 2 and `shieldPlankReserve` defaults to 16. The GUI labels are `Iron ingots to keep` and `Planks to keep`. The defense preserves non-shield offhand items, uses a plain shield only with more than 100 durability remaining, and responds to creepers with immediate escape. It does not wait for inventory or equipment changes or finish a creeper with melee.

The remote branch audit found one planner foundation on `storage-stock`, source `b8b5135`, that was absent from this branch. Its finite observed stock, shared stock debits, bounded partial withdrawals, and stored-tool durability handling have been recovered. The newer fuel selection behavior and existing claim regression remain intact. The original storage receipt is [historical evidence](evidence/planning/storage-foundation-2026-10-06.json). This adds no container registration, storage commands, or native transfers.

Unsupported mechanics report a blocker. Missing world or claim state pauses automated block edits.

## Selected evidence

The earlier 26.3 candidate completed one native iron goal in 119 seconds. A station check after the snapshot fix recovered both bot-placed stations. The initial primary native check passed 9/9. A separate native GUI check passed 9/9 across the 1.21.1 and 26.3 adapters. The settings check covered 23 typed preferences and restored all 37 leased values after close. All claim face, torch support, station preference, backfill, and station recovery checks passed before the latest inventory and shield changes. These results do not cover the current candidate or a full gear loadout in a new survival world.

An earlier exact 1.20.1 artifact and its verification sources compiled. Native gameplay on that profile remains unverified.

An earlier CI run passed all 24 profiles at [run 37592201134](https://github.com/luinbytes/lodekeeper/actions/runs/37592201134). The [matrix at `c7c885f2`](https://github.com/luinbytes/lodekeeper/actions/runs/37597621889) then passed 12 profiles and failed 12. Failures covered 1.21.5 through 1.21.11 and all five 26.x profiles. The current matrix below resolves those build failures. The latest two goals in natural survival worlds failed before the current inventory and shield changes. The current source-owned Preview 11 candidate has not completed a full gear loadout in a natural survival world.

The intermediate [matrix at `42fee35`](https://github.com/luinbytes/lodekeeper/actions/runs/37601999414) includes the drag grouping and native fixture sources. Its 26.3 job caught an incorrect modern helper name, now corrected to the native item-stack comparison.

Local builds of the recovered source pass 159 core checks, 95 navigation checks, 13 primary adapter checks, and three modern adapter checks. The 1.21.1 and 26.3 production jars and verifier sources compile. Their native shield, drag, GUI, and survival runs remain pending.

The [matrix at `8d7bb68`](https://github.com/luinbytes/lodekeeper/actions/runs/37603417871) passed all 24 exact profiles and includes the recovered storage foundation. The downloaded primary jars match the local gameplay jars byte for byte. The 1.21.1 jar SHA-256 is `e7158a2c1cab3c9bda750f82c4f17f44a924276f081c24c86b8f08e13cebfd1b`; the 26.3 jar is `b45ff0961188292a86b82223c6cc0e1d3058d5214b1220eca0695a13863a706a`.

The first shield attempt failed before world creation because mode validation rejected the runner's mandatory candidate SHA-256 metadata. Both verifiers allow that identity field at `bc496e4`, and their primary sources compile locally. Native shield behavior and original gameplay recording remain unverified; the first recorder attempt also failed before capture. Its AppKit initialization fix is in the same commit. Neither fix changes production jar bytes.

A later 1.21.1 `spare` attempt at `bc496e4` observed one crafted shield and one bucket, six remaining ingots, thirteen remaining planks, full health, and an empty cursor. The overall run still failed because aggregate validation expected nine cases. Both verifiers now expect one shield case. This failed run and its screenshot are retained; it does not count as suite acceptance.

The first shield rounds below were incomplete. Their failures stay recorded separately from the passing round. Repeat other previous native checks with the exact final jars.

With the verifier corrections at `5df577f`, the 1.21.1 `spare`, `default`, and `off` modes passed their full runner and native receipt gates. The next `queued` run at `432c344` failed. It completed a shield, bucket, and shears with six iron and thirteen planks remaining, but its shield plan reserved only five iron instead of seven. The missing two ingots belong to the queued shears recipe. This exposes a production reservation gap; the assertion remains unchanged. The five other primary modes and all nine modern modes remain unrun in that round. These runs use the byte-identical jars from the `8d7bb68` matrix.

The queued-goal fix now makes one joint plan for active, queued, project, and maintained targets before admitting optional shield work. A changed queue invalidates that work; an owned inventory transfer finishes and drains before replanning. Independent review passed after restoring the existing table-open timeout ahead of the stale-plan guard. Local 1.21.1 and 26.3 builds passed with 159 core, 95 navigation, 13 primary adapter, and 3 modern adapter checks. Fresh native checks and a new 24-profile matrix are still required for this production change. The previous docs-only matrix passed 23 profiles; its 1.21.6 job failed while resolving dependency POM parents.

At `ce3c967`, seven 1.21.1 shield modes passed their runner and native gates. `queued` reserves seven iron and completes with six iron and thirteen planks. `worn` records a blocked native damage event, minimum health twenty, and shield restoration. `occupied` passes its native check but fails the runner because JVM class-load log decoration changes mid-file. The full raw log contains the expected production and verifier classes; replay with the corrected parser validates their exact origins and rejects a different production jar. Keep the failed original receipt and require a fresh native retry. Manual takeover and all modern modes remain unrun in this round.

The fresh `fbd5261` runner completes the remaining modes using byte-identical `ce3c967` CI jars. The combined shield suite passes 18/18. Modern runs initially produced no screenshots because their shield completion path did not request one. The screenshot-only change compiles on 26.3 and leaves the production jar hash unchanged; its native capture check remains pending. A separate, uncounted current inventory warmup passes 9/9 with JFR recording. It does not establish a speed claim.

## Release status

Preview 11 is not released or ready for download. Preview 10 remains available from the [release page](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.10). Its historical full loadout timings are 11:59 on 1.21.1 and 9:33 on 26.3. Those figures do not describe Preview 11.

Release readiness requires a passing current artifact matrix, native repeats for the latest inventory and shield changes, and evidence of a complete gear loadout in a new survival world. Runtime checks and compiled-version support remain separate claims.
