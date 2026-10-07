package dev.lodekeeper.fabric;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/** Owns input only while automation is active; never changes user's key bindings. */
final class BotInput extends Input implements AirRecoveryAction.DiagnosticInput {
    private final MinecraftClient client;
    private ClientPlayerEntity owner;
    private Input previous;
    private float forward, sideways;
    private boolean jump, sneak;
    BotInput(MinecraftClient client) { this.client = client; }
    void acquire() {
        if (client.player == null) return;
        if (owner != client.player) { release(); owner = client.player; }
        if (owner.input != this) { previous = owner.input; owner.input = this; }
    }
    void drive(float forward, float sideways, boolean jump, boolean sneak) {
        this.forward = forward; this.sideways = sideways; this.jump = jump; this.sneak = sneak;
    }
    void idle() { drive(0, 0, false, false); }
    @Override public String diagnosticState() {
        return "intendedForward=" + forward + ",intendedSideways=" + sideways + ",intendedJump=" + jump + ",intendedSneak=" + sneak
                + ",appliedForward=" + movementForward + ",appliedSideways=" + movementSideways
                + ",appliedJump=" + jumping + ",appliedSneak=" + sneaking;
    }
    void release() {
        idle();
        if (owner != null && owner.input == this) owner.input = previous != null ? previous : new KeyboardInput(client.options);
        owner = null; previous = null;
    }
    @Override public void tick(boolean slowDown, float factor) {
        movementForward = forward * (slowDown ? factor : 1);
        movementSideways = sideways * (slowDown ? factor : 1);
        pressingForward = forward > 0; pressingBack = forward < 0;
        pressingLeft = sideways > 0; pressingRight = sideways < 0;
        jumping = jump; sneaking = sneak;
    }
}
