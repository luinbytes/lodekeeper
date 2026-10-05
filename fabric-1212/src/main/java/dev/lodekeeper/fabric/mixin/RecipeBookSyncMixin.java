package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.LodekeeperClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidates learned-display catalogs after the client recipe book changes. */
@Mixin(ClientRecipeBook.class)
abstract class RecipeBookSyncMixin {
    @Inject(method = "add", at = @At("TAIL"))
    private void lodekeeper$recipeAdded(RecipeDisplayEntry entry, CallbackInfo callback) {
        notifyCatalog();
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void lodekeeper$recipeRemoved(NetworkRecipeId id, CallbackInfo callback) {
        notifyCatalog();
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void lodekeeper$recipesCleared(CallbackInfo callback) {
        notifyCatalog();
    }

    private static void notifyCatalog() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world != null) LodekeeperClient.recipeDisplaysChanged(world);
    }
}
