# mc1.21.4 Baritone source drift

Minecraft mapping: 1.21.4
Baritone: 1.13.1 at `a99e25eb8dc2f5b18229c74ba77ae9e5b682dbb3` with archive SHA-256 `55af700899e145335d1719908c2d6a222db7e00ae49e940ceb53ad4cd4872dcf`.

Untouched override source files (25):
- `api/java/dev/lodekeeper/navigation/kernel/api/pathing/calc/IPathFinder.java`
- `api/java/dev/lodekeeper/navigation/kernel/api/utils/BlockOptionalMetaLookup.java`
- `launch/java/dev/lodekeeper/navigation/kernel/launch/mixins/MixinClientPlayNetHandler.java`
- `launch/java/dev/lodekeeper/navigation/kernel/launch/mixins/MixinMinecraft.java`
- `launch/java/dev/lodekeeper/navigation/kernel/launch/mixins/MixinNetworkManager.java`
- `main/java/dev/lodekeeper/navigation/kernel/Baritone.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/CachedChunk.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/CachedRegion.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/CachedWorld.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/WorldData.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/WorldProvider.java`
- `main/java/dev/lodekeeper/navigation/kernel/event/GameEventHandler.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/calc/AStarPathFinder.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/calc/AbstractNodeCostSearch.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/calc/Path.java`
- `main/java/dev/lodekeeper/navigation/kernel/process/BuilderProcess.java`
- `main/java/dev/lodekeeper/navigation/kernel/process/ExploreProcess.java`
- `main/java/dev/lodekeeper/navigation/kernel/process/GetToBlockProcess.java`
- `main/java/dev/lodekeeper/navigation/kernel/process/MineProcess.java`
- `main/java/dev/lodekeeper/navigation/kernel/utils/BlockBreakHelper.java`
- `main/java/dev/lodekeeper/navigation/kernel/utils/BlockPlaceHelper.java`
- `main/java/dev/lodekeeper/navigation/kernel/utils/BlockStateInterface.java`
- `main/java/dev/lodekeeper/navigation/kernel/utils/InputOverrideHandler.java`
- `main/java/dev/lodekeeper/navigation/kernel/utils/player/BaritonePlayerContext.java`
- `main/java/dev/lodekeeper/navigation/kernel/utils/player/BaritonePlayerController.java`

Adapted overrides over upstream drift (11):
- `api/java/dev/lodekeeper/navigation/kernel/api/utils/BlockOptionalMeta.java`
- `main/java/dev/lodekeeper/navigation/kernel/behavior/InventoryBehavior.java`
- `main/java/dev/lodekeeper/navigation/kernel/behavior/PathingBehavior.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/ChunkPacker.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/FasterWorldScanner.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/movement/CalculationContext.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/movement/movements/MovementDescend.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/movement/movements/MovementParkour.java`
- `main/java/dev/lodekeeper/navigation/kernel/process/FarmProcess.java`
- `main/java/dev/lodekeeper/navigation/kernel/utils/BlockStateInterfaceAccessWrapper.java`
- `main/java/dev/lodekeeper/navigation/kernel/utils/ToolSet.java`

Owned files absent from both upstream source trees (15):
- `api/java/dev/lodekeeper/navigation/kernel/WorldEditPolicy.java`
- `api/java/dev/lodekeeper/navigation/kernel/WorldEditPolicySnapshot.java`
- `api/java/dev/lodekeeper/navigation/kernel/api/AutomationInputBarrier.java`
- `api/java/dev/lodekeeper/navigation/kernel/api/process/OwnedMiningTargets.java`
- `main/java/dev/lodekeeper/navigation/kernel/BoundWorldEditPolicy.java`
- `main/java/dev/lodekeeper/navigation/kernel/OwnedCoalescedJob.java`
- `main/java/dev/lodekeeper/navigation/kernel/OwnedFinalFlush.java`
- `main/java/dev/lodekeeper/navigation/kernel/OwnedKernelRuntime.java`
- `main/java/dev/lodekeeper/navigation/kernel/OwnedMutationGuard.java`
- `main/java/dev/lodekeeper/navigation/kernel/OwnedResultMailbox.java`
- `main/java/dev/lodekeeper/navigation/kernel/OwnedWorkScheduler.java`
- `main/java/dev/lodekeeper/navigation/kernel/process/OwnedMiningAdmission.java`
- `main/java/dev/lodekeeper/navigation/kernel/snapshot/ChunkSnapshot.java`
- `main/java/dev/lodekeeper/navigation/kernel/snapshot/ImmutableWorldView.java`
- `main/java/dev/lodekeeper/navigation/kernel/snapshot/OwnedWorldSnapshots.java`

Port decisions:
- `BlockOptionalMeta` keeps the pinned release's registry-loading API and uses the owned direct executor.
- `PathingBehavior` keeps the bounded search scheduler, completion mailbox, and client-thread completion drain. The only upstream difference was a misspelling in a log message; the owned spelling remains.
- `ChunkPacker` keeps snapshot-only reads and passes world Y to `ChunkSnapshot.get`. The pinned 1.21.4 and 1.21.5 sources pass section-relative Y to a helper that subtracts `minY`; this source-level mismatch is unverified because these groups were not compiled.
- `MovementDescend` and `MovementParkour` use captured snapshot height bounds after the upstream accessor rename.
- `BlockStateInterfaceAccessWrapper` follows the renamed `getMinY` method and returns captured height bounds.
- `FarmProcess` preserves the pinned release removal of the immature-crop wait path and keeps owned mutation checks.
- `ToolSet` keeps captured inventory and settings with the pinned `SwordItem` check.

This is source preparation evidence only. The generated sources were not compiled, and no Minecraft runtime support claim is made.

Override manifest: `lifecycle-overrides.sha256`.
