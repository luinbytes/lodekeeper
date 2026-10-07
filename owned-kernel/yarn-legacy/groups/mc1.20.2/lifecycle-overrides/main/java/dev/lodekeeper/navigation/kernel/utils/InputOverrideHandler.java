/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lodekeeper.navigation.kernel.utils;

import dev.lodekeeper.navigation.kernel.Baritone;
import dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI;
import dev.lodekeeper.navigation.kernel.api.event.events.TickEvent;
import dev.lodekeeper.navigation.kernel.api.utils.IInputOverrideHandler;
import dev.lodekeeper.navigation.kernel.api.utils.input.Input;
import dev.lodekeeper.navigation.kernel.behavior.Behavior;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private LocalPlayer ownedPlayer;
    private net.minecraft.client.player.Input previousInput;
    private PlayerMovementInput ownedInput;
    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return !(ctx.minecraft().screen instanceof dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier)
                && input != null && this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            restoreOwnedInput();
            return;
        }
        if (ctx.minecraft().screen instanceof dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier
                || !dev.lodekeeper.navigation.kernel.OwnedMutationGuard.safeEquipment(ctx.player())) {
            baritone.getPathingBehavior().forceCancel();
            restoreOwnedInput();
            return;
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT));
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT));

        if (inControl()) {
            if (ownedPlayer != ctx.player() || ctx.player().input != ownedInput) {
                restoreMovementInput();
                ownedPlayer = ctx.player();
                previousInput = ownedPlayer.input;
                ownedInput = new PlayerMovementInput(this);
                ownedPlayer.input = ownedInput;
            }
        } else {
            restoreMovementInput();
        }
    }

    public void restoreOwnedInput() {
        baritone.getRuntime().requireMainThread();
        clearAllKeys();
        restoreMovementInput();
        blockBreakHelper.stopBreakingBlock();
    }

    private void restoreMovementInput() {
        if (ownedPlayer != null && ownedPlayer.input == ownedInput) {
            ownedPlayer.input = previousInput;
        }
        ownedPlayer = null;
        previousInput = null;
        ownedInput = null;
    }

    private boolean inControl() {
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return baritone.getPathingBehavior().isPathing() || baritone != OwnedKernelAPI.getProvider().getPrimaryBaritone();
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }
}
