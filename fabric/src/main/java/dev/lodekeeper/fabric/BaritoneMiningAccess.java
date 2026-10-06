package dev.lodekeeper.fabric;

import java.util.List;
import net.minecraft.util.math.BlockPos;

public interface BaritoneMiningAccess {
    List<BlockPos> lodekeeper$knownMiningTargets();

    void lodekeeper$knownMiningTargets(List<BlockPos> value);

    List<BlockPos> lodekeeper$blacklistedMiningTargets();
}
