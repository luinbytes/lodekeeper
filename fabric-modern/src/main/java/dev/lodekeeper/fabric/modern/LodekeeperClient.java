package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.CommandParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/** Client-only entry point. Prefix commands are canceled before chat leaves the client. */
public final class LodekeeperClient implements ClientModInitializer {
    static AutomationEngine engine;
    private final CommandParser parser = new CommandParser();

    @Override public void onInitializeClient() {
        Minecraft client = Minecraft.getInstance();
        engine = new AutomationEngine(client, LodekeeperConfig.load());
        KeyMapping stop = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.lodekeeper.stop", InputConstants.Type.KEYBOARD, InputConstants.KEY_K,
                KeyMapping.Category.MISC));

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.startsWith(engine.config.prefix)) return true;
            String body = message.substring(engine.config.prefix.length());
            client.execute(() -> command(body));
            return false;
        });
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (stop.consumeClick()) engine.stop();
            engine.tick();
        });
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> engine.terrain.changedChunk(chunk.getPos().x(), chunk.getPos().z()));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> engine.terrain.changedChunk(chunk.getPos().x(), chunk.getPos().z()));
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath("lodekeeper", "status"), (graphics, delta) -> {
                    if (client.player == null || engine.status().startsWith("idle")) return;
                    graphics.text(client.font, "Lodekeeper · " + engine.status(), 8, 8, 0xabf49b);
                });
    }

    private void command(String body) {
        CommandParser.ParseResult parsed = parser.parse(body);
        if (!parsed.success()) {
            engine.message(parsed.error().message() + " " + parsed.error().usage());
            return;
        }
        CommandParser.Command command = parsed.command();
        try {
            if (command instanceof CommandParser.GetCommand get) engine.enqueue(get.item(), get.count());
            else if (command instanceof CommandParser.StopCommand) engine.stop();
            else if (command instanceof CommandParser.PauseCommand) engine.pause("requested");
            else if (command instanceof CommandParser.ResumeCommand) engine.resume();
            else if (command instanceof CommandParser.StatusCommand) engine.message(engine.status());
            else if (command instanceof CommandParser.QueueCommand) engine.showQueue();
            else if (command instanceof CommandParser.ClearCommand) engine.clearQueue();
            else if (command instanceof CommandParser.PlanCommand plan) engine.preview(plan.item(), plan.count());
            else if (command instanceof CommandParser.ConfigCommand config) configure(config);
            else if (command instanceof CommandParser.ProjectCommand project) engine.enqueueProject(project.name());
            else if (command instanceof CommandParser.ProjectsCommand) engine.listProjects();
            else if (command instanceof CommandParser.MaintainCommand maintain) engine.maintainItem(maintain.item(), maintain.count());
            else if (command instanceof CommandParser.UnmaintainCommand unmaintain) engine.unmaintain(unmaintain.item());
            else if (command instanceof CommandParser.MaintainedCommand) engine.showMaintained();
            else engine.message("This command is recognized but is not implemented by the 26.3 adapter");
        } catch (Exception ex) {
            engine.message("Command failed: " + ex.getMessage());
        }
    }

    private void configure(CommandParser.ConfigCommand command) throws java.io.IOException {
        LodekeeperConfig config = engine.config;
        if (command.key() == null) {
            engine.message("prefix='" + config.prefix + "', searchRadius=" + config.searchRadius +
                    ", allowBreaking=" + config.allowBreaking + ", allowBuilding=" + config.allowBuilding +
                    ", allowParkour=" + config.allowParkour);
            return;
        }
        String key = command.key(), value = command.value();
        if (value == null) {
            engine.message("Use config <key> <value>. Editable: prefix, searchRadius, allowBreaking, allowBuilding, allowParkour, pauseBelowHealth, pauseOnScreen");
            return;
        }
        switch (key) {
            case "prefix" -> {
                if (value.isBlank() || value.length() > 16 || value.startsWith("/"))
                    throw new IllegalArgumentException("Prefix must be 1–16 characters and may not start with /");
                config.prefix = value;
            }
            case "searchRadius" -> config.searchRadius = Integer.parseInt(value);
            case "pauseBelowHealth" -> config.pauseBelowHealth = Float.parseFloat(value);
            case "allowBreaking" -> config.allowBreaking = bool(value);
            case "allowBuilding" -> config.allowBuilding = bool(value);
            case "allowParkour" -> config.allowParkour = bool(value);
            case "pauseOnScreen" -> config.pauseOnScreen = bool(value);
            default -> throw new IllegalArgumentException("Unknown config key: " + key);
        }
        config.save();
        engine.message("Saved " + key);
    }

    private static boolean bool(String text) {
        if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Use true or false");
        return Boolean.parseBoolean(text);
    }
}
