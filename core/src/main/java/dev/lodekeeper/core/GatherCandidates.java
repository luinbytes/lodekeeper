package dev.lodekeeper.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Groups mining drops whose tool and quantity contracts are interchangeable. */
public final class GatherCandidates {
    private GatherCandidates() { }

    public static List<BlockId> forStep(CatalogSnapshot catalog, PlanStep step, Set<String> excludedSources) {
        LinkedHashSet<BlockId> blocks = new LinkedHashSet<>(step.candidateBlocks());
        AcquisitionSource selected = catalog.sourceById(step.sourceId());
        if (!(selected instanceof GatherSource original) || !original.output().equals(step.output()))
            return List.copyOf(blocks);
        int examined = 0;
        for (AcquisitionSource source : catalog.sourcesFor(original.output())) {
            if (++examined > 256 || blocks.size() >= 256) break;
            if (!(source instanceof GatherSource candidate) || excludedSources.contains(candidate.sourceId())
                    || candidate.outputCount() != original.outputCount()
                    || !candidate.attributes().equals(original.attributes())
                    || !sameTools(original.requirements(), candidate.requirements())) continue;
            for (BlockId block : candidate.blocks()) {
                if (blocks.size() == 256) break;
                blocks.add(block);
            }
        }
        return List.copyOf(blocks);
    }

    private static boolean sameTools(List<Requirement> first, List<Requirement> second) {
        if (first.isEmpty() && second.isEmpty()) return true;
        if (first.size() != 1 || second.size() != 1
                || !(first.get(0) instanceof ToolRequirement a)
                || !(second.get(0) instanceof ToolRequirement b)) return false;
        return a.tools().equals(b.tools()) && a.minimumDurability() == b.minimumDurability()
                && a.wearPerOperation() == b.wearPerOperation();
    }
}
