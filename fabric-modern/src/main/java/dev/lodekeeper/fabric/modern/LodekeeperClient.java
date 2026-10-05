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
    private String[] panelLines = new String[0];
    private int panelTicks;

    @Override public void onInitializeClient() {
        Minecraft client = Minecraft.getInstance();
        engine = new AutomationEngine(client, LodekeeperConfig.load());
        WorldVisualization.register(client, engine);
        KeyMapping stop = KeyMappingHelper.registerKeyMapping(GameApi.keyMapping(
                "key.lodekeeper.stop", InputConstants.KEY_K, KeyMapping.Category.MISC));

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String body = CommandParser.clientCommandBody(message, engine.config.prefix);
            if (body == null) return true;
            client.execute(() -> command(body));
            return false;
        });
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (stop.consumeClick()) engine.stop();
            engine.tick();
            updatePanel();
        });
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> engine.terrain.changedChunk(chunk.getPos().x(), chunk.getPos().z()));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> engine.terrain.changedChunk(chunk.getPos().x(), chunk.getPos().z()));
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath("lodekeeper", "status"), (graphics, delta) -> {
                    if (client.player == null || WorldVisualizationHudApi.isHidden(client) || !engine.config.showHud
                            || !engine.visualizationActive() || panelLines.length == 0) return;
                    int width = Math.max(1, Math.min(304, graphics.guiWidth() - 16));
                    graphics.fill(8, 8, 8 + width, 16 + panelLines.length * 12, 0xc918242b);
                    graphics.fill(8, 8, 10, 16 + panelLines.length * 12,
                            engine.visualizationPaused() ? 0xffffc45e : 0xff55dce8);
                    for (int i = 0; i < panelLines.length; i++) {
                        String line = client.font.plainSubstrByWidth(panelLines[i], Math.max(1, width - 16));
                        graphics.text(client.font, line, 16, 13 + i * 12,
                                i == 0 ? 0xffabf49b : i == 3 ? 0xffa8c2df : 0xffedf4f6);
                    }
                });
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
        CommandParser.ParseResult parsed = parser.parse(body);
        if (!parsed.success()) {
            engine.message(parsed.error().message() + " " + parsed.error().usage());
            return;
        }
        CommandParser.Command command = parsed.command();
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
            else engine.message("This command is recognized but is not implemented by the 26.3 adapter");
        } catch (Exception ex) {
            engine.message("Command failed: " + ex.getMessage());
        }
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
            engine.message("prefix='" + config.prefix + "', searchRadius=" + config.searchRadius +
                    ", allowBreaking=" + config.allowBreaking + ", allowBuilding=" + config.allowBuilding +
                    ", allowParkour=" + config.allowParkour + ", autoEat=" + config.autoEat + ", optimizeWoodTools=" + config.optimizeWoodTools + ", allowExploration=" + config.allowExploration
                    + ", explorationAttempts=" + config.explorationAttempts + ", explorationDistance=" + config.explorationDistance
                    + ", showPath=" + config.showPath + ", showSearch=" + config.showSearch + ", showHud=" + config.showHud);
            return;
        }
        String key = command.key(), value = command.value();
        if (value == null) {
            engine.message("Use config <key> <value>. Editable: prefix, searchRadius, allowBreaking, allowBuilding, allowParkour, pauseBelowHealth, pauseOnScreen, autoEat, optimizeWoodTools, allowExploration, explorationAttempts, explorationDistance, showPath, showSearch, showHud");
            return;
        }
        switch (key) {
            case "prefix" -> {
                if (value.isBlank() || value.length() > 16 || value.startsWith("/"))
                    throw new IllegalArgumentException("Prefix must be 1–16 characters and may not start with /");
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
        config.save();
        engine.message("Saved " + key);
    }

    private static boolean bool(String text) {
        if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Use true or false");
        return Boolean.parseBoolean(text);
    }
}
