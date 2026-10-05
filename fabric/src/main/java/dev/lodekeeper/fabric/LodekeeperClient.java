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
import net.minecraft.client.world.ClientWorld;
import net.minecraft.recipe.RecipeManager;
import org.lwjgl.glfw.GLFW;

/** Client-only entry point: local chat is intercepted before any server message is sent. */
public final class LodekeeperClient implements ClientModInitializer {
    static AutomationEngine engine;
    private final CommandParser parser = new CommandParser();
    private String[] panelLines = new String[0];
    private int panelTicks;
    @Override public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        engine = new AutomationEngine(client, LodekeeperConfig.load());
        WorldVisualization.register(client, engine);
        KeyBinding stop = KeyBindingHelper.registerKeyBinding(ClientAccess.stopKey());
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String body = CommandParser.clientCommandBody(message, engine.config.prefix);
            if (body == null) return true;
            client.execute(() -> command(body));
            return false;
        });
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (stop.wasPressed()) engine.stop();
            engine.tick();
            updatePanel();
        });
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> engine.terrain.changedChunk(chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> engine.terrain.changedChunk(chunk.getPos().x, chunk.getPos().z));
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            if (client.player == null || client.options.hudHidden || !engine.config.showHud
                    || !engine.visualizationActive() || panelLines.length == 0) return;
            int width = Math.max(1, Math.min(304, client.getWindow().getScaledWidth() - 16));
            context.fill(8, 8, 8 + width, 16 + panelLines.length * 12, 0xc918242b);
            context.fill(8, 8, 10, 16 + panelLines.length * 12,
                    engine.visualizationPaused() ? 0xffffc45e : 0xff55dce8);
            for (int i = 0; i < panelLines.length; i++) {
                String line = client.textRenderer.trimToWidth(panelLines[i], Math.max(1, width - 16));
                context.drawTextWithShadow(client.textRenderer, line, 16, 13 + i * 12,
                        i == 0 ? 0xffabf49b : i == 3 ? 0xffa8c2df : 0xffedf4f6);
            }
        });
    }
    public static void recipesSynchronized(ClientWorld packetWorld, RecipeManager manager) {
        if (engine != null) engine.recipeSynchronizationReceived(packetWorld, manager);
    }
    public static void recipeDisplaysChanged(ClientWorld packetWorld) {
        if (engine != null) engine.recipeDisplaysChanged(packetWorld);
    }
    private void updatePanel() {
        if (!engine.config.showHud || !engine.visualizationActive()) {
            panelLines = new String[0]; panelTicks = 0; return;
        }
        if (panelTicks++ % 4 != 0 && panelLines.length != 0) return;
        var route = engine.visualizationNavigation(false);
        String metrics = route.searching()
                ? "Searching · " + route.expanded() + " checked · " + route.open() + " open"
                : route.path() == null ? "K to stop · " + engine.config.prefix.trim() + " status for details"
                : "Route " + route.nextStep() + "/" + Math.max(1, route.path().length() - 1)
                    + " · " + route.searchNanos() / 1_000_000L + " ms search";
        if (route.retries() > 0) metrics += " · retry " + route.retries();
        panelLines = new String[]{"LODEKEEPER · " + (engine.visualizationPaused() ? "PAUSED" : "WORKING"),
                engine.visualizationGoal(), engine.visualizationDetail(), metrics};
    }

    private void command(String body) {
        var parsed = parser.parse(body);
        if (!parsed.success()) { engine.message(parsed.error().message() + " " + parsed.error().usage()); return; }
        var command = parsed.command();
        try {
            if (command instanceof CommandParser.HelpCommand) showHelp();
            else if (command instanceof CommandParser.GetCommand get) engine.enqueue(get.item(), get.count());
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
    private void showHelp() {
        String prefix = engine.config.prefix;
        engine.message("Get starts automatically: " + prefix + "get wood 64 or " + prefix + "get iron_pickaxe");
        engine.message("Counts are total inventory targets; omitted count means 1. Close chat to let automation run.");
        engine.message("Check progress: " + prefix + "status or " + prefix + "queue. Control: pause, resume, stop.");
        engine.message(CommandParser.USAGE);
    }

    private void configure(CommandParser.ConfigCommand command) throws java.io.IOException {
        LodekeeperConfig config = engine.config;
        if (command.key() == null) {
            engine.message("prefix='" + config.prefix + "', searchRadius=" + config.searchRadius + ", allowBreaking=" + config.allowBreaking + ", allowBuilding=" + config.allowBuilding + ", allowParkour=" + config.allowParkour + ", autoEat=" + config.autoEat + ", optimizeWoodTools=" + config.optimizeWoodTools + ", allowExploration=" + config.allowExploration
                    + ", explorationAttempts=" + config.explorationAttempts + ", explorationDistance=" + config.explorationDistance
                    + ", showPath=" + config.showPath + ", showSearch=" + config.showSearch + ", showHud=" + config.showHud); return;
        }
        String key = command.key(), value = command.value();
        if (value == null) { engine.message("Use config <key> <value>. Editable: prefix, searchRadius, allowBreaking, allowBuilding, allowParkour, pauseBelowHealth, pauseOnScreen, autoEat, optimizeWoodTools, allowExploration, explorationAttempts, explorationDistance, showPath, showSearch, showHud"); return; }
        switch (key) {
            case "prefix" -> {
                if (value.isBlank() || value.length() > 16 || value.startsWith("/")) throw new IllegalArgumentException("Prefix must be 1–16 characters and may not start with /");
                config.prefix = value;
            }
            case "searchRadius" -> config.searchRadius = Integer.parseInt(value);
            case "allowExploration" -> config.allowExploration = bool(value);
            case "explorationAttempts" -> config.explorationAttempts = Integer.parseInt(value);
            case "explorationDistance" -> config.explorationDistance = Integer.parseInt(value);
            case "pauseBelowHealth" -> config.pauseBelowHealth = Float.parseFloat(value);
            case "allowBreaking" -> config.allowBreaking = bool(value);
            case "allowBuilding" -> config.allowBuilding = bool(value);
            case "allowParkour" -> config.allowParkour = bool(value);
            case "pauseOnScreen" -> config.pauseOnScreen = bool(value);
            case "autoEat" -> config.autoEat = bool(value);
            case "optimizeWoodTools" -> config.optimizeWoodTools = bool(value);
            case "showPath" -> config.showPath = bool(value);
            case "showSearch" -> config.showSearch = bool(value);
            case "showHud" -> config.showHud = bool(value);
            default -> throw new IllegalArgumentException("Unknown config key: " + key);
        }
        config.save(); engine.message("Saved " + key);
    }
    private static boolean bool(String text) {
        if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) throw new IllegalArgumentException("Use true or false");
        return Boolean.parseBoolean(text);
    }
}
