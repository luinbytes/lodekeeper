# mc1.21.2 Baritone source drift

Minecraft mapping: 1.21.2, 1.21.3
Baritone: 1.12.0 at `deae0f3094b145f2afd55ff4e4b573993ae2e5bb` with archive SHA-256 `2ec85054d0663869e3e44e583c4351bf8fb30c75b1c0f5897fde9cbfc2fb72ef`.

Untouched override source files (28):
- `api/java/dev/lodekeeper/navigation/kernel/api/pathing/calc/IPathFinder.java`
- `api/java/dev/lodekeeper/navigation/kernel/api/utils/BlockOptionalMetaLookup.java`
- `launch/java/dev/lodekeeper/navigation/kernel/launch/mixins/MixinClientPlayNetHandler.java`
- `launch/java/dev/lodekeeper/navigation/kernel/launch/mixins/MixinMinecraft.java`
- `launch/java/dev/lodekeeper/navigation/kernel/launch/mixins/MixinNetworkManager.java`
- `main/java/dev/lodekeeper/navigation/kernel/Baritone.java`
- `main/java/dev/lodekeeper/navigation/kernel/behavior/InventoryBehavior.java`
- `main/java/dev/lodekeeper/navigation/kernel/behavior/PathingBehavior.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/CachedChunk.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/CachedRegion.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/CachedWorld.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/WorldData.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/WorldProvider.java`
- `main/java/dev/lodekeeper/navigation/kernel/event/GameEventHandler.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/calc/AStarPathFinder.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/calc/AbstractNodeCostSearch.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/calc/Path.java`
- `main/java/dev/lodekeeper/navigation/kernel/pathing/movement/CalculationContext.java`
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

Adapted overrides over upstream drift (8):
- `api/java/dev/lodekeeper/navigation/kernel/api/utils/BlockOptionalMeta.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/ChunkPacker.java`
- `main/java/dev/lodekeeper/navigation/kernel/cache/FasterWorldScanner.java`
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
- `PathingBehavior` has no upstream drift from the 1.11.3 source. The owned file retains the bounded search scheduler, completion mailbox, and client-thread completion drain.
- `ChunkPacker` keeps snapshot-only reads. The pinned release changed the live chunk minimum-height accessor, which the snapshot rewrite no longer calls.
- `MovementDescend` and `MovementParkour` use captured snapshot height bounds after the upstream accessor rename.
- `BlockStateInterfaceAccessWrapper` follows the renamed `getMinY` method and returns captured height bounds.
- `FarmProcess` keeps this pin's immature-crop wait behavior and adds the owned mutation checks.
- `ToolSet` keeps captured inventory and settings with the 1.21.2 pin's sword check.

This is source preparation evidence only. The generated sources were not compiled, and no Minecraft runtime support claim is made.

Override manifest: `lifecycle-overrides.sha256`.
