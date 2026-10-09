package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Temporarily owns the player's vanilla input object while one route is executing. */
final class BotInput extends ClientInput implements AirRecoveryAction.DiagnosticInput {
    private LocalPlayer owner;
    private ClientInput previous;
    private float forward, sideways;
    private boolean jump, sneak;

    void acquire(Minecraft client) {
        if (client.player == null) return;
        if (owner != client.player) {
            release();
            owner = client.player;
        }
        if (owner.input != this) {
            previous = owner.input;
            owner.input = this;
        }
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

    @Override public String diagnosticState() {
        return "intendedForward=" + forward + ",intendedSideways=" + sideways + ",intendedJump=" + jump + ",intendedSneak=" + sneak
                + ",appliedForward=" + getMoveVector().y + ",appliedSideways=" + getMoveVector().x
                + ",appliedJump=" + keyPresses.jump() + ",appliedSneak=" + keyPresses.shift();
    }

    void discardOwnership() {
        owner = null; previous = null;
    }
    void release() {
        idle();
        if (owner != null && owner.input == this) owner.input = previous != null ? previous : new ClientInput();
        owner = null;
        previous = null;
    }

    @Override public void tick() {
        keyPresses = new Input(forward > 0, forward < 0, sideways > 0, sideways < 0, jump, sneak, false);
        Vec2 movement = new Vec2(sideways, forward);
        moveVector = movement.lengthSquared() > 1 ? movement.normalized() : movement;
    }
}
