package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;

/** Owns input only while automation is active; never changes user's key bindings. */
final class BotInput extends Input {
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

    Object ownedPredecessor(Object player, Object installedInput) {
        return owner != null && owner == player && installedInput == this && owner.input == this
                && previous != null && previous != this ? previous : null;
    }

    void drive(float forward, float sideways, boolean jump, boolean sneak) {
        this.forward = forward;
        this.sideways = sideways;
        this.jump = jump;
        this.sneak = sneak;
    }

    void idle() { drive(0, 0, false, false); }

    void discardOwnership() {
        owner = null;
        previous = null;
    }

    void release() {
        idle();
        if (owner != null && owner.input == this) owner.input = previous != null ? previous : new KeyboardInput(client.options);
        owner = null;
        previous = null;
    }

    @Override public void tick(boolean slowDown, float factor) {
        float multiplier = slowDown ? factor : 1;
        movementForward = forward * multiplier;
        movementSideways = sideways * multiplier;
        playerInput = new PlayerInput(forward > 0, forward < 0, sideways > 0, sideways < 0, jump, sneak, false);
    }
}
