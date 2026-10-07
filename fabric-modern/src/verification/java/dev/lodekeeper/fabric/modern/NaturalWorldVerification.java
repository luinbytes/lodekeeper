package dev.lodekeeper.fabric.modern;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.storage.LevelStorageSource;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in end-to-end request verifier for an untouched, normally generated survival world. */
final class NaturalWorldVerification {
    private static final String GOAL_PROPERTY = "lodekeeper.verify.naturalGoal";
    private static final long WORLD_INIT_TIMEOUT_NANOS = 120_000_000_000L;
    private static final int MAX_REQUEST_TIMEOUT_SECONDS = 15 * 60;
    private static final int WORLD_READY_TICKS = 60;
    private static final int COMPLETE_STABILITY_TICKS = 20;
    private static final int MAX_RECEIPTS = 512;
    private static final int MAX_SCREENSHOTS = 12;
    private static final long SCREENSHOT_INTERVAL_NANOS = 5_000_000_000L;
    private static final long FINAL_SCREENSHOT_SAVE_GRACE_NANOS = 2_000_000_000L;
    private static final String IRON_PICKAXE_ID = "minecraft:iron_pickaxe";
    private static final List<String> DIAMOND_GEAR_IDS = List.of(
        "minecraft:diamond_pickaxe", "minecraft:diamond_axe", "minecraft:diamond_shovel",
        "minecraft:diamond_hoe", "minecraft:diamond_sword", "minecraft:diamond_helmet",
        "minecraft:diamond_chestplate", "minecraft:diamond_leggings", "minecraft:diamond_boots");

    private enum State { OPENING_WORLD, WAITING_FOR_WORLD, WAITING_FOR_PLAYER, RUNNING, TERMINAL }

    private final Minecraft client;
    private final String runId = Instant.now().toString().replace(':', '-') + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    private final String worldId = "natural-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final long initializedAtNanos = System.nanoTime();
    private final List<JsonObject> serverTargetReceipts = new ArrayList<>();
    private final JsonArray expectedItems = new JsonArray();
    private final Object screenshotQueueLock = new Object();
    private final ArrayDeque<ScreenshotRequest> screenshotQueue = new ArrayDeque<>();
    private final java.util.Set<String> scheduledScreenshotNames = new java.util.HashSet<>();
    private final List<String> screenshotFiles = new ArrayList<>();

    private volatile State state = State.OPENING_WORLD;
    private volatile UUID playerId;
    private volatile boolean commandStarted;
    private volatile boolean terminal;
    private volatile boolean finalizing;
    private volatile ServerObservation latestObservation;
    private volatile int deathsObserved;
    private String lastTaskScreenshotPhase = "";
    private int screenshotSlotsReserved;
    private int screenshotWritesPending;
    private long lastScreenshotStartedAtNanos;
    private long finalScreenshotStartedAtNanos;
    private boolean finalScreenshotQueued;
    private boolean finalScreenshotStarted;
    private boolean finalScreenshotSettled;
    private boolean evidenceWritten;
    private Path verificationRoot;
    private Path evidenceDirectory;
    private Path worldsDirectory;
    private Path backupsDirectory;
    private LevelStorageSource storage;
    private String goal;
    private String command;
    private String failure = "";
    private String result = "running";
    private long seed;
    private int requestTimeoutSeconds = 900;
    private long worldLaunchStartedAtNanos;
    private long worldReadyAtNanos;
    private long commandStartedAtNanos;
    private long commandServerTick = -1;
    private long satisfiedSinceServerTick = -1;
    private long serverReceiptsDropped;
    private int commandDeaths;
    private int readyTicks;
    private boolean worldLaunchQueued;
    private boolean worldCreationStarted;
    private double commandX;
    private double commandY;
    private double commandZ;
    private Map<String, Integer> previousHeldCounts = Map.of();
    private double previousX = Double.NaN;
    private double previousY = Double.NaN;
    private double previousZ = Double.NaN;
    private int firstServerMovementMillis = -1;
    private long serverMovingTicks;
    private double serverDistanceMeters;
    private float minimumHealth = 20.0F;
    private int minimumHunger = 20;

    private NaturalWorldVerification(Minecraft client) {
        this.client = client;
    }

    static void start(Minecraft client) {
        new NaturalWorldVerification(client).initialize();
    }

    private void initialize() {
        try {
            goal = System.getProperty(GOAL_PROPERTY, "");
            if (!List.of("wood", "iron_pickaxe", "gear_diamond").contains(goal)) {
                throw new IllegalArgumentException(GOAL_PROPERTY + " must be exactly wood, iron_pickaxe, or gear_diamond");
            }
            if (goal.equals("wood")) expectedItems.add("minecraft:logs item tag (one or more logs)");
            else if (goal.equals("iron_pickaxe")) expectedItems.add(IRON_PICKAXE_ID);
            else if (goal.equals("gear_diamond")) DIAMOND_GEAR_IDS.forEach(expectedItems::add);
            seed = Long.parseLong(System.getProperty("lodekeeper.verify.naturalSeed", "483920105"));
            requestTimeoutSeconds = Integer.parseInt(System.getProperty("lodekeeper.verify.naturalTimeoutSeconds", "900"));
            if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > MAX_REQUEST_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("naturalTimeoutSeconds must be between 1 and " + MAX_REQUEST_TIMEOUT_SECONDS);
            }
            prepareIsolatedPaths();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
            ServerTickEvents.END_SERVER_TICK.register(this::serverTick);
            ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
                UUID ownedPlayer = playerId;
                if (commandStarted && ownedPlayer != null && entity instanceof ServerPlayer player
                        && ownedPlayer.equals(player.getUUID())) deathsObserved++;
            });
            System.out.println("[Lodekeeper natural verification] Starting " + goal
                + " in a normal survival world; evidence under " + evidenceDirectory);
        } catch (Exception exception) {
            fail("initialization failed: " + exception);
        }
    }

    private void prepareIsolatedPaths() throws IOException {
        Path runDirectory = client.gameDirectory.toPath().toRealPath();
        Path verificationBase = runDirectory.resolve("verification");
        createContainedDirectory(runDirectory, verificationBase);
        Path actualBase = verificationBase.toRealPath();
        Path ordinarySaves = runDirectory.resolve("saves");
        Path actualSaves = Files.exists(ordinarySaves, LinkOption.NOFOLLOW_LINKS)
            ? ordinarySaves.toRealPath() : ordinarySaves.normalize();
        if (overlaps(actualBase, actualSaves)) throw new IOException("verification output overlaps ordinary saves");

        verificationRoot = actualBase.resolve(runId);
        createContainedDirectory(actualBase, verificationRoot);
        evidenceDirectory = verificationRoot.resolve("evidence");
        worldsDirectory = verificationRoot.resolve("worlds");
        backupsDirectory = verificationRoot.resolve("backups");
        createContainedDirectory(verificationRoot, evidenceDirectory);
        createContainedDirectory(verificationRoot, worldsDirectory);
        createContainedDirectory(verificationRoot, backupsDirectory);
        Path actualRoot = verificationRoot.toRealPath();
        evidenceDirectory = evidenceDirectory.toRealPath();
        worldsDirectory = worldsDirectory.toRealPath();
        backupsDirectory = backupsDirectory.toRealPath();
        if (!evidenceDirectory.startsWith(actualRoot) || !worldsDirectory.startsWith(actualRoot)
                || !backupsDirectory.startsWith(actualRoot) || overlaps(worldsDirectory, actualSaves)) {
            throw new IOException("verification paths escaped or overlap ordinary saves");
        }
        storage = new LevelStorageSource(worldsDirectory, backupsDirectory,
            client.directoryValidator(), client.getFixerUpper());
    }

    private static void createContainedDirectory(Path root, Path child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot) || normalizedChild.equals(normalizedRoot)) {
            throw new IOException("refusing a verifier path outside its root: " + normalizedChild);
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedChild)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("refusing linked or non-directory verifier path: " + current);
            }
        }
        Files.createDirectories(normalizedChild);
        if (!normalizedChild.toRealPath().startsWith(normalizedRoot.toRealPath())) {
            throw new IOException("verifier path resolves outside its root: " + normalizedChild);
        }
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private void tick(Minecraft currentClient) {
        if (terminal) return;
        if (finalizing) {
            pumpScreenshotQueue();
            advanceFinalization();
            return;
        }
        pumpScreenshotQueue();
        long now = System.nanoTime();
        if (state != State.RUNNING && now - initializedAtNanos > WORLD_INIT_TIMEOUT_NANOS) {
            fail("normal-world initialization exceeded 120 seconds");
            return;
        }
        if (commandStarted && now - commandStartedAtNanos > requestTimeoutSeconds * 1_000_000_000L) {
            fail("natural request exceeded its " + requestTimeoutSeconds + " second wall timeout");
            return;
        }
        try {
            if (state == State.OPENING_WORLD) {
                if (client.level != null) throw new IllegalStateException("start from the title screen; an existing world is active");
                if (!worldLaunchQueued && GameApi.screen(client) != null) queueWorldLaunch();
                return;
            }
            if (state == State.WAITING_FOR_WORLD) {
                if (client.level != null && client.player != null) {
                    playerId = client.player.getUUID();
                    if (GameApi.screen(client) == null) {
                        transitionTo(State.WAITING_FOR_PLAYER);
                        worldReadyAtNanos = System.nanoTime();
                    }
                }
                return;
            }
            if (state == State.WAITING_FOR_PLAYER) {
                if (client.level == null || client.player == null || GameApi.screen(client) != null
                        || LodekeeperClient.engine == null) return;
                if (++readyTicks < WORLD_READY_TICKS) return;
                ServerObservation observation = latestObservation;
                if (observation == null) return;
                if (!"NORMAL".equals(observation.difficulty)) {
                    throw new IllegalStateException("server world difficulty is not Normal: " + observation.difficulty);
                }
                if (observation.creative || observation.spectator) {
                    throw new IllegalStateException("server player is not in Survival mode");
                }
                if (!observation.heldCounts.isEmpty() || observation.cursorCount != 0) {
                    throw new IllegalStateException("fresh natural world did not start with an empty inventory and cursor");
                }
                if (observation.health <= 0.0F || deathsObserved != 0) {
                    throw new IllegalStateException("player was not alive at request start");
                }
                issueCommand(observation);
                transitionTo(State.RUNNING);
                return;
            }
            if (state == State.RUNNING) {
                if (Files.exists(verificationRoot.resolve("stop.txt"))) {
                    fail("verification stopped by operator after observing a defect");
                    return;
                }
                ServerObservation observation = latestObservation;
                if (observation == null) return;
                if (!"NORMAL".equals(observation.difficulty) || observation.creative || observation.spectator) {
                    fail("world settings changed during the natural request: difficulty=" + observation.difficulty
                        + ", creative=" + observation.creative + ", spectator=" + observation.spectator);
                    return;
                }
                if (observation.deaths > commandDeaths || observation.health <= 0) {
                    fail("player died during the natural request");
                    return;
                }
                String taskPhase = LodekeeperClient.engine.visualizationDetail().strip()
                        .split("[ :·]", 2)[0].toLowerCase(java.util.Locale.ROOT);
                if (!taskPhase.equals(lastTaskScreenshotPhase)) {
                    lastTaskScreenshotPhase = taskPhase;
                    if (!"gear_diamond".equals(goal)) requestScreenshot("task-" + taskPhase, false);
                }
                if (LodekeeperClient.engine.visualizationPaused()) {
                    fail("automation paused: " + LodekeeperClient.engine.status());
                    return;
                }
                var pathing = dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getProvider().getPrimaryBaritone().getPathingBehavior();
                boolean navigationStopped = !pathing.hasPath() && !pathing.isPathing()
                    && pathing.getInProgress().isEmpty();
                boolean satisfied = goalSatisfied(observation) && observation.cursorCount == 0
                    && LodekeeperClient.engine.status().startsWith("idle") && navigationStopped;
                if (satisfied) {
                    if (satisfiedSinceServerTick < 0) satisfiedSinceServerTick = observation.serverTick;
                    if (observation.serverTick - satisfiedSinceServerTick >= COMPLETE_STABILITY_TICKS) {
                        result = "passed";
                        finish();
                    }
                } else {
                    satisfiedSinceServerTick = -1;
                }
            }
        } catch (Exception exception) {
            fail("verification error: " + exception);
        }
    }

    private void queueWorldLaunch() {
        worldLaunchQueued = true;
        transitionTo(State.WAITING_FOR_WORLD);
        worldLaunchStartedAtNanos = System.nanoTime();
        net.minecraft.util.Util.backgroundExecutor().execute(() -> client.execute(() -> {
            if (state != State.WAITING_FOR_WORLD || worldCreationStarted || terminal) return;
            worldCreationStarted = true;
            try {
                VerificationApi.startNormalWorld(client, worldId, storage, Long.toString(seed),
                    () -> System.out.println("[Lodekeeper natural verification] Normal integrated-world startup requested"),
                    throwable -> client.execute(() -> fail("normal-world creation failed: " + throwable)));
            } catch (Throwable throwable) {
                fail("could not start normal world: " + throwable);
            }
        }));
    }

    private void issueCommand(ServerObservation observation) {
        command = goal.equals("gear_diamond") ? "!lk project gear_diamond" : "!lk get " + goal;
        if (client.getConnection() == null) throw new IllegalStateException("integrated client is not connected");
        commandX = observation.x;
        commandY = observation.y;
        commandZ = observation.z;
        commandDeaths = deathsObserved;
        commandServerTick = observation.serverTick;
        commandStartedAtNanos = System.nanoTime();
        commandStarted = true;
        client.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal("[Verification] " + command));
        client.getConnection().sendChat(command);
        System.out.println("[Lodekeeper natural verification] Sent " + command + " at server tick " + commandServerTick);
    }

    private void serverTick(MinecraftServer server) {
        if (terminal || finalizing || playerId == null || server != client.getSingleplayerServer()) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        ServerLevel world = player.level();

        Map<String, Integer> inventoryCounts = new HashMap<>();
        Set<String> logItems = new HashSet<>();
        List<EquipmentSlot> equipmentSlots = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) countStack(stack, inventoryCounts, logItems);
        countStack(player.getItemBySlot(EquipmentSlot.OFFHAND), inventoryCounts, logItems);
        Map<String, Integer> armorCounts = new HashMap<>();
        for (EquipmentSlot slot : equipmentSlots) countStack(player.getItemBySlot(slot), armorCounts, logItems);
        Map<String, Integer> heldCounts = new HashMap<>(inventoryCounts);
        armorCounts.forEach((item, count) -> heldCounts.merge(item, count, Integer::sum));
        ItemStack cursor = player.containerMenu.getCarried();
        String cursorItem = cursor.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(cursor.getItem()).toString();
        int cursorCount = cursor.isEmpty() ? 0 : cursor.getCount();

        if (commandStarted) {
            recordReceipts(server.getTickCount(), heldCounts, logItems);
            if (firstServerMovementMillis < 0 && movedFromCommand(player)) {
                firstServerMovementMillis = (int) Math.max(0, (System.nanoTime() - commandStartedAtNanos) / 1_000_000L);
                requestScreenshot("first-movement", false);
            }
            if (!Double.isNaN(previousX)) {
                double dx = player.getX() - previousX;
                double dy = player.getY() - previousY;
                double dz = player.getZ() - previousZ;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance > 0.03) {
                    serverMovingTicks++;
                    serverDistanceMeters += distance;
                }
            }
            minimumHealth = Math.min(minimumHealth, player.getHealth());
            minimumHunger = Math.min(minimumHunger, player.getFoodData().getFoodLevel());
        }
        previousX = player.getX();
        previousY = player.getY();
        previousZ = player.getZ();
        previousHeldCounts = Map.copyOf(heldCounts);
        latestObservation = new ServerObservation(server.getTickCount(), Map.copyOf(inventoryCounts), Map.copyOf(armorCounts),
            Map.copyOf(heldCounts), Set.copyOf(logItems), cursorItem, cursorCount, player.getHealth(),
            player.getFoodData().getFoodLevel(), deathsObserved,
            world.getDifficulty().name(), player.getAbilities().instabuild, invokeBoolean(player, "isSpectator"),
            player.getX(), player.getY(), player.getZ(), firstServerMovementMillis,
            serverMovingTicks, serverDistanceMeters, minimumHealth, minimumHunger);
    }

    private static boolean invokeBoolean(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return Boolean.TRUE.equals(method.invoke(target));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void countStack(ItemStack stack, Map<String, Integer> counts, Set<String> logItems) {
        if (stack.isEmpty()) return;
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        counts.merge(item, stack.getCount(), Integer::sum);
        if (stack.is(ItemTags.LOGS)) logItems.add(item);
    }

    private boolean movedFromCommand(ServerPlayer player) {
        double dx = player.getX() - commandX;
        double dy = player.getY() - commandY;
        double dz = player.getZ() - commandZ;
        return dx * dx + dy * dy + dz * dz > 0.0025;
    }

    private void recordReceipts(int serverTick, Map<String, Integer> heldCounts, Set<String> logItems) {
        Map<String, Integer> before = previousHeldCounts;
        for (Map.Entry<String, Integer> entry : heldCounts.entrySet()) {
            int increase = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
            if (increase <= 0) continue;
            synchronized (serverTargetReceipts) {
                if (serverTargetReceipts.size() >= MAX_RECEIPTS) {
                    serverReceiptsDropped++;
                    continue;
                }
                JsonObject receipt = new JsonObject();
                receipt.addProperty("serverTick", serverTick);
                receipt.addProperty("elapsedMillisFromCommand",
                    Math.max(0, (System.nanoTime() - commandStartedAtNanos) / 1_000_000L));
                receipt.addProperty("item", entry.getKey());
                receipt.addProperty("countIncrease", increase);
                receipt.addProperty("heldCountAfterReceipt", entry.getValue());
                receipt.addProperty("requestedTargetItem", isGoalItem(entry.getKey(), logItems));
                receipt.addProperty("authority", "integrated_server_inventory_diff");
                serverTargetReceipts.add(receipt);
                if ("gear_diamond".equals(goal) && DIAMOND_GEAR_IDS.contains(entry.getKey()))
                    requestScreenshot("received-" + entry.getKey().substring(entry.getKey().indexOf(':') + 1), false);
            }
        }
    }

    private boolean goalSatisfied(ServerObservation observation) {
        return switch (goal) {
            case "wood" -> observation.logItems.stream().anyMatch(item -> observation.heldCounts.getOrDefault(item, 0) > 0);
            case "iron_pickaxe" -> observation.heldCounts.getOrDefault(IRON_PICKAXE_ID, 0) >= 1;
            case "gear_diamond" -> DIAMOND_GEAR_IDS.stream()
                .allMatch(item -> observation.heldCounts.getOrDefault(item, 0) >= 1);
            default -> false;
        };
    }

    private boolean isGoalItem(String item, Set<String> logItems) {
        return switch (goal) {
            case "wood" -> logItems.contains(item);
            case "iron_pickaxe" -> IRON_PICKAXE_ID.equals(item);
            case "gear_diamond" -> DIAMOND_GEAR_IDS.contains(item);
            default -> false;
        };
    }

    private void finish() {
        if (terminal || finalizing) return;
        finalizing = true;
        state = State.TERMINAL;
        requestScreenshot("final", true);
        if (evidenceDirectory == null) {
            finalScreenshotSettled = true;
            completeFinalization();
        }
    }

    private void completeFinalization() {
        if (terminal) return;
        terminal = true;
        finalizing = false;
        evidenceWritten = true;
        writeEvidence();
        System.out.println("[Lodekeeper natural verification] " + result + "; evidence: " + evidenceDirectory);
        client.stop();
    }

    private void fail(String reason) {
        if (terminal || finalizing) return;
        failure = reason;
        result = "failed";
        finish();
    }

    private void transitionTo(State next) {
        if (state == next) return;
        state = next;
        if (next == State.RUNNING)
            requestScreenshot("phase-" + next.name().toLowerCase(java.util.Locale.ROOT), false);
    }

    private void requestScreenshot(String name, boolean finalCapture) {
        if (evidenceDirectory == null) {
            if (finalCapture) finalScreenshotSettled = true;
            return;
        }
        synchronized (screenshotQueueLock) {
            if (terminal || finalizing && !finalCapture) return;
            if (finalCapture && finalScreenshotQueued) return;
            if (!scheduledScreenshotNames.add(name)) return;
            int limit = finalCapture ? MAX_SCREENSHOTS : MAX_SCREENSHOTS - 1;
            if (screenshotSlotsReserved >= limit) {
                System.err.println("[Lodekeeper natural verification] Screenshot limit reached; omitted " + name);
                if (finalCapture) finalScreenshotSettled = true;
                return;
            }
            screenshotSlotsReserved++;
            screenshotQueue.addLast(new ScreenshotRequest(name, finalCapture));
            if (finalCapture) finalScreenshotQueued = true;
        }
    }

    private void pumpScreenshotQueue() {
        ScreenshotRequest request;
        synchronized (screenshotQueueLock) {
            request = screenshotQueue.peekFirst();
        }
        if (request == null) return;
        long now = System.nanoTime();
        if (lastScreenshotStartedAtNanos != 0
                && now - lastScreenshotStartedAtNanos < SCREENSHOT_INTERVAL_NANOS) return;
        synchronized (screenshotQueueLock) {
            request = screenshotQueue.pollFirst();
        }
        if (request == null) return;
        lastScreenshotStartedAtNanos = now;
        if (request.finalCapture()) {
            finalScreenshotStarted = true;
            finalScreenshotStartedAtNanos = now;
        }
        captureScreenshot(request);
    }

    private void captureScreenshot(ScreenshotRequest request) {
        String fileName = "lodekeeper-" + runId + "-" + request.name() + ".png";
        screenshotWritesPending++;
        AtomicBoolean completionClaimed = new AtomicBoolean();
        try {
            Screenshot.grab(evidenceDirectory.toFile(), fileName, (RenderTarget) getMainRenderTarget(), 1, message -> {
                if (completionClaimed.compareAndSet(false, true)) verifyScreenshotAsync(request, fileName);
            });
        } catch (Exception exception) {
            if (completionClaimed.compareAndSet(false, true)) {
                screenshotWritesPending = Math.max(0, screenshotWritesPending - 1);
                if (request.finalCapture()) finalScreenshotSettled = true;
                System.err.println("[Lodekeeper natural verification] Native screenshot unavailable for "
                    + request.name() + ": " + exception.getMessage());
            }
        }
    }

    private void verifyScreenshotAsync(ScreenshotRequest request, String fileName) {
        Path screenshot = evidenceDirectory.resolve("screenshots").resolve(fileName);
        CompletableFuture.supplyAsync(() -> readablePng(screenshot)).whenComplete((readable, failure) ->
            client.execute(() -> {
                screenshotWritesPending = Math.max(0, screenshotWritesPending - 1);
                if (Boolean.TRUE.equals(readable) && failure == null && !evidenceWritten) {
                    screenshotFiles.add(evidenceDirectory.relativize(screenshot).toString()
                        .replace(File.separatorChar, '/'));
                } else {
                    System.err.println("[Lodekeeper natural verification] Screenshot was not a readable PNG: " + fileName);
                }
                if (request.finalCapture()) finalScreenshotSettled = true;
            }));
    }

    private static boolean readablePng(Path screenshot) {
        try {
            return Files.isRegularFile(screenshot) && Files.size(screenshot) > 0
                && ImageIO.read(screenshot.toFile()) != null;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void advanceFinalization() {
        if (finalScreenshotSettled && screenshotWritesPending == 0) {
            completeFinalization();
            return;
        }
        if (finalScreenshotStarted
                && System.nanoTime() - finalScreenshotStartedAtNanos >= FINAL_SCREENSHOT_SAVE_GRACE_NANOS) {
            if (screenshotWritesPending != 0) {
                System.err.println("[Lodekeeper natural verification] Screenshot save grace expired with "
                    + screenshotWritesPending + " pending write(s)");
            }
            completeFinalization();
        }
    }

    private void writeEvidence() {
        if (evidenceDirectory == null) {
            System.err.println("[Lodekeeper natural verification] Cannot write evidence: output directory was not prepared");
            return;
        }
        try {
            JsonObject evidence = new JsonObject();
            evidence.addProperty("runId", runId);
            evidence.addProperty("worldId", worldId);
            evidence.addProperty("status", result);
            evidence.addProperty("verificationMode", "natural_world");
            evidence.addProperty("evidenceAuthority", "integrated_server_ticks_and_inventory");
            evidence.addProperty("goal", goal == null ? "" : goal);
            evidence.addProperty("command", command == null ? "" : command);
            evidence.addProperty("minecraftVersion", VerificationApi.minecraftVersion());
            evidence.addProperty("worldKind", "normal_generated_world");
            evidence.addProperty("worldPreset", "minecraft:normal");
            evidence.addProperty("worldSeed", seed);
            evidence.addProperty("gameMode", "survival");
            evidence.addProperty("difficulty", "normal");
            evidence.addProperty("cheatsEnabled", false);
            evidence.addProperty("bonusChestEnabled", false);
            evidence.addProperty("structuresEnabled", true);
            evidence.addProperty("worldInitializationTimeoutSeconds", WORLD_INIT_TIMEOUT_NANOS / 1_000_000_000L);
            evidence.addProperty("requestTimeoutSeconds", requestTimeoutSeconds);
            evidence.addProperty("initializedElapsedMillis", elapsedMillis(initializedAtNanos));
            evidence.addProperty("worldLaunchElapsedMillis", worldLaunchStartedAtNanos == 0 ? -1 : elapsedMillis(worldLaunchStartedAtNanos));
            evidence.addProperty("worldReadyElapsedMillis", worldReadyAtNanos == 0 ? -1 : elapsedMillis(worldReadyAtNanos));
            evidence.addProperty("commandElapsedMillis", commandStarted ? elapsedMillis(commandStartedAtNanos) : -1);
            evidence.addProperty("firstServerMovementElapsedMillis", latestObservation == null
                ? firstServerMovementMillis : latestObservation.firstMovementMillis);
            evidence.addProperty("commandServerTick", commandServerTick);
            evidence.addProperty("serverMovementTicks", latestObservation == null ? serverMovingTicks : latestObservation.movingTicks);
            evidence.addProperty("serverMovementSteps", latestObservation == null ? serverMovingTicks : latestObservation.movingTicks);
            evidence.addProperty("serverMovementStepDefinition", "server ticks with more than 0.03 blocks of displacement");
            evidence.addProperty("serverDistanceMeters", latestObservation == null ? serverDistanceMeters : latestObservation.distanceMeters);
            evidence.addProperty("deathsObserved", deathsObserved);
            evidence.addProperty("minimumHealth", latestObservation == null ? minimumHealth : latestObservation.minimumHealth);
            evidence.addProperty("minimumHunger", latestObservation == null ? minimumHunger : latestObservation.minimumHunger);
            evidence.addProperty("failure", failure);
            evidence.addProperty("engineStatus", LodekeeperClient.engine == null ? "unavailable" : LodekeeperClient.engine.status());
            evidence.addProperty("successRequiresIdleAndCancelledNavigation", true);
            evidence.addProperty("candidateSha256", System.getProperty("lodekeeper.verify.candidateSha256", ""));
            evidence.addProperty("renderDistanceChunks", optionInteger("getViewDistance", "getRenderDistance"));
            evidence.addProperty("maxFps", optionInteger("getMaxFps", "getFramerateLimit"));
            evidence.addProperty("framebufferWidth", renderTargetDimension("width"));
            evidence.addProperty("framebufferHeight", renderTargetDimension("height"));
            if (commandStarted) {
                JsonObject startPosition = new JsonObject();
                startPosition.addProperty("x", commandX);
                startPosition.addProperty("y", commandY);
                startPosition.addProperty("z", commandZ);
                evidence.add("commandStartPosition", startPosition);
            }
            evidence.add("expectedItems", expectedItems);
            JsonArray verifiedScreenshots = new JsonArray();
            screenshotFiles.forEach(verifiedScreenshots::add);
            evidence.add("visualScreenshots", verifiedScreenshots);
            ServerObservation observation = latestObservation;
            if (observation != null) {
                evidence.addProperty("serverDifficulty", observation.difficulty);
                evidence.addProperty("serverSurvival", !observation.creative && !observation.spectator);
                evidence.addProperty("serverCreativeAbility", observation.creative);
                evidence.addProperty("serverSpectator", observation.spectator);
                evidence.addProperty("serverHealth", observation.health);
                evidence.addProperty("serverHunger", observation.hunger);
                evidence.addProperty("serverDeaths", observation.deaths);
                evidence.addProperty("serverTick", observation.serverTick);
                evidence.add("serverInventoryCounts", stringMap(observation.inventoryCounts));
                evidence.add("serverArmorCounts", stringMap(observation.armorCounts));
                evidence.add("serverHeldCounts", stringMap(observation.heldCounts));
                evidence.add("serverLogItems", stringSet(observation.logItems));
                JsonObject cursor = new JsonObject();
                cursor.addProperty("item", observation.cursorItem);
                cursor.addProperty("count", observation.cursorCount);
                evidence.add("serverCursor", cursor);
                evidence.addProperty("goalSatisfied", goalSatisfied(observation));
            } else {
                evidence.addProperty("goalSatisfied", false);
            }
            JsonArray receipts = new JsonArray();
            long receiptsDropped;
            synchronized (serverTargetReceipts) {
                serverTargetReceipts.forEach(receipts::add);
                receiptsDropped = serverReceiptsDropped;
            }
            evidence.add("serverTargetReceipts", receipts);
            evidence.addProperty("serverTargetReceiptsDropped", receiptsDropped);

            String filename = "natural-" + runId + ".json";
            Path output = evidenceDirectory.resolve(filename);
            Path temporary = evidenceDirectory.resolve(filename + ".tmp");
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(evidence);
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            System.err.println("[Lodekeeper natural verification] Evidence write failed: " + exception);
        }
    }

    private Integer optionInteger(String... getters) {
        for (String getter : getters) {
            try {
                Object option = client.options.getClass().getMethod(getter).invoke(client.options);
                Object value = option.getClass().getMethod("getValue").invoke(option);
                if (value instanceof Number number) return number.intValue();
            } catch (ReflectiveOperationException ignored) {
                // Try the accessor used by this game profile.
            }
        }
        return null;
    }

    private Integer renderTargetDimension(String fieldName) {
        try {
            Object target = getMainRenderTarget();
            Field field = target.getClass().getField(fieldName);
            return field.getInt(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Object getMainRenderTarget() throws ReflectiveOperationException {
        try {
            Method getter = Minecraft.class.getDeclaredMethod("getMainRenderTarget");
            getter.setAccessible(true);
            return getter.invoke(client);
        } catch (NoSuchMethodException ignored) {
            Field rendererField = Minecraft.class.getDeclaredField("gameRenderer");
            rendererField.setAccessible(true);
            Object renderer = rendererField.get(client);
            Method getter = renderer.getClass().getDeclaredMethod("mainRenderTarget");
            getter.setAccessible(true);
            return getter.invoke(renderer);
        }
    }

    private JsonObject stringMap(Map<String, Integer> values) {
        JsonObject object = new JsonObject();
        values.forEach(object::addProperty);
        return object;
    }

    private JsonArray stringSet(Set<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private record ScreenshotRequest(String name, boolean finalCapture) { }

    private record ServerObservation(int serverTick, Map<String, Integer> inventoryCounts,
                                     Map<String, Integer> armorCounts, Map<String, Integer> heldCounts,
                                     Set<String> logItems, String cursorItem, int cursorCount,
                                     float health, int hunger, int deaths,
                                     String difficulty, boolean creative, boolean spectator,
                                     double x, double y, double z, int firstMovementMillis,
                                     long movingTicks, double distanceMeters, float minimumHealth,
                                     int minimumHunger) { }
}
