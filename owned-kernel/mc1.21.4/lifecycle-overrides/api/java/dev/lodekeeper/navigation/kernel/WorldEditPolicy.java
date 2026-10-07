package dev.lodekeeper.navigation.kernel;

import net.minecraft.core.BlockPos;

public sealed interface WorldEditPolicy permits WorldEditPolicy.DenyAll, WorldEditPolicySnapshot {
    boolean mayBreak(BlockPos position);
    boolean mayPlace(BlockPos position);

    static WorldEditPolicy denyAll() {
        return DenyAll.INSTANCE;
    }

    enum DenyAll implements WorldEditPolicy {
        INSTANCE;
        public boolean mayBreak(BlockPos position) { return false; }
        public boolean mayPlace(BlockPos position) { return false; }
    }
}
