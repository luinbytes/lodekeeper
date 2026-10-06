package dev.lodekeeper.fabric.modern;

import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Custom registry fixtures are loaded only by the verification Loom source set. */
public final class VerificationContentInitializer implements ModInitializer {
    static final String RUBY_ORE_ID = "lodekeeper_verification:ruby_ore";
    static final String RUBY_ID = "lodekeeper_verification:ruby";
    static final String RUBY_GEAR_ID = "lodekeeper_verification:ruby_gear";
    static final String BREAD_ID = "minecraft:bread";
    static final String DYNAMIC_COLLISION_ID = "lodekeeper_verification:dynamic_collision";
    static volatile boolean geometryBlockFull = true;

    private static final String SOURCE_CONTRACT = """
        {"schema":1,"gather":[{"id":"ruby_ore","item":"lodekeeper_verification:ruby","blocks":["lodekeeper_verification:ruby_ore"],"tools":["minecraft:stone_pickaxe"]}]}
        """;

    @Override
    public void onInitialize() {
        Identifier oreId = Identifier.parse(RUBY_ORE_ID);
        Block ore = Registry.register(BuiltInRegistries.BLOCK, oreId,
            new Block(BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, oreId))
                .strength(3.0F, 3.0F)
                .requiresCorrectToolForDrops()
                .overrideLootTable(Optional.of(ResourceKey.create(
                    Registries.LOOT_TABLE, Identifier.parse("lodekeeper_verification:blocks/ruby_ore"))))));
        Registry.register(BuiltInRegistries.ITEM, oreId,
            new BlockItem(ore, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, oreId))));
        registerItem(RUBY_ID, new Item.Properties());
        registerItem(RUBY_GEAR_ID, new Item.Properties().stacksTo(1));
        Identifier dynamicCollisionId = Identifier.parse(DYNAMIC_COLLISION_ID);
        Registry.register(BuiltInRegistries.BLOCK, dynamicCollisionId,
            VerificationApi.dynamicCollisionBlock(dynamicCollisionId));
    }

    private static void registerItem(String id, Item.Properties properties) {
        Identifier identifier = Identifier.parse(id);
        Registry.register(BuiltInRegistries.ITEM, identifier,
            new Item(properties.setId(ResourceKey.create(Registries.ITEM, identifier))));
    }

    static void ensureSourceContract(Path runDirectory) throws IOException {
        Path actualRunDirectory = runDirectory.toRealPath();
        Path configDirectory = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
        if (!configDirectory.startsWith(actualRunDirectory) || configDirectory.equals(actualRunDirectory)
            || !"config".equals(configDirectory.getFileName().toString())) {
            throw new IOException("verification config is not under the isolated run directory: " + configDirectory);
        }
        Path current = actualRunDirectory;
        for (Path component : actualRunDirectory.relativize(configDirectory)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("refusing a linked or non-directory verifier config path: " + current);
            }
        }
        Files.createDirectories(configDirectory);
        Path actualConfig = configDirectory.toRealPath();
        if (!actualConfig.startsWith(actualRunDirectory)) {
            throw new IOException("verification config resolves outside the isolated run directory");
        }
        Path sourceFile = actualConfig.resolve("lodekeeper-sources.json");
        if (Files.exists(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
            requireMatchingContract(sourceFile);
            return;
        }
        try {
            Files.writeString(sourceFile, SOURCE_CONTRACT, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException race) {
            requireMatchingContract(sourceFile);
        }
    }

    private static void requireMatchingContract(Path sourceFile) throws IOException {
        if (Files.isSymbolicLink(sourceFile) || !Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing to replace non-regular verifier source file: " + sourceFile);
        }
        if (Files.size(sourceFile) > 262_144) throw new IOException("refusing to read oversized verifier source file");
        try {
            if (!JsonParser.parseString(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .equals(JsonParser.parseString(SOURCE_CONTRACT))) {
                throw new IOException("existing verifier source contract differs; left untouched: " + sourceFile);
            }
        } catch (IllegalStateException | com.google.gson.JsonParseException malformed) {
            throw new IOException("existing verifier source contract is invalid JSON; left untouched: " + sourceFile, malformed);
        }
    }
}
