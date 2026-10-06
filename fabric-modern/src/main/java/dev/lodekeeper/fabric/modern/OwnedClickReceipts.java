package dev.lodekeeper.fabric.modern;

import net.minecraft.world.item.ItemStack;
import java.util.function.BooleanSupplier;

/** Reconciles only owned stonecutter input changes; ordinary clicks retain native prediction. */
public final class OwnedClickReceipts {
    public interface Receipt {
        long lodekeeper$inputSequence();
        long lodekeeper$contentsSequence();
        ItemStack lodekeeper$receivedInput();
    }
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final class Scope {
        final int containerId;
        boolean claimed;
        Scope(int containerId) { this.containerId = containerId; }
    }
    private OwnedClickReceipts() {}
    private static void enter(int containerId) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested owned inventory click");
        CURRENT.set(new Scope(containerId));
    }
    static boolean inputTransfer(int containerId, BooleanSupplier operation) {
        enter(containerId);
        try { return operation.getAsBoolean(); } finally { CURRENT.remove(); }
    }
    static void outputClick(int containerId, Runnable operation) {
        enter(containerId);
        try { operation.run(); } finally { CURRENT.remove(); }
    }
    /** Called only by the synchronous client packet constructor, once per owned click. */
    public static boolean claimInputReconciliation(int containerId) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.claimed || scope.containerId != containerId) return false;
        scope.claimed = true;
        return true;
    }
}
