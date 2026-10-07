# Preview 11 development checkpoint

Preview 11 is an unreleased development candidate. Preview 10 remains downloadable. All 24 exact profiles compile at `ce3c967` in [CI run 37609503491](https://github.com/luinbytes/lodekeeper/actions/runs/37609503491). All eighteen shield cases pass across the two primary versions. The remaining release gates are below.

## Source state

The owned navigation kernel is flattened into Lodekeeper under `dev.lodekeeper.navigation.kernel`. The source map routes 24 exact Minecraft profiles through 14 owned families. It preserves upstream LGPL notices, source locks, exact archive hashes, and corresponding source access. It loads no separate Baritone runtime. Core and navigation target Java 17; each Fabric adapter targets its exact game APIs.

The settings GUI exposes 23 advanced navigation options and 3D protected claims. Claims prefer usable loaded stations. Bot-owned station pickup checks the exact item UUID and server receipts. Backfill spends only surplus stone or cobblestone. Unsupported mechanics report a blocker; missing world or claim state pauses automated block edits.

With `autoDefend` enabled, `autoUseShield` defaults to `true`. `autoCraftShield` defaults to `false` and requires shield use. The configurable keep floors default to two iron ingots and sixteen planks. These amounts remain after reserving active goals, queued goals, non-aborted project targets, and maintained stock. The GUI labels are `Iron ingots to keep` and `Planks to keep`.

Optional shield work uses one joint plan for those targets. Changed work invalidates the optional plan; an owned inventory transfer finishes and drains before replanning. Shield use preserves non-shield offhand items and requires more than 100 durability remaining. A creeper triggers immediate escape without waiting for inventory changes or trying melee.

The remote branch audit recovered the finite-stock planner foundation from `storage-stock`, source `b8b5135`. Finite observed stock, shared debits, bounded partial withdrawals, and stored-tool durability handling are preserved alongside newer fuel selection and claim checks. The [original storage receipt](evidence/planning/storage-foundation-2026-10-06.json) remains historical evidence. Container registration, storage commands, and native container transfers are unsupported.

## Selected evidence

The exact CI production jars match the native gameplay inputs byte for byte:

| Minecraft | Production jar SHA-256 |
| --- | --- |
| 1.21.1 | `663c191cbcdf4ab547cfe72b8653e3b141306075f3e3a3ed3071ec464cd2618f` |
| 26.3 | `afb2e613d63e08dc68492e95fe407c3d060c03c46645f8680f44db244fbedb4e` |

The production source is `ce3c967b567ec1d7592e11a9f67e318cbcfa52c4`. Later runner and capture-helper changes preserve these jar bytes. Local checks pass 159 core, 95 navigation, 13 primary adapter, and three modern adapter cases. All 24 exact CI artifacts pass packaging, namespace, class-version, source-pin, licence, and verifier-exclusion checks. Runtime coverage remains separate.

| Current gate | Result |
| --- | --- |
| Exact adapter and verifier compilation | All 24 profiles pass |
| Shield modes | 18/18 pass, nine on each primary version |
| Inventory progression warmup | Current 1.21.1 jar passes 9/9; profiled and uncounted |
| Repeated inventory comparison | Historical A/B sequence complete: five progression passes each, one additional A timeout and one B cleanup failure; current-build counted trials pending |
| GUI and settings | Current ten-step GUI passes on both primary versions with saved shield thresholds; 37-value native lease checks pending |
| Claims, backfill, stations and threats | Current 1.21.1 live-creeper retreat fails after two routes; other fresh safety runs pending |
| Fresh natural full diamond gear | Pending on both primary versions; latest earlier attempts failed |
| Original gameplay media | Fresh 26.3 queued and worn shield PNG captures pass; raw videos pending |

The queued shield case reserves seven iron before adding a keep floor of four. Twelve starting ingots leave eleven after the shield and six after the bucket and shears. Four reserved planks plus a keep floor of nine leave thirteen after shield crafting. The worn case records native blocked damage, minimum health twenty, and shield restoration. Occupied offhand stock is preserved. Manual takeover uses simulated native key-state input, not physical keyboard input.

The inventory warmup passes all nine controlled cases, including custom crafting and automatic eating. Its profiling samples include navigation and engine work; the receipt exposes no per-click latency. The historical A/B sequence records five progression passes each across eleven attempts. A's additional wooden-pickaxe timeout remains a failure. One B progression pass leaves an owned table pickup incomplete, and one A pass includes a temporary pickup timeout followed by recovery. B's source commit is unknown; its jar hash identifies that historical candidate. Current-build counted trials remain pending, so these results do not establish the released build's speedup.

The current GUI passes all ten checks on each primary version, including shield controls, saved thresholds of nineteen iron and thirty-seven planks, dependent-control disabling, discard, protected plots and restoration of original settings. The current primary creeper fixture fails after two retreats. The player remains at full health, the same creeper remains alive with its fuse stopped, and weapons are untouched; clearance is still below twelve blocks. This failure blocks release.

The retreat continuation change at `314ffd0` compiles on both primary versions and passes the existing local checks, but the primary native retry still fails with about nine blocks of clearance. Position logs at `13cbcae` show that the client reaches its goal blocks; pursuing threats consume the snapshot clearance. The sixteen-block destination change at `9fe705c` reaches twelve blocks of live clearance, then resumes bucket crafting too close to the pursuer. That native attempt fails with an exploded creeper and minimum player health of 0.92. The next correction requires sixteen blocks of live clearance before handoff and plans twenty-block destinations. Fresh verification remains pending. The passing shield and GUI results above describe `ce3c967`.

## Build and native history

Earlier receipts and original screenshots remain distinct from current acceptance.

| Stage | Recorded outcome |
| --- | --- |
| `c7c885f2` matrix | [12 builds passed and 12 failed](https://github.com/luinbytes/lodekeeper/actions/runs/37597621889); later exact builds resolve these failures |
| `42fee35` matrix | [26.3 caught an incorrect verifier helper name](https://github.com/luinbytes/lodekeeper/actions/runs/37601999414); corrected before the current matrix |
| `8d7bb68` matrix | [All 24 passed](https://github.com/luinbytes/lodekeeper/actions/runs/37603417871); includes recovered finite stock but predates the queued shield fix |
| First shield attempt | Metadata validation failed before world creation; the first recorder also failed before capture |
| `bc496e4` spare attempt | Native shield and bucket counts were correct, but aggregate validation expected nine cases and failed; retained as a failed attempt |
| `5df577f` and `432c344` shield round | Three primary modes passed; queued mode failed because the plan reserved five iron instead of seven |
| Queued-goal fix | Independent review and local checks passed; `ce3c967` then passed all 24 exact builds |
| First `ce3c967` primary round | Seven runner/native passes; occupied mode passed natively but failed the class-origin parser when JVM log decorators changed |
| `fbd5261` continuation | A fresh occupied retry and remaining modes pass, completing 18/18. Parser replay verifies exact origins and rejects a different production jar |

The earlier `8d7bb68` primary hashes are historical: 1.21.1 `e7158a2c1cab3c9bda750f82c4f17f44a924276f081c24c86b8f08e13cebfd1b`, and 26.3 `b45ff0961188292a86b82223c6cc0e1d3058d5214b1220eca0695a13863a706a`. Earlier progression, claims, station recovery, and nine-step GUI results do not cover the current production change. An earlier exact 1.20.1 artifact and its verifier compile; current native gameplay on that profile is unverified.

Modern shield completion initially omitted a screenshot request. The capture-only fix compiles on 26.3 without changing production jar bytes. Fresh queued and worn cases now pass with existing original PNGs. The failed original receipts remain unchanged.

## Release status

Preview 11 is not released or ready for download. Preview 10 remains on the [release page](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.10). Its historical full gear timings are 11:59 on 1.21.1 and 9:33 on 26.3; those figures do not describe Preview 11.

Release requires corrected live-creeper retreat, the remaining settings, safety, inventory comparison, and fresh full gear gates; final exact-source CI and packaging; and labelled original screenshots and raw videos. Uploaded media must pass remote size and SHA-256 read-back before its local copies are removed. Compiled coverage, runtime verification, and Lu's acceptance remain separate.
