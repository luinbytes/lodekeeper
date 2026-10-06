package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.CommandParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import dev.lodekeeper.nav.Goal;
import dev.lodekeeper.nav.NavigationSnapshot;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;

/** Client-only entry point. Prefix commands are canceled before chat leaves the client. */
public final class LodekeeperClient implements ClientModInitializer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("lodekeeper");
    static AutomationEngine engine;
    private boolean openSettingsPending;
    private final CommandParser parser = new CommandParser();
    private String[] panelLines = new String[0];
    private int panelTicks;
    private Object clockTask;
    private long taskStartedNanos, lastProgressLogNanos;
    private String taskLabel = "unknown", currentTargetInfo = "none", lastTaskDetail = "idle";
    private BlockPos observedTarget;
    private boolean targetObserved, pendingTargetLog, wasSearching, stopRequested;
    private boolean taskBeginLogged, beginDeferred;
    private int lastRouteRetries, immediateEventCount;
    private long eventWindowStartNanos;
    private final ArrayDeque<String> pendingRouteEvents = new ArrayDeque<>(16);
    private int droppedRouteEvents;

    public static boolean prepareAutomatedBreak(BlockPos position) {
        return engine == null || engine.prepareAutomatedBreak(position);
    }

    @Override public void onInitializeClient() {
        Minecraft client = Minecraft.getInstance();
        engine = new AutomationEngine(client, LodekeeperConfig.load());
        if (engine.config.debugLogging) logInfo("INIT mod=" + metadataVersion("lodekeeper")
                + " minecraft=" + metadataVersion("minecraft"));
        WorldVisualization.register(client, engine);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> engine.dispose());
        KeyMapping stop = KeyMappingHelper.registerKeyMapping(GameApi.keyMapping(
                "key.lodekeeper.stop", InputConstants.KEY_K, KeyMapping.Category.MISC));
        KeyMapping settings = KeyMappingHelper.registerKeyMapping(GameApi.keyMapping(
                "key.lodekeeper.settings", InputConstants.KEY_RSHIFT, KeyMapping.Category.MISC));

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String body = CommandParser.clientCommandBody(message, engine.config.prefix);
            if (body == null) return true;
            client.execute(() -> command(body));
            return false;
        });
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (stop.consumeClick()) { stopRequested = true; engine.stop(); }
            while (settings.consumeClick()) openSettingsPending = true;
            if (openSettingsPending) {
                openSettingsPending = false;
                GameApi.setScreen(mc, new AutomationSettingsScreen(engine.config, () -> engine.message("Settings saved")));
            }
            engine.tick();
            syncDiagnostics(client);
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
        var route = engine.diagnosticNavigation();
        String metrics = route.searching()
                ? route.path() == null ? "Planning route" : "Moving · planning next route"
                : route.path() == null ? "K to stop · " + engine.config.prefix.trim() + " status for details"
                : "Route · " + Math.max(0, route.path().length() - 1) + " waypoints ahead";
        if (route.retries() > 0) metrics += " · retry " + route.retries();
        String elapsed = clockTask == null ? "0:00:00" : elapsedLabel(System.nanoTime() - taskStartedNanos);
        panelLines = new String[]{"LODEKEEPER · " + (engine.visualizationPaused() ? "PAUSED" : "WORKING") + " · " + elapsed,
                engine.visualizationGoal(), engine.visualizationDetail(), metrics};
    }

    private void syncDiagnostics(Minecraft client) {
        long now = System.nanoTime();
        Object active = engine.diagnosticTaskIdentity();
        String detail = safeField(engine.visualizationDetail(), 120);
        if (detail.contains("stopping_after")) stopRequested = true;
        if (active != clockTask) {
            if (clockTask != null) {
                String reason = active != null ? "replaced"
                        : client.player == null || client.level == null ? "disconnected"
                        : stopRequested ? "stopped" : "idle";
                endTask(reason, now, active == null ? detail : lastTaskDetail);
            }
            clockTask = active;
            resetTaskDiagnostics();
            if (active != null) {
                taskStartedNanos = now;
                lastProgressLogNanos = now;
                beginDeferred = !engine.config.debugLogging;
                stopRequested = false;
            } else {
                taskStartedNanos = 0;
                lastProgressLogNanos = 0;
                stopRequested = false;
            }
        }
        if (clockTask == null) return;
        lastTaskDetail = detail;
        if (!engine.config.debugLogging || client.player == null || client.level == null) return;
        if (!taskBeginLogged) beginTask(client, now);

        BlockPos target = engine.diagnosticTarget();
        if (!targetObserved || !Objects.equals(target, observedTarget)) {
            targetObserved = true;
            observedTarget = target == null ? null : target.immutable();
            if (observedTarget != null || currentTargetInfo.equals("none")) {
                currentTargetInfo = describeTarget(client, observedTarget);
                pendingTargetLog = observedTarget != null;
            } else {
                currentTargetInfo = "none";
                pendingTargetLog = true;
            }
        }
        if (pendingTargetLog && allowImmediateLog(now)) {
            logInfo("TARGET target=" + currentTargetInfo);
            pendingTargetLog = false;
        }

        NavigationSnapshot route = engine.diagnosticNavigation();
        if (route.searching() && !wasSearching) queueRouteEvent(routeSearchEvent(), now);
        if (wasSearching && !route.searching() && route.path() != null) {
            queueRouteEvent("ROUTE_TRAVEL path_index=" + route.nextStep() + " path_length=" + route.path().length()
                    + " target=" + currentTargetInfo, now);
        }
        if (route.retries() > lastRouteRetries) {
            queueRouteEvent("RETRY count=" + route.retries() + " target=" + currentTargetInfo + " status=" + detail, now);
        }
        wasSearching = route.searching();
        lastRouteRetries = route.retries();
        flushRouteEvents(now);

        if (now - lastProgressLogNanos >= 2_000_000_000L) {
            lastProgressLogNanos = now;
            logProgress(route, detail, now);
        }
    }

    private void beginTask(Minecraft client, long now) {
        String summary = engine.visualizationGoal();
        int separator = summary.lastIndexOf(" · ");
        taskLabel = separator < 0 ? summary : summary.substring(0, separator);
        String counts = separator < 0 ? "unknown/unknown" : summary.substring(separator + 3);
        int slash = counts.indexOf('/');
        String inventory = slash < 0 ? "unknown" : counts.substring(0, slash);
        String goal = slash < 0 ? counts : counts.substring(slash + 1);
        BlockPos feet = client.player == null ? null : client.player.blockPosition();
        String startFeet = feet == null ? "unknown" : feet.getX() + "," + feet.getY() + "," + feet.getZ();
        logInfo("BEGIN label=" + safeField(taskLabel, 80) + " goal=" + safeField(goal, 24)
                + " inventory=" + safeField(inventory, 24) + " startfeet=" + startFeet
                + " elapsed_ms=" + elapsedMillis(now) + " continued=" + beginDeferred);
        taskBeginLogged = true;
        beginDeferred = false;
    }

    private void endTask(String reason, long now, String detail) {
        if (engine.config.debugLogging && taskBeginLogged) {
            logInfo("task_end reason=" + reason + " label=" + safeField(taskLabel, 80)
                    + " elapsed_ms=" + elapsedMillis(now) + " pending_route_events=" + pendingRouteEvents.size()
                    + " dropped_route_events=" + droppedRouteEvents + " status=" + safeField(detail, 120));
        }
    }

    private String routeSearchEvent() {
        Goal goal = engine.diagnosticRouteGoal();
        String anchor = goal == null ? "unknown" : goal.x + "," + goal.y + "," + goal.z;
        String kind = goal == null ? "unknown" : goal.kind.name().toLowerCase(Locale.ROOT);
        int feetY16 = goal == null ? 0 : goal.feetY16;
        return "ROUTE_SEARCH kind=" + kind + " anchor=" + anchor + " feet_y16=" + feetY16
                + " candidates=" + engine.diagnosticRouteGoalCandidateCount() + " target=" + currentTargetInfo;
    }

    private void logProgress(NavigationSnapshot route, String detail, long now) {
        String phase = phase(engine.visualizationPaused(), route, detail);
        logInfo("PROGRESS elapsed_ms=" + elapsedMillis(now) + " phase=" + phase
                + " backend=baritone target=" + currentTargetInfo + " navigation_elapsed_ms=" + route.searchNanos() / 1_000_000L
                + " navigation_ticks=" + route.searchTicks() + " searching=" + route.searching()
                + " path_index=" + route.nextStep() + " path_length=" + (route.path() == null ? 0 : route.path().length())
                + " retries=" + route.retries() + " pending_route_events=" + pendingRouteEvents.size()
                + " dropped_route_events=" + droppedRouteEvents + " status=" + safeField(detail, 120));
    }

    private static String phase(boolean paused, NavigationSnapshot route, String detail) {
        if (paused) return "paused";
        if (route.searching()) return "searching";
        if (route.path() != null) return "traveling";
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tool_investment_")) lower = lower.substring("tool_investment_".length()).replaceFirst("^_+", "");
        if (lower.startsWith("exploring_") || lower.startsWith("finding_a_safe_exploration_")
                || lower.startsWith("trying_another_safe_exploration_")) return "exploring";
        if (lower.startsWith("eating_")) return "eating";
        if (lower.startsWith("craft_")) return "crafting";
        if (lower.startsWith("smelt_")) return "smelting";
        if (lower.startsWith("mining_") || lower.startsWith("gather_")) return "gathering";
        if (lower.startsWith("place_station_")) return "station";
        if (lower.startsWith("discovering_") || lower.startsWith("finding_nearby_")
                || lower.startsWith("indexing_")) return "discovering";
        if (lower.startsWith("planning")) return "planning";
        if (lower.startsWith("waiting_") || lower.startsWith("foreground_queued_")
                || lower.startsWith("maintenance_waiting_")) return "waiting";
        return "working";
    }

    private String describeTarget(Minecraft client, BlockPos target) {
        if (target == null) return "none";
        String coords = target.getX() + "," + target.getY() + "," + target.getZ();
        if (client.level == null || client.level.getChunk(
                target.getX() >> 4, target.getZ() >> 4, ChunkStatus.FULL, false) == null) return coords + " block=unknown";
        return coords + " block=" + safeField(BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(target).getBlock()).toString(), 128);
    }

    private void queueRouteEvent(String event, long now) {
        if (pendingRouteEvents.size() == 16) {
            pendingRouteEvents.removeFirst();
            if (droppedRouteEvents < Integer.MAX_VALUE) droppedRouteEvents++;
        }
        pendingRouteEvents.addLast(event + " elapsed_ms=" + elapsedMillis(now));
    }

    private void flushRouteEvents(long now) {
        while (!pendingRouteEvents.isEmpty() && allowImmediateLog(now)) logInfo(pendingRouteEvents.removeFirst());
    }

    private boolean allowImmediateLog(long now) {
        if (eventWindowStartNanos == 0 || now - eventWindowStartNanos >= 1_000_000_000L) {
            eventWindowStartNanos = now;
            immediateEventCount = 0;
        }
        if (immediateEventCount >= 8) return false;
        immediateEventCount++;
        return true;
    }

    private void resetTaskDiagnostics() {
        taskLabel = "unknown";
        currentTargetInfo = "none";
        lastTaskDetail = "idle";
        observedTarget = null;
        targetObserved = false;
        pendingTargetLog = false;
        wasSearching = false;
        lastRouteRetries = 0;
        taskBeginLogged = false;
        beginDeferred = false;
        eventWindowStartNanos = 0;
        immediateEventCount = 0;
        pendingRouteEvents.clear();
        droppedRouteEvents = 0;
    }

    private static String metadataVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    private long elapsedMillis(long now) { return Math.max(0L, now - taskStartedNanos) / 1_000_000L; }

    private static String elapsedLabel(long elapsedNanos) {
        long seconds = Math.max(0L, elapsedNanos) / 1_000_000_000L;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long remainder = seconds % 60;
        return hours + ":" + twoDigits(minutes) + ":" + twoDigits(remainder);
    }

    private static String twoDigits(long value) { return value < 10 ? "0" + value : Long.toString(value); }

    private static String safeField(String value, int limit) {
        if (value == null || value.isBlank()) return "unknown";
        StringBuilder safe = new StringBuilder(Math.min(value.length(), limit));
        for (int i = 0; i < value.length() && safe.length() < limit; i++) {
            char c = value.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':' || c == '/' ? c : '_');
        }
        return safe.toString();
    }

    private static void logInfo(String event) {
        LOGGER.info("[Lodekeeper] {}", event);
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
            else if (command instanceof CommandParser.StopCommand) { stopRequested = true; engine.stop(); }
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
        if (command.key() == null || command.key().equalsIgnoreCase("ui")) {
            if (command.value() != null) throw new IllegalArgumentException("config ui takes no value");
            openSettingsPending = true;
            return;
        }
        if (command.key().equalsIgnoreCase("list")) {
            if (command.value() != null) throw new IllegalArgumentException("config list takes no value");
            for (String category : LodekeeperConfig.specs().stream()
                    .map(dev.lodekeeper.core.SettingSpec::category).distinct().toList()) {
                engine.message(category + ": " + LodekeeperConfig.specs().stream()
                        .filter(spec -> spec.category().equals(category))
                        .map(spec -> spec.key() + "=" + config.read(spec.key()))
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
            return;
        }
        String key = command.key();
        dev.lodekeeper.core.SettingSpec spec = dev.lodekeeper.core.AutomationSettings.spec(key);
        if (command.value() == null) {
            engine.message(spec.label() + " (" + key + ") = " + config.read(key) + ". " + spec.help());
            return;
        }
        Object previous = config.read(key);
        config.write(key, command.value());
        try { config.save(); }
        catch (java.io.IOException failure) {
            config.writeValue(key, previous);
            throw failure;
        }
        engine.message("Saved " + spec.label() + " = " + config.read(key));
    }
}
