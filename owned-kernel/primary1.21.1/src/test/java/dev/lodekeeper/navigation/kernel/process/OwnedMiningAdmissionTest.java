package dev.lodekeeper.navigation.kernel.process;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class OwnedMiningAdmissionTest {
    @Test
    void emptyAdmissionPreservesMoreThanSnapshotLimitAndDoesNotFenceQueuedScan() {
        List<BlockPos> nativeTargets = IntStream.range(0, 96)
                .mapToObj(index -> new BlockPos(index, 64, 0)).toList();
        List<BlockPos> callerTargets = List.of(nativeTargets.get(5), nativeTargets.get(95));
        AtomicLong generation = new AtomicLong(7);
        AtomicBoolean scanQueued = new AtomicBoolean(true);
        BooleanSupplier workerIsCurrent = () -> scanQueued.get() && generation.get() == 7;

        OwnedMiningAdmission.Plan plan = OwnedMiningAdmission.plan(nativeTargets, callerTargets, List.of(),
                Set.of(), false, () -> {
                    generation.incrementAndGet();
                    scanQueued.set(false);
                });

        assertFalse(plan.changed());
        assertEquals(nativeTargets, plan.processTargets());
        assertEquals(callerTargets, plan.callerTargets());
        assertTrue(scanQueued.get());
        assertTrue(workerIsCurrent.getAsBoolean());
    }

    @Test
    void freshRejectionRemovesOnlyThatTargetAndFencesQueuedScan() {
        List<BlockPos> nativeTargets = IntStream.range(0, 96)
                .mapToObj(index -> new BlockPos(index, 64, 0)).toList();
        BlockPos rejected = nativeTargets.get(37);
        List<BlockPos> callerTargets = List.of(nativeTargets.get(9), rejected);
        List<BlockPos> expected = new ArrayList<>(nativeTargets);
        expected.remove(rejected);
        AtomicLong generation = new AtomicLong(7);
        AtomicBoolean scanQueued = new AtomicBoolean(true);
        BooleanSupplier workerIsCurrent = () -> scanQueued.get() && generation.get() == 7;

        OwnedMiningAdmission.Plan plan = OwnedMiningAdmission.plan(nativeTargets, callerTargets, List.of(),
                Set.of(rejected), true, () -> {
                    generation.incrementAndGet();
                    scanQueued.set(false);
                });

        assertTrue(plan.changed());
        assertEquals(expected, plan.processTargets());
        assertEquals(95, plan.processTargets().size());
        assertFalse(plan.processTargets().contains(rejected));
        assertEquals(List.of(nativeTargets.get(9)), plan.callerTargets());
        assertEquals(8, generation.get());
        assertFalse(scanQueued.get());
        assertFalse(workerIsCurrent.getAsBoolean());
    }
}
