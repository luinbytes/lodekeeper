# Owned automation release plan

The next preview must run as one Lodekeeper mod. Its navigation kernel can retain licensed upstream search and movement code, but Lodekeeper owns its lifecycle, settings, input, protection rules, and shutdown. A separately loaded Baritone mod does not meet this requirement.

## Workflow

- [x] Read the Poteto mode principles.
- [x] Ground the reported failures in the supplied preview.10 jar and log.
- [x] Trace navigation ownership, station provenance, configuration, and rendering.
- [x] Compare source-maintained and transformed-kernel designs.
- [ ] Implement and verify the owned kernel before extending its world policies.
- [ ] Add shared settings and claim models with native enforcement.
- [ ] Add provenance-based station recovery and surplus-only backfill.
- [ ] Add the click GUI and bounded movement/action previews.
- [ ] Review material changes independently and fix accepted findings.
- [ ] Verify primary native artifacts and all 24 compiled profiles.
- [ ] Publish the verified release and send the requested ntfy notification.

## Navigation decision

Fourteen pinned upstream releases cover the 24 exact game profiles. Existing artifacts embed the upstream Fabric mod, whose Minecraft mixin initializes a global provider and an example command controller. Removing its manifest alone would also remove required input, player, world, and tick hooks.

The preferred target is a source-maintained internal kernel. Generate version-matched source from pinned archives, apply reviewed source ports, compile against the matching game mappings, and merge the kernel into the Lodekeeper artifact. Lodekeeper supplies its runtime, bounded executor, configuration, and world-edit policy. Retain upstream asynchronous current/next path segments, mining discovery, pickup, and parkour movements. Remove independent chat commands, settings-file reads, and unused native elytra support.

A deterministic transformed binary can preserve already compiled movement code during migration. It is acceptable only with owned lifecycle and source-maintained replacements, exact corresponding source, and native verification. Package relocation alone is insufficient.

The source-maintained alternative costs more version-adapter work but permits direct review and future improvements. The transformed alternative reduces mapping churn but introduces opaque private-class and refmap patching. Prototype the source build first. Change that choice only on recorded build evidence.

## Shared data

`SettingSpec` describes each readable option, its bounds, default, category, and help. The same registry drives command editing, GUI editing, persistence, and reference documentation. Remove or migrate old options that do not control the active engine.

`WorldScope`, `ClaimBox`, and an immutable claim snapshot describe world identity, dimension, and inclusive three-dimensional bounds. Claims permit travel and station interaction. Every automated break and placement must pass the shared policy, including route construction, mining, station recovery, emergency actions, and restoration. Recheck the actual destination before sending a native mutation.

`StationRecord` separates discovered stations from confirmed bot placements. A successful job enters bounded cleanup before completion. Recover only unchanged bot-created stations outside claims, after draining station contents and owned inventory transactions. Prefer usable stations in preferred claims within loaded render distance. A discovered station is never bot property.

`WorldMutationReceipt` records confirmed bot breaks. Restoration uses matching spare blocks after reserving job targets, project goals, maintenance stock, pending recipes, and safety supplies. Stone and cobblestone form the requested equivalence. Missing surplus leaves restoration deferred. Never gather solely to finance backfill or place where a player, drop, fluid, station, or active route would be trapped.

`AutomationScene` contains bounded snapshots of actual movement kinds, route segments, next break/place actions, claims, stations, and restoration progress. Renderers do not query the world. Curved parkour previews require a native parkour movement, rather than a guess from waypoint height.

## Verification gates

Each implementation unit ends with relevant checks before the next dependent unit starts. One JVM and one Gradle worker run locally. Disposable clients use render distance at most eight. Never restart an application used by Lu.

Inspect every final artifact recursively. It must contain no separate `baritone` mod, old provider bootstrap, example command controller, or independent upstream settings-file initialization. Preserve licenses, input hashes, modified source, and rebuilding instructions.

Native fixtures must prove contact combat, manual input priority, claim protection at all six faces, preferred-station use, table and furnace recovery, surplus conservation, and GUI save/reload behavior. Existing collateral and creeper-retreat fixtures remain required. Test the release bytes with the separate verification module, not development production classes.

Run fresh survival `!lk project gear_diamond` on both primary versions. Preserve timing, initial grants, minimum health, deaths, actual inventory, armor, cursor, and final owned-runtime state. One seed cannot establish universal completion time or complete support for every Minecraft mechanic.

The completion predicate is all requested units implemented, reviewed, artifact-verified, and available in the next public tag. The ntfy notification is sent only after that predicate passes. Lu's acceptance remains separate.

## Current evidence

Preview.10 contains `META-INF/jars/baritone-api-fabric-1.11.3.jar`. Its supplied log ends with zero attacks, two retreats, no remaining dry retreat stance, and death. The ongoing contact fix killed both live contact zombies without hurting the cow on 1.21.1 and 26.3. Those controlled passes do not yet cover fresh-world weapon preparation.

The older verification event failure is resolved by a selected adapter. Commit `0ab5415` passed CI run `37516029585` on all 24 profiles. The 1.21.1 contact verifier retains its server damage event.

### Settings, claims, and combat preparation foundation

The foundation unit at `6171581` passed the 1.21.1 and 26.3 production and verifier builds, 117 core tests, and 91 navigation tests. It adds 48 shared setting descriptors, isolated GUI drafts with save rollback, bounded claim storage, and optional cheap stone-sword preparation before ore gathering. Claim enforcement is still pending. Claim storage refuses replacement when loading has failed.

The config size review found that a legal navigation map could save beyond the old 64 KiB read limit. Read and write limits now agree at 256 KiB, and saving validates serialized UTF-8 bytes before replacement. Both primary native clients passed a 128-entry, 68,393-byte config round trip with block breaking disabled and every other setting preserved.

The manual defense takeover verifier captures the exact pending request, observes physical-key preservation before clearing the introduced key, and then requires a later server request and server tick for conservation checks. It checks every native process and forced-input key. Its first native run exposed `tick()` overwriting the pause reason while a cancelled jump settled. Commit `0c4b67c` fixes that overwrite without changing the verifier. The exact committed jars passed on both primary clients with original input restored, request retained, inventory conserved, empty cursor, unchanged shell and cow, and no deaths. These fixtures verify takeover rather than combat victory or bucket completion. The [baseline](evidence/survival-safety/preview11-manual-pause-baseline-1211/assessment.json), [1.21.1 pass](evidence/survival-safety/preview11-manual-takeover-1211/assessment.json), and [26.3 pass](evidence/survival-safety/preview11-manual-takeover-263/assessment.json) include inspected screenshots and source hashes.

The settings screens now support categories, search, draft edits, reset, save, discard, and bounded navigation preferences. Right Shift or `!lk config` opens the editor. Settings screens suspend automation even when general screen pausing is disabled. At `ec7b948`, both exact candidate jars passed eight native GUI checks. The verifier submits the config command through chat, dispatches native widget input, reloads saved values, and restores all 48 original settings. The [1.21.1 receipt](evidence/settings-ui/preview11-native-1211/assessment.json) and [26.3 receipt](evidence/settings-ui/preview11-native-263/assessment.json) preserve inspected screenshots and separate production and verifier hashes. Navigation preference runtime bindings and ordinary launcher acceptance remain pending.

The retained GUI baselines exposed verifier errors when logging a closed screen and using obsolete GLFW input codes on the SDL-based 26.3 client. The [closed-screen baseline](evidence/settings-ui/preview11-baseline-1211/assessment.json), [confirmation-key baseline](evidence/settings-ui/preview11-native-key-before-263/assessment.json), [mouse-button baseline](evidence/settings-ui/preview11-native-mouse-before-263/assessment.json), and [shortcut-key baseline](evidence/settings-ui/preview11-native-shortcut-before-263/assessment.json) record those failures. Native input helpers now select the correct profile events. CI run `37531786927` at `dea840e` passed all 24 adapter checks after removing a duplicate verifier helper on 1.21.9 and 1.21.10. The later shortcut-helper commit has its own pending CI run.

The new station recovery action verifies an exact station handler, a full server contents receipt, empty station slots and cursor, inventory capacity, and the current protection predicate before mining. Pickup requires inventory and entity receipts, including collection before the entity becomes visible. It is not yet wired to placement provenance or successful job cleanup.

The station ledger accepts one exclusive placement intent at a time. Ownership requires later server block and inventory-decrement receipts for the exact session, job, item, and position. Refunds, mismatched blocks, stale receipts, session changes, and capacity limits reject or remove ownership. Nine focused ledger regressions pass. Claim loading now rejects missing fields, coerced types, fractional or overflowing coordinates, oversized claim lists, and malformed UTF-8 world identities. The paired adapters pass three decoding regressions each. Both primary builds pass with 126 core and 91 navigation tests. Independent review found no serious issues in these models; authoritative native receipt hooks and cleanup integration remain pending.

The source-port lifecycle unit compiles and remaps 252 owned Java sources for 1.21.1. Its corrected artifact is 634,858 bytes with SHA-256 `478c8da43482d86125b39e69800a65d7cb89dd65db475d791a476a7e96a59782`. Focused regressions cover cancelled search replacement, mandatory cache flushes, and nonblocking flush admission. Independent review found no remaining serious issues in that unit. Native lifecycle verification and repository integration remain pending.

The following snapshot unit has 258 generated sources. It replaces worker access to live chunk palettes with bounded compressed snapshots, coalesces process scans, and consumes world-edit policies during calculation and execution. Its first corrected source passed focused regressions and remapping. Review then found that removing a lower door half could miss falling blocks above its upper half. The new frozen source checks the complete door or bed removal footprint and each affected gravity column. Independent review found no remaining serious issues. The strengthened stub regressions and remapping pass against source archive SHA-256 `bd326fd54ad07cb18f53195985fe22a228cbfef28ec2d0d1ef2d225998a5eeba`. The resulting internal prototype jar has SHA-256 `1b3bf05f287f35a7412534525893a79d2f71de7def2a32a1b72e5196f9c2ae0d`. Palette-copy timing, native world invalidation, placement checks, and repository integration remain pending. These prototype results do not establish an independent release.

## Sources

- [Baritone runtime](https://github.com/cabaletta/baritone/blob/10e65932e597ee5f11a654030f17798646f563f2/src/main/java/baritone/Baritone.java).
- [Baritone path execution](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/path/PathExecutor.java).
- [Baritone launch hooks](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/launch/resources/mixins.baritone.json).
- [AltoClef defense policy](https://raw.githubusercontent.com/gaucho-matrero/altoclef/main/src/main/java/adris/altoclef/chains/MobDefenseChain.java).
- [AltoClef container routines](https://raw.githubusercontent.com/gaucho-matrero/altoclef/main/src/main/java/adris/altoclef/tasks/container/DoStuffInContainerTask.java).
