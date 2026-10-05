package dev.lodekeeper.fabric;

import dev.lodekeeper.core.CommandParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Client-only entry point: local chat is intercepted before any server message is sent. */
public final class LodekeeperClient implements ClientModInitializer {
    static AutomationEngine engine;
    private final CommandParser parser = new CommandParser();
    @Override public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        engine = new AutomationEngine(client, LodekeeperConfig.load());
        KeyBinding stop = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.lodekeeper.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.lodekeeper"));
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.startsWith(engine.config.prefix)) return true;
            String body = message.substring(engine.config.prefix.length());
            client.execute(() -> command(body));
            return false;
        });
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (stop.wasPressed()) engine.stop();
            engine.tick();
        });
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> engine.terrain.changedChunk(chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> engine.terrain.changedChunk(chunk.getPos().x, chunk.getPos().z));
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            if (client.player == null || client.options.hudHidden || engine.status().startsWith("idle")) return;
            String status = "Lodekeeper · " + engine.status();
            context.drawTextWithShadow(client.textRenderer, status, 8, 8, 0xabf49b);
        });
    }
    private void command(String body) {
        var parsed = parser.parse(body);
        if (!parsed.success()) { engine.message(parsed.error().message() + " " + parsed.error().usage()); return; }
        var command = parsed.command();
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
        } catch (Exception ex) { engine.message("Command failed: " + ex.getMessage()); }
    }
    private void configure(CommandParser.ConfigCommand command) throws java.io.IOException {
        LodekeeperConfig config = engine.config;
        if (command.key() == null) {
            engine.message("prefix='" + config.prefix + "', searchRadius=" + config.searchRadius + ", allowBreaking=" + config.allowBreaking + ", allowBuilding=" + config.allowBuilding + ", allowParkour=" + config.allowParkour + ", autoEat=" + config.autoEat); return;
        }
        String key = command.key(), value = command.value();
        if (value == null) { engine.message("Use config <key> <value>. Editable: prefix, searchRadius, allowBreaking, allowBuilding, allowParkour, pauseBelowHealth, pauseOnScreen, autoEat"); return; }
        switch (key) {
            case "prefix" -> {
                if (value.isBlank() || value.length() > 16 || value.startsWith("/")) throw new IllegalArgumentException("Prefix must be 1–16 characters and may not start with /");
                config.prefix = value;
            }
            case "searchRadius" -> config.searchRadius = Integer.parseInt(value);
            case "pauseBelowHealth" -> config.pauseBelowHealth = Float.parseFloat(value);
            case "allowBreaking" -> config.allowBreaking = bool(value);
            case "allowBuilding" -> config.allowBuilding = bool(value);
            case "allowParkour" -> config.allowParkour = bool(value);
            case "pauseOnScreen" -> config.pauseOnScreen = bool(value);
            case "autoEat" -> config.autoEat = bool(value);
            default -> throw new IllegalArgumentException("Unknown config key: " + key);
        }
        config.save(); engine.message("Saved " + key);
    }
    private static boolean bool(String text) {
        if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) throw new IllegalArgumentException("Use true or false");
        return Boolean.parseBoolean(text);
    }
}
