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

package dev.lodekeeper.navigation.kernel.cache;

import dev.lodekeeper.navigation.kernel.Baritone;
import dev.lodekeeper.navigation.kernel.api.cache.IWorldProvider;
import dev.lodekeeper.navigation.kernel.api.utils.IPlayerContext;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @author Brady
 * @since 8/4/2018
 */
public class WorldProvider implements IWorldProvider {

    private final Map<Path, WorldData> worldCache = new HashMap<>();

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private volatile WorldData currentWorld;
    private final Map<Path, WorldData> closingWorlds = new HashMap<>();
    private Level pendingWorld;

    public WorldProvider(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    @Override
    public final WorldData getCurrentWorld() {
        return this.currentWorld;
    }

    /**
     * Called when a new world is initialized to discover the
     *
     * @param world The new world
     */
    public final void initWorld(Level world) {
        baritone.getRuntime().requireMainThread();
        this.getSaveDirectories(world).ifPresent(dirs -> {
            final Path worldDir = dirs.getA();
            final Path readmeDir = dirs.getB();

            try {
                // lol wtf is this baritone folder in my minecraft save?
                // good thing we have a readme
                Files.createDirectories(readmeDir);
                Files.write(
                        readmeDir.resolve("readme.txt"),
                        "https://github.com/cabaletta/baritone\n".getBytes(StandardCharsets.US_ASCII)
                );
            } catch (IOException ignored) {}

            // We will actually store the world data in a subfolder: "DIM<id>"
            final Path worldDataDir = this.getWorldDataDirectory(worldDir, world);
            WorldData previous = closingWorlds.get(worldDataDir);
            if (previous != null && !previous.cache.finalFlushComplete()) {
                pendingWorld = world;
                return;
            }
            closingWorlds.remove(worldDataDir);
            pendingWorld = null;
            try {
                Files.createDirectories(worldDataDir);
            } catch (IOException ignored) {}

            System.out.println("Baritone world data dir: " + worldDataDir);
            synchronized (worldCache) {
                this.currentWorld = worldCache.computeIfAbsent(worldDataDir, d -> new WorldData(d, world.dimensionType(), baritone.getRuntime()));
            }
        });
    }

    public final void closeWorld() {
        baritone.getRuntime().requireMainThread();
        pendingWorld = null;
        WorldData world = this.currentWorld;
        this.currentWorld = null;
        if (world == null) {
            return;
        }
        world.onClose();
        closingWorlds.put(world.directory, world);
        worldCache.values().removeIf(value -> value == world);
    }

    private Path getWorldDataDirectory(Path parent, Level world) {
        ResourceLocation dimId = world.dimension().location();
        int height = world.dimensionType().logicalHeight();
        return parent.resolve(dimId.getNamespace()).resolve(dimId.getPath() + "_" + height);
    }

    /**
     * @param world The world
     * @return An {@link Optional} containing the world's baritone dir and readme dir, or {@link Optional#empty()} if
     *         the world isn't valid for caching.
     */
    private Optional<Tuple<Path, Path>> getSaveDirectories(Level world) {
        Path worldDir;
        Path readmeDir;

        // If there is an integrated server running (Aka Singleplayer) then do magic to find the world save file
        if (ctx.minecraft().hasSingleplayerServer()) {
            worldDir = ctx.minecraft().getSingleplayerServer().getWorldPath(LevelResource.ROOT);

            // Gets the "depth" of this directory relative to the game's run directory, 2 is the location of the world
            if (worldDir.relativize(ctx.minecraft().gameDirectory.toPath()).getNameCount() != 2) {
                // subdirectory of the main save directory for this world
                worldDir = worldDir.getParent();
            }

            worldDir = worldDir.resolve("lodekeeper");
            readmeDir = worldDir;
        } else { // Otherwise, the server must be remote...
            String folderName;
            final ServerData serverData = ctx.minecraft().getCurrentServer();
            if (serverData != null) {
                folderName = serverData.isRealm() ? "realms" : serverData.ip;
            } else {
                //replaymod causes null currentServer and false singleplayer.
                System.out.println("World seems to be a replay. Not loading Baritone cache.");
                currentWorld = null;
                return Optional.empty();
            }
            if (SystemUtils.IS_OS_WINDOWS) {
                folderName = folderName.replace(":", "_");
            }
            // TODO: This should probably be in "baritone/servers"
            worldDir = baritone.getDirectory().resolve(folderName);
            // Just write the readme to the baritone directory instead of each server save in it
            readmeDir = baritone.getDirectory();
        }

        return Optional.of(new Tuple<>(worldDir, readmeDir));
    }

    public void tick() {
        baritone.getRuntime().requireMainThread();
        closingWorlds.values().forEach(world -> world.cache.retryFinalFlush());
        closingWorlds.values().removeIf(world -> world.cache.finalFlushComplete());
        if (pendingWorld != null) { initWorld(pendingWorld); }
        WorldData world = currentWorld;
        if (world != null) {
            BlockPos center = ctx.player() == null ? BlockPos.ZERO : ctx.playerFeet();
            world.cache.tick(System.nanoTime(), center);
        }
    }

    public void finishClosedFlushes() {
        baritone.getRuntime().requireMainThread();
        closingWorlds.values().forEach(world -> world.cache.finishFinalFlush());
        closingWorlds.clear();
    }

    public void close() {
        baritone.getRuntime().requireMainThread();
        closeWorld();
        worldCache.values().forEach(WorldData::onClose);
        worldCache.clear();
    }
}
