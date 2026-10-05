package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

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

    void drive(float forward, float sideways, boolean jump, boolean sneak) {
        this.forward = forward;
        this.sideways = sideways;
        this.jump = jump;
        this.sneak = sneak;
    }

    void idle() { drive(0, 0, false, false); }

    void release() {
        idle();
        if (owner != null && owner.input == this) owner.input = previous != null ? previous : new KeyboardInput(client.options);
        owner = null;
        previous = null;
    }

    @Override public void tick() {
        float length = (float) Math.hypot(forward, sideways);
        float scale = length > 1 ? 1 / length : 1;
        movementVector = new Vec2f(sideways * scale, forward * scale);
        playerInput = new PlayerInput(forward > 0, forward < 0, sideways > 0, sideways < 0, jump, sneak, false);
    }
}
