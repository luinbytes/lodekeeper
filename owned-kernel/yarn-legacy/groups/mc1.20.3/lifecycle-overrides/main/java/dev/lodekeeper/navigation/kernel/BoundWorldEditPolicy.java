package dev.lodekeeper.navigation.kernel;

import net.minecraft.core.BlockPos;

public final class BoundWorldEditPolicy {
    private final OwnedKernelRuntime owner;
    private final OwnedKernelRuntime.Session session;
    private final long policyGeneration;
    private final WorldEditPolicy rules;

    BoundWorldEditPolicy(OwnedKernelRuntime owner, OwnedKernelRuntime.Session session, long policyGeneration, WorldEditPolicy rules) {
        this.owner = owner;
        this.session = session;
        this.policyGeneration = policyGeneration;
        this.rules = rules;
    }

    public String dimension() { return session == null ? "" : session.dimension(); }
    public long worldGeneration() { return session == null ? -1 : session.generation(); }
    public boolean current() { return owner.isCurrent(session) && owner.policyGeneration() == policyGeneration; }
    public boolean mayBreak(BlockPos pos) { return current() && rules.mayBreak(pos); }
    public boolean mayPlace(BlockPos pos) { return current() && rules.mayPlace(pos); }
}
