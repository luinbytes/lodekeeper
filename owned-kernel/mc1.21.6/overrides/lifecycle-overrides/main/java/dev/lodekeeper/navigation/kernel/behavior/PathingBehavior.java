/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lodekeeper.navigation.kernel.behavior;

import dev.lodekeeper.navigation.kernel.Baritone;
import dev.lodekeeper.navigation.kernel.OwnedKernelRuntime;
import dev.lodekeeper.navigation.kernel.OwnedResultMailbox;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import dev.lodekeeper.navigation.kernel.api.behavior.IPathingBehavior;
import dev.lodekeeper.navigation.kernel.api.event.events.*;
import dev.lodekeeper.navigation.kernel.api.event.events.type.EventState;
import dev.lodekeeper.navigation.kernel.api.pathing.calc.IPath;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalXZ;
import dev.lodekeeper.navigation.kernel.api.process.PathingCommand;
import dev.lodekeeper.navigation.kernel.api.utils.BetterBlockPos;
import dev.lodekeeper.navigation.kernel.api.utils.Helper;
import dev.lodekeeper.navigation.kernel.api.utils.PathCalculationResult;
import dev.lodekeeper.navigation.kernel.api.utils.interfaces.IGoalRenderPos;
import dev.lodekeeper.navigation.kernel.pathing.calc.AStarPathFinder;
import dev.lodekeeper.navigation.kernel.pathing.calc.AbstractNodeCostSearch;
import dev.lodekeeper.navigation.kernel.pathing.movement.CalculationContext;
import dev.lodekeeper.navigation.kernel.pathing.movement.MovementHelper;
import dev.lodekeeper.navigation.kernel.pathing.path.PathExecutor;
import dev.lodekeeper.navigation.kernel.utils.PathRenderer;
import dev.lodekeeper.navigation.kernel.utils.PathingCommandContext;
import dev.lodekeeper.navigation.kernel.utils.pathing.Favoring;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {

    private PathExecutor current;
    private PathExecutor next;

    private Goal goal;
    private CalculationContext context;

    /*eta*/
    private int ticksElapsedSoFar;
    private BetterBlockPos startPosition;

    private boolean safeToCancel;
    private boolean pauseRequestedLastTick;
    private boolean unpausedLastTick;
    private boolean pausedThisTick;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;

    private volatile AbstractNodeCostSearch inProgress;
    private SearchRequest searchRequest;
    private final OwnedResultMailbox<SearchRequest, SearchCompletion> completions = new OwnedResultMailbox<>();

    private static final class SearchRequest {
        final AbstractNodeCostSearch pathfinder;
        final OwnedKernelRuntime.Session session;
        final dev.lodekeeper.navigation.kernel.BoundWorldEditPolicy policy;
        Future<?> future;
        SearchRequest(AbstractNodeCostSearch pathfinder, OwnedKernelRuntime.Session session, dev.lodekeeper.navigation.kernel.BoundWorldEditPolicy policy) {
            this.pathfinder = pathfinder;
            this.session = session;
            this.policy = policy;
        }
        void cancel() {
            pathfinder.cancel();
            if (future != null) { future.cancel(true); }
        }
    }

    private record SearchCompletion(SearchRequest request, PathCalculationResult result,
                                    BlockPos start, Goal goal, boolean talkAboutIt) {}
    private final Object pathCalcLock = new Object();

    private final Object pathPlanLock = new Object();

    private boolean lastAutoJump;
    private boolean autoJumpOwned;

    private BetterBlockPos expectedSegmentStart;

    private final LinkedBlockingQueue<PathEvent> toDispatch = new LinkedBlockingQueue<>();

    public PathingBehavior(Baritone baritone) {
        super(baritone);
    }

    private void queuePathEvent(PathEvent event) {
        toDispatch.add(event);
    }

    private void dispatchEvents() {
        ArrayList<PathEvent> curr = new ArrayList<>();
        toDispatch.drainTo(curr);
        calcFailedLastTick = curr.contains(PathEvent.CALC_FAILED);
        for (PathEvent event : curr) {
            baritone.getGameEventHandler().onPathEvent(event);
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.OUT) {
            expectedSegmentStart = pathStart();
            drainSearchCompletion();
        }
        dispatchEvents();
        if (event.getType() == TickEvent.Type.OUT) {
            forceCancel();
            baritone.getPathingControlManager().cancelEverything();
            return;
        }

        baritone.getPathingControlManager().preTick();
        tickPath();
        ticksElapsedSoFar++;
        dispatchEvents();
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        if (isPathing()) {
            event.setState(current.isSprinting());
        }
    }

    private void tickPath() {
        pausedThisTick = false;
        if (pauseRequestedLastTick && safeToCancel) {
            pauseRequestedLastTick = false;
            if (unpausedLastTick) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
            unpausedLastTick = false;
            pausedThisTick = true;
            return;
        }
        unpausedLastTick = true;
        if (cancelRequested) {
            cancelRequested = false;
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    // we are calculating
                    // are we calculating the right thing though? 🤔
                    BetterBlockPos calcFrom = inProgress.getStart();
                    Optional<IPath> currentBest = inProgress.bestPathSoFar();
                    if ((current == null || !current.getPath().getDest().equals(calcFrom)) // if current ends in inProgress's start, then we're ok
                            && !calcFrom.equals(ctx.playerFeet()) && !calcFrom.equals(expectedSegmentStart) // if current starts in our playerFeet or pathStart, then we're ok
                            && (!currentBest.isPresent() || (!currentBest.get().positions().contains(ctx.playerFeet()) && !currentBest.get().positions().contains(expectedSegmentStart))) // if
                    ) {
                        // when it was *just* started, currentBest will be empty so we need to also check calcFrom since that's always present
                        inProgress.cancel(); // cancellation doesn't dispatch any events
                    }
                }
            }
            if (current == null) {
                return;
            }
            safeToCancel = current.onTick();
            if (current.failed() || current.finished()) {
                current = null;
                if (goal == null || goal.isInGoal(ctx.playerFeet())) {
                    logDebug("All done. At " + goal);
                    queuePathEvent(PathEvent.AT_GOAL);
                    next = null;
                    if (Baritone.settings().disconnectOnArrival.value) {
                        if (ctx.world() instanceof ClientLevel clientLevel) {
                            clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                        }
                    }
                    return;
                }
                if (next != null && !next.getPath().positions().contains(ctx.playerFeet()) && !next.getPath().positions().contains(expectedSegmentStart)) { // can contain either one
                    // if the current path failed, we may not actually be on the next one, so make sure
                    logDebug("Discarding next path as it does not contain current position");
                    // for example if we had a nicely planned ahead path that starts where current ends
                    // that's all fine and good
                    // but if we fail in the middle of current
                    // we're nowhere close to our planned ahead path
                    // so need to discard it sadly.
                    queuePathEvent(PathEvent.DISCARD_NEXT);
                    next = null;
                }
                if (next != null) {
                    logDebug("Continuing on to planned next path");
                    queuePathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
                    current = next;
                    next = null;
                    current.onTick(); // don't waste a tick doing nothing, get started right away
                    return;
                }
                // at this point, current just ended, but we aren't in the goal and have no plan for the future
                synchronized (pathCalcLock) {
                    if (inProgress != null) {
                        queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
                        return;
                    }
                    // we aren't calculating
                    queuePathEvent(PathEvent.CALC_STARTED);
                    findPathInNewThread(expectedSegmentStart, true, context);
                }
                return;
            }
            // at this point, we know current is in progress
            if (safeToCancel && next != null && next.snipsnapifpossible()) {
                // a movement just ended; jump directly onto the next path
                logDebug("Splicing into planned next path early...");
                queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
                current = next;
                next = null;
                current.onTick();
                return;
            }
            if (Baritone.settings().splicePath.value) {
                current = current.trySplice(next);
            }
            if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
                next = null;
            }
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    // if we aren't calculating right now
                    return;
                }
                if (next != null) {
                    // and we have no plan for what to do next
                    return;
                }
                if (goal == null || goal.isInGoal(current.getPath().getDest())) {
                    // and this path doesn't get us all the way there
                    return;
                }
                if (ticksRemainingInSegment(false).get() < Baritone.settings().planningTickLookahead.value) {
                    // and this path has 7.5 seconds or less left
                    // don't include the current movement so a very long last movement (e.g. descend) doesn't trip it up
                    // if we actually included current, it wouldn't start planning ahead until the last movement was done, if the last movement took more than 7.5 seconds on its own
                    logDebug("Path almost over. Planning ahead...");
                    queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
                    findPathInNewThread(current.getPath().getDest(), false, context);
                }
            }
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (event.getState() == EventState.PRE && current != null && !autoJumpOwned) {
            lastAutoJump = ctx.minecraft().options.autoJump().get();
            autoJumpOwned = true;
            ctx.minecraft().options.autoJump().set(false);
        } else if (event.getState() == EventState.POST) {
            restoreAutoJump();
        }
    }

    private void restoreAutoJump() {
        if (autoJumpOwned) {
            ctx.minecraft().options.autoJump().set(lastAutoJump);
            autoJumpOwned = false;
        }
    }

    public void secretInternalSetGoal(Goal goal) {
        this.goal = goal;
    }

    public boolean secretInternalSetGoalAndPath(PathingCommand command) {
        secretInternalSetGoal(command.goal);
        if (command instanceof PathingCommandContext) {
            context = ((PathingCommandContext) command).desiredCalcContext;
        } else {
            context = new CalculationContext(baritone, true);
        }
        if (goal == null) {
            return false;
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            return false;
        }
        synchronized (pathPlanLock) {
            if (current != null) {
                return false;
            }
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    return false;
                }
                queuePathEvent(PathEvent.CALC_STARTED);
                findPathInNewThread(expectedSegmentStart, true, context);
                return true;
            }
        }
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public boolean isPathing() {
        return hasPath() && !pausedThisTick;
    }

    @Override
    public PathExecutor getCurrent() {
        return current;
    }

    @Override
    public PathExecutor getNext() {
        return next;
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.ofNullable(inProgress);
    }

    public boolean isSafeToCancel() {
        if (current == null) {
            return !baritone.getElytraProcess().isActive() || baritone.getElytraProcess().isSafeToCancel();
        }
        return safeToCancel;
    }

    public void requestPause() {
        pauseRequestedLastTick = true;
    }

    public boolean cancelSegmentIfSafe() {
        if (isSafeToCancel()) {
            secretInternalSegmentCancel();
            return true;
        }
        return false;
    }

    @Override
    public boolean cancelEverything() {
        boolean doIt = isSafeToCancel();
        if (doIt) {
            secretInternalSegmentCancel();
        }
        baritone.getPathingControlManager().cancelEverything(); // regardless of if we can stop the current segment, we can still stop the processes
        return doIt;
    }

    public boolean calcFailedLastTick() { // NOT exposed on public api
        return calcFailedLastTick;
    }

    public void softCancelIfSafe() {
        synchronized (pathPlanLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel); // only cancel ours
            if (!isSafeToCancel()) {
                return;
            }
            current = null;
            next = null;
        }
        cancelRequested = true;
        // do everything BUT clear keys
    }

    // just cancel the current path
    public void secretInternalSegmentCancel() {
        queuePathEvent(PathEvent.CANCELED);
        synchronized (pathPlanLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
            if (current != null) {
                current = null;
                next = null;
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
        }
    }

    @Override
    public void forceCancel() {
        baritone.getRuntime().requireMainThread();
        cancelEverything();
        secretInternalSegmentCancel();
        synchronized (pathPlanLock) {
            current = null;
            next = null;
            goal = null;
            context = null;
        }
        restoreAutoJump();
        synchronized (pathCalcLock) {
            if (searchRequest != null) { searchRequest.cancel(); }
            completions.cancel(searchRequest);
            searchRequest = null;
            inProgress = null;
        }
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    public Optional<Double> estimatedTicksToGoal() {
        BetterBlockPos currentPos = ctx.playerFeet();
        if (goal == null || currentPos == null || startPosition == null) {
            return Optional.empty();
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            resetEstimatedTicksToGoal();
            return Optional.of(0.0);
        }
        if (ticksElapsedSoFar == 0) {
            return Optional.empty();
        }
        double current = goal.heuristic(currentPos.x, currentPos.y, currentPos.z);
        double start = goal.heuristic(startPosition.x, startPosition.y, startPosition.z);
        if (current == start) {// can't check above because current and start can be equal even if currentPos and startPosition are not
            return Optional.empty();
        }
        double eta = Math.abs(current - goal.heuristic()) * ticksElapsedSoFar / Math.abs(start - current);
        return Optional.of(eta);
    }

    private void resetEstimatedTicksToGoal() {
        resetEstimatedTicksToGoal(expectedSegmentStart);
    }

    private void resetEstimatedTicksToGoal(BlockPos start) {
        resetEstimatedTicksToGoal(new BetterBlockPos(start));
    }

    private void resetEstimatedTicksToGoal(BetterBlockPos start) {
        ticksElapsedSoFar = 0;
        startPosition = start;
    }

    /**
     * See issue #209
     *
     * @return The starting {@link BlockPos} for a new path
     */
    public BetterBlockPos pathStart() { // TODO move to a helper or util class
        BetterBlockPos feet = ctx.playerFeet();
        if (!MovementHelper.canWalkOn(ctx, feet.below())) {
            if (ctx.player().onGround()) {
                double playerX = ctx.player().position().x;
                double playerZ = ctx.player().position().z;
                ArrayList<BetterBlockPos> closest = new ArrayList<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
                    }
                }
                closest.sort(Comparator.comparingDouble(pos -> ((pos.x + 0.5D) - playerX) * ((pos.x + 0.5D) - playerX) + ((pos.z + 0.5D) - playerZ) * ((pos.z + 0.5D) - playerZ)));
                for (int i = 0; i < 4; i++) {
                    BetterBlockPos possibleSupport = closest.get(i);
                    double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
                    double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);
                    if (xDist > 0.8 && zDist > 0.8) {
                        // can't possibly be sneaking off of this one, we're too far away
                        continue;
                    }
                    if (MovementHelper.canWalkOn(ctx, possibleSupport.below()) && MovementHelper.canWalkThrough(ctx, possibleSupport) && MovementHelper.canWalkThrough(ctx, possibleSupport.above())) {
                        // this is plausible
                        //logDebug("Faking path start assuming player is standing off the edge of a block");
                        return possibleSupport;
                    }
                }

            } else {
                // !onGround
                // we're in the middle of a jump
                if (MovementHelper.canWalkOn(ctx, feet.below().below())) {
                    //logDebug("Faking path start assuming player is midair and falling");
                    return feet.below();
                }
            }
        }
        return feet;
    }

    /**
     * In a new thread, pathfind to target blockpos
     *
     * @param start
     * @param talkAboutIt
     */
    private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt, CalculationContext context) {
        // this must be called with synchronization on pathCalcLock!
        // actually, we can check this, muahaha
        if (!Thread.holdsLock(pathCalcLock)) {
            throw new IllegalStateException("Must be called with synchronization on pathCalcLock");
            // why do it this way? it's already indented so much that putting the whole thing in a synchronized(pathCalcLock) was just too much lol
        }
        if (inProgress != null) {
            throw new IllegalStateException("Already doing it"); // should have been checked by caller
        }
        if (!context.safeForThreadedUse) {
            throw new IllegalStateException("Improper context thread safety level");
        }
        if (!context.safeAutomation || !context.policy.current() || !context.snapshot.hasLiveChunk(start.getX(), start.getZ())) { return; }
        Goal goal = this.goal;
        if (goal == null) {
            logDebug("no goal"); // TODO should this be an exception too? definitely should be checked by caller
            return;
        }
        long primaryTimeout;
        long failureTimeout;
        if (current == null) {
            primaryTimeout = Baritone.settings().primaryTimeoutMS.value;
            failureTimeout = Baritone.settings().failureTimeoutMS.value;
        } else {
            primaryTimeout = Baritone.settings().planAheadPrimaryTimeoutMS.value;
            failureTimeout = Baritone.settings().planAheadFailureTimeoutMS.value;
        }
        AbstractNodeCostSearch pathfinder = createPathfinder(start, goal, current == null ? null : current.getPath(), context);
        if (!Objects.equals(pathfinder.getGoal(), goal)) { // will return the exact same object if simplification didn't happen
            logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
        }
        OwnedKernelRuntime owner = baritone.getRuntime();
        OwnedKernelRuntime.Session session = owner.captureSession();
        if (!owner.isCurrent(session)) { return; }
        SearchRequest request = new SearchRequest(pathfinder, session, context.policy);
        searchRequest = request;
        completions.begin(request);
        inProgress = pathfinder;
        try {
            request.future = owner.submitSearch(session, () -> {
                PathCalculationResult result;
                try {
                    result = pathfinder.calculate(primaryTimeout, failureTimeout);
                } catch (Throwable failure) {
                    result = new PathCalculationResult(PathCalculationResult.Type.EXCEPTION);
                }
                synchronized (pathCalcLock) {
                    if (searchRequest == request && inProgress == pathfinder && owner.isCurrent(session) && context.policy.current()) {
                        completions.complete(request, new SearchCompletion(request, result, start, goal, talkAboutIt));
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            request.cancel();
            if (searchRequest == request) {
                completions.cancel(request);
                searchRequest = null;
                inProgress = null;
                queuePathEvent(PathEvent.CALC_FAILED);
            }
        }
    }

    private void drainSearchCompletion() {
        baritone.getRuntime().requireMainThread();
        SearchCompletion ready;
        synchronized (pathCalcLock) {
            ready = completions.take(searchRequest);
            if (ready == null || searchRequest != ready.request() || inProgress != ready.request().pathfinder) { return; }
            searchRequest = null;
            inProgress = null;
        }
        if (!baritone.getRuntime().isCurrent(ready.request().session)
                || !ready.request().policy.current()
                || ready.request().pathfinder.isCancellationRequested()
                || !Objects.equals(this.goal, ready.goal())) { return; }
        PathCalculationResult calcResult = ready.result();
        BlockPos start = ready.start();
        Goal goal = ready.goal();
        boolean talkAboutIt = ready.talkAboutIt();
        synchronized (pathPlanLock) {
            Optional<PathExecutor> executor = calcResult.getPath().map(p -> new PathExecutor(PathingBehavior.this, p));
            if (current == null) {
                if (executor.isPresent()) {
                    if (executor.get().getPath().positions().contains(expectedSegmentStart)) {
                        queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                        current = executor.get();
                        resetEstimatedTicksToGoal(start);
                    } else {
                        logDebug("Warning: discarding orphan path segment with incorrect start");
                    }
                } else {
                    if (calcResult.getType() != PathCalculationResult.Type.CANCELLATION && calcResult.getType() != PathCalculationResult.Type.EXCEPTION) {
                        // don't dispatch CALC_FAILED on cancellation
                        queuePathEvent(PathEvent.CALC_FAILED);
                    }
                }
            } else {
                if (next == null) {
                    if (executor.isPresent()) {
                        if (executor.get().getPath().getSrc().equals(current.getPath().getDest())) {
                            queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                            next = executor.get();
                        } else {
                            logDebug("Warning: discarding orphan next segment with incorrect start");
                        }
                    } else {
                        queuePathEvent(PathEvent.NEXT_CALC_FAILED);
                    }
                } else {
                    //throw new IllegalStateException("I have no idea what to do with this path");
                    // no point in throwing an exception here, and it gets it stuck with inProgress being not null
                    logDirect("Warning: PathingBehavior illegal state! Discarding invalid path!");
                }
            }
            if (talkAboutIt && current != null && current.getPath() != null) {
                if (goal.isInGoal(current.getPath().getDest())) {
                    logDebug("Finished finding a path from " + start + " to " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                } else {
                    logDebug("Found path segment from " + start + " towards " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                }
            }
        }
    }


    private AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
        Goal transformed = goal;
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos) {
            BlockPos pos = ((IGoalRenderPos) goal).getGoalPos();
            if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                transformed = new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
        BetterBlockPos feet = ctx.playerFeet();
        var realStart = new BetterBlockPos(start);
        var sub = feet.subtract(realStart);
        if (feet.getY() == realStart.getY() && Math.abs(sub.getX()) <= 1 && Math.abs(sub.getZ()) <= 1) {
            realStart = feet;
        }
        return new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), transformed, favoring, context);

    }

    @Override
    public void onRenderPass(RenderEvent event) {
        PathRenderer.render(event, this);
    }
}
