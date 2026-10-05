package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Event-driven stock targets. This class has no Minecraft or executor dependency; the adapter supplies
 * observed inventory counts and owns the returned task IDs. Calls are intended to be serialized by the adapter.
 */
public final class MaintainedDemandModel {
    public static final int MAX_TARGETS = 32;
    public static final int MAX_ACTIVE_TASKS = 32;
    public static final int MAX_COUNT = 1_000_000;
    private static final int MAX_INVENTORY_ITEMS = 4_096;

    public enum State {
        SATISFIED,
        HOLDING_ABOVE_REFILL_POINT,
        REFILL_DUE,
        QUEUED,
        BLOCKED_UNTIL_INVENTORY_CHANGE
    }

    /** targetCount is the total desired inventory stock; deficitCount is the gap when the request was issued. */
    public record MaintenanceRequest(String taskId, ItemId item, int targetCount, int deficitCount, int observedCount) {
        public MaintenanceRequest {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(item, "item");
            validateCount(targetCount, "target count");
            validateCount(deficitCount, "deficit count");
            validateInventoryCount(observedCount);
            if (observedCount >= targetCount || deficitCount != targetCount - observedCount) {
                throw new IllegalArgumentException("Request counts do not describe a deficit to the target");
            }
        }
    }

    public record Status(ItemId item, int targetCount, int refillBelow, int actualCount,
                         int activeReservedCount, int shortfallCount, int deficitCount,
                         State state, String activeTaskId) {
        public Status {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(state, "state");
            validateCount(targetCount, "target count");
            if (refillBelow < 0 || refillBelow >= targetCount) {
                throw new IllegalArgumentException("Refill threshold must be between 0 and target count - 1");
            }
            validateInventoryCount(actualCount);
            validateInventoryCount(activeReservedCount);
            if (shortfallCount != Math.max(0, targetCount - actualCount)
                    || deficitCount != Math.max(0, shortfallCount - activeReservedCount)) {
                throw new IllegalArgumentException("Maintenance status counts are inconsistent");
            }
            if ((activeTaskId == null) != (activeReservedCount == 0)) {
                throw new IllegalArgumentException("Task id and active reservation must appear together");
            }
        }
    }

    private record Target(int count, int refillBelow) { }

    private final Map<ItemId, Target> targets = new TreeMap<>();
    private final Map<String, MaintenanceRequest> activeById = new TreeMap<>();
    private final Map<ItemId, String> activeByItem = new TreeMap<>();
    private final Set<ItemId> blocked = new TreeSet<>();
    private Map<ItemId, Integer> inventory = Map.of();
    private long nextTaskId = 1;

    /** Default low-water mark is half the target, rounded down; stock is refilled up to target. */
    public List<String> maintain(ItemId item, int targetCount) {
        validateCount(targetCount, "target count");
        return maintain(item, targetCount, targetCount / 2);
    }

    /** Configures a target and low-water mark. Set refillBelow to zero to refill only when stock is empty. */
    public List<String> maintain(ItemId item, int targetCount, int refillBelow) {
        Objects.requireNonNull(item, "item");
        validateCount(targetCount, "target count");
        if (refillBelow < 0 || refillBelow >= targetCount) {
            throw new IllegalArgumentException("Refill threshold must be between 0 and target count - 1");
        }
        Target next = new Target(targetCount, refillBelow);
        Target previous = targets.get(item);
        List<String> cancelled = List.of();
        if (previous != null && !previous.equals(next)) cancelled = removeActive(item);
        if (previous == null && targets.size() >= MAX_TARGETS) {
            throw new IllegalArgumentException("At most " + MAX_TARGETS + " items can be maintained");
        }
        targets.put(item, next);
        blocked.remove(item); // An explicit maintain command is a user-requested retry event.
        return cancelled;
    }

    /** Removes one target and returns only its maintenance-owned task IDs for adapter cancellation. */
    public List<String> unmaintain(ItemId item) {
        Objects.requireNonNull(item, "item");
        targets.remove(item);
        blocked.remove(item);
        return removeActive(item);
    }

    /** Clears all maintained targets, returning only maintenance-owned task IDs for adapter cancellation. */
    public List<String> unmaintainAll() {
        List<String> cancelled = new ArrayList<>(activeById.keySet());
        targets.clear();
        blocked.clear();
        activeById.clear();
        activeByItem.clear();
        return List.copyOf(cancelled);
    }

    /** Supplies a fresh observed inventory snapshot and issues eligible bounded requests. */
    public List<MaintenanceRequest> onInventoryChanged(Map<ItemId, Integer> observedCounts) {
        Map<ItemId, Integer> nextInventory = immutableCounts(observedCounts);
        if (!inventory.equals(nextInventory)) blocked.clear();
        inventory = nextInventory;
        return issueEligibleRequests();
    }

    /** Releases a completed task, reconciles against observed inventory, and may issue a progress-driven refill. */
    public List<MaintenanceRequest> complete(String taskId, Map<ItemId, Integer> observedCounts) {
        return finishTask(taskId, observedCounts);
    }

    /** Releases a failed or cancelled task. No retry occurs until observed inventory changes or maintain is repeated. */
    public List<MaintenanceRequest> fail(String taskId, Map<ItemId, Integer> observedCounts) {
        return finishTask(taskId, observedCounts);
    }

    /** Current status is derived only from the latest snapshot supplied by the adapter. */
    public List<Status> statuses() {
        List<Status> result = new ArrayList<>(targets.size());
        for (Map.Entry<ItemId, Target> entry : targets.entrySet()) {
            ItemId item = entry.getKey();
            Target target = entry.getValue();
            int actual = count(inventory, item);
            int reserved = activeRequest(item).map(MaintenanceRequest::deficitCount).orElse(0);
            int shortfall = Math.max(0, target.count() - actual);
            int deficit = Math.max(0, shortfall - reserved);
            String taskId = activeByItem.get(item);
            State state;
            if (actual >= target.count()) state = State.SATISFIED;
            else if (taskId != null) state = State.QUEUED;
            else if (actual > target.refillBelow()) state = State.HOLDING_ABOVE_REFILL_POINT;
            else if (blocked.contains(item)) state = State.BLOCKED_UNTIL_INVENTORY_CHANGE;
            else state = State.REFILL_DUE;
            result.add(new Status(item, target.count(), target.refillBelow(), actual, reserved, shortfall, deficit, state, taskId));
        }
        return List.copyOf(result);
    }

    /** Requests currently owned by this model; callers can reconcile their queue against these IDs. */
    public List<MaintenanceRequest> activeRequests() { return List.copyOf(activeById.values()); }

    /**
     * Inventory physically held for maintained targets. Adapters can subtract these counts before
     * planning unrelated goals so those goals do not spend protected stock.
     */
    public Map<ItemId, Integer> reservedCounts() {
        return protectedCountsExcept(null);
    }

    /** Like {@link #reservedCounts()}, but permits the target's own acquisition to use its current stock. */
    public Map<ItemId, Integer> reservedCountsFor(ItemId acquisitionTarget) {
        return protectedCountsExcept(Objects.requireNonNull(acquisitionTarget, "acquisitionTarget"));
    }

    /** Expected outputs represented by queued maintenance requests, separate from physically held stock. */
    public Map<ItemId, Integer> activeOutputReservations() {
        TreeMap<ItemId, Integer> result = new TreeMap<>();
        for (MaintenanceRequest request : activeById.values()) {
            result.merge(request.item(), request.deficitCount(), Math::addExact);
        }
        return immutableOrdered(result);
    }

    private List<MaintenanceRequest> finishTask(String taskId, Map<ItemId, Integer> observedCounts) {
        Objects.requireNonNull(taskId, "taskId");
        Map<ItemId, Integer> nextInventory = immutableCounts(observedCounts);
        MaintenanceRequest request = activeById.get(taskId);
        if (request == null) throw new IllegalArgumentException("Unknown maintenance task: " + taskId);
        activeById.remove(taskId);
        activeByItem.remove(request.item());
        boolean inventoryChanged = !inventory.equals(nextInventory);
        if (inventoryChanged) blocked.clear();
        inventory = nextInventory;
        if (!inventoryChanged) blocked.add(request.item());
        else blocked.remove(request.item());
        return issueEligibleRequests();
    }

    private List<MaintenanceRequest> issueEligibleRequests() {
        List<MaintenanceRequest> created = new ArrayList<>();
        for (Map.Entry<ItemId, Target> entry : targets.entrySet()) {
            if (activeById.size() >= MAX_ACTIVE_TASKS) break;
            ItemId item = entry.getKey();
            Target target = entry.getValue();
            int actual = count(inventory, item);
            if (actual > target.refillBelow() || actual >= target.count() || blocked.contains(item)
                    || activeByItem.containsKey(item)) continue;
            int deficit = target.count() - actual;
            if (deficit <= 0) continue;
            String taskId = "maintain-" + nextTaskId++;
            MaintenanceRequest request = new MaintenanceRequest(taskId, item, target.count(), deficit, actual);
            activeById.put(taskId, request);
            activeByItem.put(item, taskId);
            created.add(request);
        }
        return List.copyOf(created);
    }

    private List<String> removeActive(ItemId item) {
        String taskId = activeByItem.remove(item);
        if (taskId == null) return List.of();
        activeById.remove(taskId);
        return List.of(taskId);
    }

    private java.util.Optional<MaintenanceRequest> activeRequest(ItemId item) {
        String taskId = activeByItem.get(item);
        return taskId == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(activeById.get(taskId));
    }

    private Map<ItemId, Integer> protectedCountsExcept(ItemId except) {
        TreeMap<ItemId, Integer> result = new TreeMap<>();
        targets.forEach((item, target) -> {
            if (item.equals(except)) return;
            int held = Math.min(count(inventory, item), target.count());
            if (held > 0) result.put(item, held);
        });
        return immutableOrdered(result);
    }

    private static Map<ItemId, Integer> immutableCounts(Map<ItemId, Integer> counts) {
        Objects.requireNonNull(counts, "observedCounts");
        if (counts.size() > MAX_INVENTORY_ITEMS) throw new IllegalArgumentException("Inventory snapshot contains too many item types");
        TreeMap<ItemId, Integer> copy = new TreeMap<>();
        for (Map.Entry<ItemId, Integer> entry : counts.entrySet()) {
            ItemId item = Objects.requireNonNull(entry.getKey(), "inventory item");
            Integer count = Objects.requireNonNull(entry.getValue(), "inventory count");
            validateInventoryCount(count);
            if (count > 0) copy.put(item, count);
        }
        return immutableOrdered(copy);
    }

    private static Map<ItemId, Integer> immutableOrdered(Map<ItemId, Integer> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static int count(Map<ItemId, Integer> counts, ItemId item) { return counts.getOrDefault(item, 0); }

    private static void validateCount(int count, String description) {
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException(description + " must be between 1 and " + MAX_COUNT);
    }

    private static void validateInventoryCount(int count) {
        if (count < 0 || count > MAX_COUNT) throw new IllegalArgumentException("Inventory count must be between 0 and " + MAX_COUNT);
    }
}
