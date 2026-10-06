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

The `a5d3584` CI run compiled 21 profiles. Three older verification adapters failed because their Fabric API has no `AFTER_DAMAGE` event. Their production builds are not the failing step. Add a version-specific verification event adapter without weakening the 1.21.1 contact proof.

## Sources

- [Baritone runtime](https://github.com/cabaletta/baritone/blob/10e65932e597ee5f11a654030f17798646f563f2/src/main/java/baritone/Baritone.java).
- [Baritone path execution](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/main/java/baritone/pathing/path/PathExecutor.java).
- [Baritone launch hooks](https://github.com/cabaletta/baritone/blob/25111daedf1d59e6a8dfb5a3e61885cdb8d953df/src/launch/resources/mixins.baritone.json).
- [AltoClef defense policy](https://raw.githubusercontent.com/gaucho-matrero/altoclef/main/src/main/java/adris/altoclef/chains/MobDefenseChain.java).
- [AltoClef container routines](https://raw.githubusercontent.com/gaucho-matrero/altoclef/main/src/main/java/adris/altoclef/tasks/container/DoStuffInContainerTask.java).
