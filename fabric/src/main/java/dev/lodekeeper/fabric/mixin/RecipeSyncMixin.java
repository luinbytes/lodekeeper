package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.LodekeeperClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.network.packet.s2c.play.SynchronizeRecipesS2CPacket;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the client catalog stale after vanilla has installed synchronized recipes. */
@Mixin(ClientPlayNetworkHandler.class)
abstract class RecipeSyncMixin {
    @Shadow @Final private RecipeManager recipeManager;

    @Inject(method = "onSynchronizeRecipes", at = @At("TAIL"))
    private void lodekeeper$recipesSynchronized(SynchronizeRecipesS2CPacket packet, CallbackInfo callback) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world != null && world.getRecipeManager() == recipeManager) {
            LodekeeperClient.recipesSynchronized(world, recipeManager);
        }
    }
}
