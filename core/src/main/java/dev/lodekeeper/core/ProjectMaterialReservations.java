package dev.lodekeeper.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Temporary inventory protection for optional work alongside a remaining project plan. */
public final class ProjectMaterialReservations {
    private static final int MAX_PLAN_STEPS = 100_000;

    private ProjectMaterialReservations() { }

    /** Rebuild from actual stock each time; use the returned view only for optional work. */
    public static InventorySnapshot protectOptionalWork(InventorySnapshot stock, ProjectPlanResult remaining) {
        Objects.requireNonNull(stock, "stock");
        Objects.requireNonNull(remaining, "remaining");
        if (!remaining.success()) {
            throw new IllegalArgumentException("Cannot reserve materials from a failed project plan");
        }
        return protectOptionalWork(stock, remaining.steps());
    }

    public static InventorySnapshot protectOptionalWork(InventorySnapshot stock, PlanResult remaining) {
        Objects.requireNonNull(remaining, "remaining");
        if (!remaining.success()) throw new IllegalArgumentException("Cannot reserve materials from a failed plan");
        return protectOptionalWork(stock, remaining.steps());
    }

    private static InventorySnapshot protectOptionalWork(InventorySnapshot stock, java.util.List<PlanStep> steps) {
        Objects.requireNonNull(stock, "stock");
        if (steps.size() > MAX_PLAN_STEPS) {
            throw new IllegalArgumentException("Project plan exceeds the planner step limit");
        }

        Map<ItemId, Long> consumed = new HashMap<>();
        Map<ItemId, Long> reusable = new HashMap<>();
        for (PlanStep step : steps) {
            for (SelectedRequirement requirement : step.requirements()) {
                if (requirement instanceof SelectedItemRequirement item) {
                    if (item.consumed()) {
                        consumed.merge(item.item(), (long) item.count(), ProjectMaterialReservations::saturatingAdd);
                    } else {
                        reusable.merge(item.item(), (long) item.count(), Long::max);
                    }
                } else if (requirement instanceof SelectedToolRequirement tool) {
                    reusable.merge(tool.item(), 1L, Long::max);
                }
            }
        }

        Map<ItemId, Integer> protectedCounts = new HashMap<>(stock.protectedCounts());
        stock.counts().forEach((item, count) -> {
            long demand = saturatingAdd(consumed.getOrDefault(item, 0L), reusable.getOrDefault(item, 0L));
            long reserve = saturatingAdd(stock.protectedCounts().getOrDefault(item, 0), demand);
            if (reserve > 0) protectedCounts.put(item, (int) Math.min(count, reserve));
        });
        return new InventorySnapshot(stock.counts(), stock.availableStations(), stock.remainingDurability(),
                protectedCounts, stock.durabilityLots(), stock.toolLots());
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
