package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.IOException;

/** Version-specific vanilla world-creation flow for the isolated runtime verifier. */
final class VerificationApi {
    private VerificationApi() {}

    static String minecraftVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }

    static void startFlatWorld(Minecraft client, String worldId, LevelStorageSource storage,
                               Runnable started, java.util.function.Consumer<Throwable> failed) {
        CreateWorldScreen.openFresh(client, () -> {}, (screen, registries, worldData, gameRules, ignoredTempDataPackDir) -> {
            LevelStorageSource.LevelStorageAccess access = null;
            try {
                if (!storage.isNewLevelIdAcceptable(worldId)) {
                    throw new IOException("isolated world ID already exists or is invalid: " + worldId);
                }
                access = storage.validateAndCreateAccess(worldId);
                WorldCreationUiState settings = screen.getUiState();
                client.createWorldOpenFlows().createLevelFromExistingSettings(
                    access,
                    settings.getSettings().dataPackResources(),
                    registries,
                    worldData,
                    gameRules
                );
                started.run();
                return true;
            } catch (Throwable failure) {
                if (access != null) access.safeClose();
                failed.accept(failure);
                return false;
            }
        });

        Screen current = client.gui.screen();
        if (!(current instanceof CreateWorldScreen createScreen)) {
            throw new IllegalStateException("vanilla world creation screen did not open");
        }
        WorldCreationUiState settings = createScreen.getUiState();
        settings.setName("Lodekeeper verification " + worldId.substring(worldId.length() - 8));
        settings.setSeed("483920105");
        settings.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
        settings.setDifficulty(Difficulty.PEACEFUL);
        settings.setAllowCommands(false);
        settings.setGenerateStructures(false);
        settings.setBonusChest(false);
        WorldCreationUiState.WorldTypeEntry flat = java.util.stream.Stream
            .concat(settings.getNormalPresetList().stream(), settings.getAltPresetList().stream())
            .filter(entry -> entry.preset().is(WorldPresets.FLAT))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("built-in flat preset is unavailable"));
        settings.setWorldType(flat);

        Button create = createButton(createScreen);
        if (create == null || !create.isActive()) {
            throw new IllegalStateException("vanilla create-world button is unavailable");
        }
        create.onPress(new KeyEvent(0, 0, 0));
    }

    private static Button createButton(CreateWorldScreen screen) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button
                && button.getMessage().getContents() instanceof TranslatableContents translated
                && translated.getKey().equals("selectWorld.create")) {
                return button;
            }
        }
        return null;
    }
}
