package dev.lodekeeper.core;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded, Minecraft-free decisions for optionally acquiring a harvest tool. */
public final class HarvestInvestment {
    private static final int MAX_REMAINING_BLOCKS = 1_000_000;
    private static final int MAX_DURABILITY = 10_000_000;
    private static final int MAX_WEAR_PER_BLOCK = 1_000_000;
    private static final int MAX_RETAINED_COPIES = 4;
    private static final int MAX_ADDITIONAL_COPIES = 2;
    private static final int MAX_AUXILIARY_STEPS = 64;
    private static final int MAX_STEP_OPERATIONS = 1_000_000;
    private static final long MAX_TICK_ESTIMATE = 1_000_000_000L;

    private HarvestInvestment() { }

    /**
     * A proposed extra tool count and the safe work capacity known from held and new stacks.
     * Call only after the ordinary request already has a complete plan. Capacities are estimates
     * from explicit durability data and the supplied wear model; no duplicate held tools are inferred.
     */
    public static Optional<ToolDemand> additionalDemand(InventorySnapshot inventory, ItemId tool,
            int remainingBlocks, int freshDurability, int wearPerBlock, int minBeforeBreak, int maxAdditional) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(tool, "tool");
        if (remainingBlocks < 1 || remainingBlocks > MAX_REMAINING_BLOCKS
                || freshDurability < 1 || freshDurability > MAX_DURABILITY
                || wearPerBlock < 1 || wearPerBlock > MAX_WEAR_PER_BLOCK
                || minBeforeBreak < wearPerBlock + 1 || minBeforeBreak > MAX_DURABILITY
                || maxAdditional < 0 || maxAdditional > MAX_ADDITIONAL_COPIES
                || inventory.protectedCounts().getOrDefault(tool, 0) > 0) {
            return Optional.empty();
        }

        long usableHeldCapacity = 0;
        for (int durability : inventory.durabilityLots().getOrDefault(tool, java.util.List.of())) {
            usableHeldCapacity += safeOperations(durability, wearPerBlock, minBeforeBreak);
        }
        if (usableHeldCapacity >= remainingBlocks || maxAdditional == 0) return Optional.empty();

        long freshCapacity = safeOperations(freshDurability, wearPerBlock, minBeforeBreak);
        if (freshCapacity == 0) return Optional.empty();

        int heldCount = inventory.count(tool);
        int retainedSlots = MAX_RETAINED_COPIES - heldCount;
        if (retainedSlots <= 0) return Optional.empty();
        long missingCapacity = remainingBlocks - usableHeldCapacity;
        long desiredAdditional = (missingCapacity - 1) / freshCapacity + 1;
        int additionalCount = (int) Math.min(desiredAdditional, Math.min(maxAdditional, retainedSlots));
        if (additionalCount < 1) return Optional.empty();

        long addedSafeCapacity = Math.multiplyExact((long) additionalCount, freshCapacity);
        return Optional.of(new ToolDemand(tool, heldCount, additionalCount, heldCount + additionalCount,
                remainingBlocks, usableHeldCapacity, addedSafeCapacity));
    }

    /**
     * Approves a complete tool acquisition only when all auxiliary work is locally bounded and
     * the caller's benefit estimate exceeds the complete step cost by the requested margin.
     * Tick values are adapter estimates, not a universal timing guarantee.
     */
    public static Decision approve(PlanResult ordinaryPlan, PlanResult investmentPlan, ToolDemand demand,
            Set<ItemId> knownLocalGatherOutputs, Set<StationId> allowedPlacementStations, TickEstimates estimates) {
        Objects.requireNonNull(ordinaryPlan, "ordinaryPlan");
        Objects.requireNonNull(investmentPlan, "investmentPlan");
        Objects.requireNonNull(demand, "demand");
        Objects.requireNonNull(estimates, "estimates");
        Set<ItemId> localOutputs = Set.copyOf(Objects.requireNonNull(knownLocalGatherOutputs, "knownLocalGatherOutputs"));
        Set<StationId> allowedStations = Set.copyOf(Objects.requireNonNull(allowedPlacementStations, "allowedPlacementStations"));

        long benefit = estimates.expectedBenefitTicks();
        if (!ordinaryPlan.success()) return rejected("ordinary plan is incomplete", benefit);
        if (!investmentPlan.success()) return rejected("tool investment plan is incomplete", benefit);
        if (!demand.tool().equals(investmentPlan.target()) || demand.targetCount() != investmentPlan.requestedCount()) {
            return rejected("tool investment plan does not match the demand", benefit);
        }
        if (investmentPlan.steps().isEmpty()) return rejected("tool investment plan has no acquisition steps", benefit);
        if (investmentPlan.steps().size() > MAX_AUXILIARY_STEPS) return rejected("tool investment plan has too many steps", benefit);

        long cost = 0;
        try {
            for (PlanStep step : investmentPlan.steps()) {
                int operations = step.operationCount();
                if (operations < 1 || operations > MAX_STEP_OPERATIONS) {
                    return rejected("tool investment step operation count is out of bounds", benefit);
                }
                long rate;
                switch (step.kind()) {
                    case GATHER -> {
                        if (!localOutputs.contains(step.output())) return rejected("gather output is not known to be local", benefit);
                        rate = estimates.miningAndTravelTicksPerLog();
                    }
                    case CRAFT -> {
                        if (step.station() != null && !allowedStations.contains(step.station())) {
                            return rejected("crafting station is not allowed", benefit);
                        }
                        rate = estimates.craftTicksPerOperation();
                    }
                    case PLACE_STATION -> {
                        if (!allowedStations.contains(step.station())) return rejected("station placement is not allowed", benefit);
                        rate = estimates.stationPlacementTicks();
                    }
                    case SMELT, CUSTOM -> { return rejected("tool investment requires unsupported auxiliary work", benefit); }
                    default -> { return rejected("tool investment contains an unsupported step", benefit); }
                }
                cost = Math.addExact(cost, Math.multiplyExact((long) operations, rate));
            }
            long requiredBenefit = Math.addExact(cost, estimates.minimumNetSavingTicks());
            long netSaving = Math.subtractExact(benefit, cost);
            if (benefit < requiredBenefit) return new Decision(false, benefit, cost, netSaving, "estimated saving is below the required minimum");
            return new Decision(true, benefit, cost, netSaving, "approved within the supplied estimates");
        } catch (ArithmeticException exception) {
            return rejected("tool investment cost exceeded safe arithmetic bounds", benefit);
        }
    }

    private static long safeOperations(int durability, int wearPerBlock, int minBeforeBreak) {
        if (durability < minBeforeBreak) return 0;
        return (durability - (long) minBeforeBreak) / wearPerBlock + 1;
    }

    private static Decision rejected(String reason, long benefit) {
        return new Decision(false, benefit, 0, 0, reason);
    }

    /** Exact requested inventory count and known safe capacities for the proposal. */
    public record ToolDemand(ItemId tool, int heldCount, int additionalCount, int targetCount,
                             int remainingBlocks, long usableHeldCapacity, long addedSafeCapacity) {
        public ToolDemand {
            Objects.requireNonNull(tool, "tool");
            if (heldCount < 0 || heldCount >= MAX_RETAINED_COPIES || additionalCount < 1
                    || additionalCount > MAX_ADDITIONAL_COPIES || targetCount != heldCount + additionalCount
                    || targetCount > MAX_RETAINED_COPIES || remainingBlocks < 1
                    || remainingBlocks > MAX_REMAINING_BLOCKS || usableHeldCapacity < 0
                    || usableHeldCapacity > (long) MAX_RETAINED_COPIES * MAX_DURABILITY
                    || addedSafeCapacity < 1 || addedSafeCapacity > (long) MAX_ADDITIONAL_COPIES * MAX_DURABILITY) {
                throw new IllegalArgumentException("Invalid harvest tool demand");
            }
        }
    }

    /** Explicit adapter-supplied estimates used to compare the complete bootstrap plan. */
    public record TickEstimates(long expectedBenefitTicks, long miningAndTravelTicksPerLog,
                                long craftTicksPerOperation, long stationPlacementTicks,
                                long minimumNetSavingTicks) {
        public TickEstimates {
            if (!bounded(expectedBenefitTicks) || !bounded(miningAndTravelTicksPerLog)
                    || !bounded(craftTicksPerOperation) || !bounded(stationPlacementTicks)
                    || minimumNetSavingTicks < 1 || !bounded(minimumNetSavingTicks)) {
                throw new IllegalArgumentException("Tick estimates must be bounded and the minimum saving positive");
            }
        }

        private static boolean bounded(long value) { return value >= 0 && value <= MAX_TICK_ESTIMATE; }
    }

    /** Decision and estimate arithmetic for an optional tool bootstrap. */
    public record Decision(boolean approved, long estimatedBenefitTicks, long estimatedCostTicks,
                           long estimatedNetSavingTicks, String reason) {
        public Decision {
            Objects.requireNonNull(reason, "reason");
            if (estimatedCostTicks < 0) throw new IllegalArgumentException("Estimated cost cannot be negative");
        }
    }
}
