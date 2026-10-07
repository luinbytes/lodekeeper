package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.RetreatSnapshotDiagnostics;
import dev.lodekeeper.navigation.kernel.snapshot.ChunkSnapshot;
import dev.lodekeeper.navigation.kernel.snapshot.OwnedWorldSnapshots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = OwnedWorldSnapshots.class, remap = false)
public abstract class RetreatWorldSnapshotsMixin implements RetreatSnapshotDiagnostics.SnapshotMaps {
    @Override @Accessor(value = "pending", remap = false)
    public abstract Map<Long, ?> lodekeeper$pending();

    @Override @Accessor(value = "ready", remap = false)
    public abstract Map<Long, ChunkSnapshot> lodekeeper$ready();

    @Override @Accessor(value = "revisions", remap = false)
    public abstract java.util.concurrent.ConcurrentMap<Long, Long> lodekeeper$revisions();

    @Override @Accessor(value = "hasInterestCenter", remap = false)
    public abstract boolean lodekeeper$hasInterestCenter();

    @Override @Accessor(value = "interestX", remap = false)
    public abstract int lodekeeper$interestX();

    @Override @Accessor(value = "interestZ", remap = false)
    public abstract int lodekeeper$interestZ();

    @Inject(method = "reset", at = @At("TAIL"), remap = false)
    private void lodekeeper$clearRetreatSnapshot(CallbackInfo callback) {
        RetreatSnapshotDiagnostics.snapshotsReset((OwnedWorldSnapshots) (Object) this);
    }
}
