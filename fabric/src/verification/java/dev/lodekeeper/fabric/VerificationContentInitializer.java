package dev.lodekeeper.fabric;

import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Test-only registry content and extension data for the isolated runtime verifier. */
public final class VerificationContentInitializer implements ModInitializer {
    static final String RUBY_ORE_ID = "lodekeeper_verification:ruby_ore";
    static final String RUBY_ID = "lodekeeper_verification:ruby";
    static final String RUBY_GEAR_ID = "lodekeeper_verification:ruby_gear";
    static final String BREAD_ID = "minecraft:bread";

    private static final String SOURCE_CONTRACT = """
        {"schema":1,"gather":[{"id":"ruby_ore","item":"lodekeeper_verification:ruby","blocks":["lodekeeper_verification:ruby_ore"],"tools":["minecraft:stone_pickaxe"]}]}
        """;

    @Override
    public void onInitialize() {
        var rubyOreId = GameApi.identifier(RUBY_ORE_ID);
        Block rubyOre = Registry.register(Registries.BLOCK, rubyOreId,
            VerificationApi.rubyOre(rubyOreId, GameApi.identifier("lodekeeper_verification:blocks/ruby_ore")));
        Registry.register(Registries.ITEM, rubyOreId, VerificationApi.rubyOreItem(rubyOre, rubyOreId));
        Registry.register(Registries.ITEM, GameApi.identifier(RUBY_ID),
            VerificationApi.ruby(GameApi.identifier(RUBY_ID)));
        Registry.register(Registries.ITEM, GameApi.identifier(RUBY_GEAR_ID),
            VerificationApi.rubyGear(GameApi.identifier(RUBY_GEAR_ID)));
    }

    static void ensureSourceContract(Path runDirectory) throws IOException {
        Path actualRunDirectory = runDirectory.toRealPath();
        Path configDirectory = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
        if (!configDirectory.startsWith(actualRunDirectory) || configDirectory.equals(actualRunDirectory)
            || !"config".equals(configDirectory.getFileName().toString())) {
            throw new IOException("verification config is not the module run/config directory: " + configDirectory);
        }
        Path current = actualRunDirectory;
        for (Path component : actualRunDirectory.relativize(configDirectory)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("refusing a linked or non-directory config path: " + current);
            }
        }
        Files.createDirectories(configDirectory);
        Path actualConfigDirectory = configDirectory.toRealPath();
        if (!actualConfigDirectory.startsWith(actualRunDirectory)) {
            throw new IOException("verification config resolves outside the module run directory: " + actualConfigDirectory);
        }

        Path sourceFile = actualConfigDirectory.resolve("lodekeeper-sources.json");
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
        if (Files.size(sourceFile) > 262_144) {
            throw new IOException("refusing to read oversized verifier source file: " + sourceFile);
        }
        try {
            if (!JsonParser.parseString(Files.readString(sourceFile, StandardCharsets.UTF_8))
                .equals(JsonParser.parseString(SOURCE_CONTRACT))) {
                throw new IOException("existing lodekeeper-sources.json differs from the verifier contract; left untouched: " + sourceFile);
            }
        } catch (IllegalStateException | com.google.gson.JsonParseException malformed) {
            throw new IOException("existing lodekeeper-sources.json is not valid JSON; left untouched: " + sourceFile, malformed);
        }
    }
}
