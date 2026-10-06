package dev.lodekeeper.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.stat.Stats;
import net.minecraft.registry.tag.ItemTags;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Optional real-world request verifier; it never edits the generated world. */
final class NaturalWorldVerification {
    private static final String GOAL_PROPERTY = "lodekeeper.verify.naturalGoal";
    private static final long WORLD_INIT_TIMEOUT_NANOS = 120_000_000_000L;
    private static final int MAX_REQUEST_TIMEOUT_SECONDS = 15 * 60;
    private static final int WORLD_READY_TICKS = 60;
    private static final int COMPLETE_STABILITY_TICKS = 20;
    private static final int MAX_RECEIPTS = 512;
    private static final String WOOD_TAG = "#minecraft:logs";
    private static final String IRON_PICKAXE_ID = "minecraft:iron_pickaxe";
    private static final List<String> DIAMOND_GEAR_IDS = List.of(
        "minecraft:diamond_pickaxe", "minecraft:diamond_axe", "minecraft:diamond_shovel",
        "minecraft:diamond_hoe", "minecraft:diamond_sword", "minecraft:diamond_helmet",
        "minecraft:diamond_chestplate", "minecraft:diamond_leggings", "minecraft:diamond_boots");

    private enum State { OPENING_WORLD, WAITING_FOR_WORLD, WAITING_FOR_PLAYER, RUNNING, TERMINAL }

    private final MinecraftClient client;
    private final String runId = Instant.now().toString().replace(':', '-') + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    private final long initializedAtNanos = System.nanoTime();
    private final List<JsonObject> serverTargetReceipts = new ArrayList<>();
    private final JsonArray expectedItems = new JsonArray();

    private volatile State state = State.OPENING_WORLD;
    private volatile UUID playerId;
    private volatile boolean commandStarted;
    private volatile boolean terminal;
    private volatile ServerObservation latestObservation;
    private Path verificationRoot;
    private Path evidenceDirectory;
    private Path worldsDirectory;
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
    private long commandWalkCentimeters;
    private int commandDeaths;
    private int readyTicks;
    private boolean worldLaunchQueued;
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

    private NaturalWorldVerification(MinecraftClient client) {
        this.client = client;
    }

    static void start(MinecraftClient client) {
        new NaturalWorldVerification(client).initialize();
    }

    private void initialize() {
        try {
            goal = System.getProperty(GOAL_PROPERTY, "");
            if (!List.of("wood", "iron_pickaxe", "gear_diamond").contains(goal)) {
                throw new IllegalArgumentException(GOAL_PROPERTY + " must be exactly wood, iron_pickaxe, or gear_diamond");
            }
            if (goal.equals("wood")) expectedItems.add(WOOD_TAG);
            else if (goal.equals("iron_pickaxe")) expectedItems.add(IRON_PICKAXE_ID);
            else DIAMOND_GEAR_IDS.forEach(expectedItems::add);
            seed = Long.parseLong(System.getProperty("lodekeeper.verify.naturalSeed", "483920105"));
            requestTimeoutSeconds = Integer.parseInt(System.getProperty("lodekeeper.verify.naturalTimeoutSeconds", "900"));
            if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > MAX_REQUEST_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("naturalTimeoutSeconds must be between 1 and "
                    + MAX_REQUEST_TIMEOUT_SECONDS);
            }
            prepareOutputDirectories();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
            ServerTickEvents.END_SERVER_TICK.register(this::serverTick);
            System.out.println("[Lodekeeper natural verification] Starting " + goal + " in a normal survival world; evidence under "
                + evidenceDirectory);
        } catch (Exception exception) {
            fail("initialization failed: " + exception);
        }
    }

    private void prepareOutputDirectories() throws IOException {
        Path runDirectory = client.runDirectory.toPath().toRealPath();
        Path requestedRoot = runDirectory.resolve("verification");
        Files.createDirectories(requestedRoot);
        verificationRoot = requestedRoot.toRealPath();
        if (!verificationRoot.startsWith(runDirectory)) {
            throw new IOException("verification output resolves outside the development run directory");
        }
        Path ordinarySaves = runDirectory.resolve("saves");
        Path actualSaves = Files.exists(ordinarySaves) ? ordinarySaves.toRealPath() : ordinarySaves.normalize();
        if (verificationRoot.startsWith(actualSaves) || actualSaves.startsWith(verificationRoot)) {
            throw new IOException("verification output overlaps the normal saves directory");
        }

        Path requestedEvidence = verificationRoot.resolve("evidence");
        Files.createDirectories(requestedEvidence);
        evidenceDirectory = requestedEvidence.toRealPath();
        if (!evidenceDirectory.startsWith(verificationRoot)) {
            throw new IOException("evidence output resolves outside the verification directory");
        }

        Path requestedWorlds = verificationRoot.resolve("worlds");
        Files.createDirectories(requestedWorlds);
        worldsDirectory = requestedWorlds.toRealPath();
        if (!worldsDirectory.startsWith(verificationRoot)
                || worldsDirectory.startsWith(actualSaves) || actualSaves.startsWith(worldsDirectory)) {
            throw new IOException("isolated world storage is outside verification or overlaps normal saves");
        }
    }

    private void tick(MinecraftClient currentClient) {
        if (terminal) return;
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
                if (client.getOverlay() != null) return;
                if (client.world != null) throw new IllegalStateException("start from the title screen; an existing world is active");
                VerificationApi.openCreateWorldScreen(client, client.currentScreen);
                state = State.WAITING_FOR_WORLD;
                return;
            }
            if (state == State.WAITING_FOR_WORLD) {
                if (client.world != null && client.player != null) {
                    playerId = client.player.getUuid();
                    state = State.WAITING_FOR_PLAYER;
                    worldReadyAtNanos = System.nanoTime();
                    return;
                }
                if (client.getOverlay() == null && client.currentScreen instanceof CreateWorldScreen createScreen
                        && !worldLaunchQueued) {
                    worldLaunchQueued = true;
                    worldLaunchStartedAtNanos = System.nanoTime();
                    WorldCreator creator = createScreen.getWorldCreator();
                    client.send(() -> {
                        try {
                            if (terminal) return;
                            if (client.getOverlay() != null || client.world != null || client.currentScreen != createScreen) {
                                throw new IllegalStateException("world creation screen changed before verifier startup");
                            }
                            startNormalWorld(creator);
                        } catch (Exception exception) {
                            client.execute(() -> fail("normal-world startup failed: " + exception));
                        }
                    });
                }
                return;
            }
            if (state == State.WAITING_FOR_PLAYER) {
                if (client.world == null || client.player == null) return;
                if (client.getOverlay() != null || client.currentScreen != null || LodekeeperClient.engine == null) return;
                if (++readyTicks < WORLD_READY_TICKS) return;
                ServerObservation observation = latestObservation;
                if (observation == null) return;
                if (!"survival".equals(observation.gameMode) || !"NORMAL".equals(observation.difficulty)) {
                    throw new IllegalStateException("server world is not Survival on Normal difficulty: mode="
                        + observation.gameMode + ", difficulty=" + observation.difficulty);
                }
                if (!observation.heldCounts.isEmpty() || observation.cursorCount != 0) {
                    throw new IllegalStateException("fresh natural world did not start with an empty inventory and cursor");
                }
                if (observation.health <= 0.0F) throw new IllegalStateException("player is not alive at request start");
                issueCommand(observation);
                state = State.RUNNING;
                return;
            }
            if (state == State.RUNNING) {
                if (Files.exists(verificationRoot.resolve("stop.txt"))) {
                    fail("verification stopped by operator after observing a defect");
                    return;
                }
                ServerObservation observation = latestObservation;
                if (observation == null) return;
                if (!"survival".equals(observation.gameMode) || !"NORMAL".equals(observation.difficulty)) {
                    fail("server mode or difficulty changed during the natural request");
                    return;
                }
                if (observation.deaths > commandDeaths || observation.health <= 0) {
                    fail("player died during the natural request");
                    return;
                }
                if (LodekeeperClient.engine.visualizationPaused()) {
                    fail("automation paused: " + LodekeeperClient.engine.status());
                    return;
                }
                var pathing = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior();
                boolean navigationStopped = !pathing.hasPath() && !pathing.isPathing()
                        && pathing.getInProgress().isEmpty();
                boolean satisfied = goalSatisfied(observation.heldCounts) && observation.cursorCount == 0
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

    private void startNormalWorld(WorldCreator creator) throws Exception {
        WorldCreator.WorldType normal = creator.getNormalWorldTypes().stream()
            .filter(type -> type.preset().matchesKey(WorldPresets.DEFAULT)).findFirst()
            .or(() -> creator.getExtendedWorldTypes().stream()
                .filter(type -> type.preset().matchesKey(WorldPresets.DEFAULT)).findFirst())
            .orElseThrow(() -> new IllegalStateException("the built-in normal world preset is unavailable"));
        String worldName = "Lodekeeper natural verification " + runId.substring(runId.length() - 8);
        creator.setWorldName(worldName);
        creator.setGameMode(WorldCreator.Mode.SURVIVAL);
        creator.setDifficulty(Difficulty.NORMAL);
        creator.setCheatsEnabled(false);
        creator.setGenerateStructures(true);
        creator.setBonusChestEnabled(false);
        creator.setSeed(Long.toString(seed));
        creator.setWorldType(normal);
        creator.update();
        if (!creator.getWorldType().preset().matchesKey(WorldPresets.DEFAULT)) {
            throw new IllegalStateException("could not select the built-in normal world preset");
        }

        var optionsHolder = creator.getGeneratorOptionsHolder();
        if (optionsHolder == null) throw new IllegalStateException("normal-world generator settings are not ready");
        LevelInfo levelInfo = new LevelInfo(worldName, GameMode.SURVIVAL, false, Difficulty.NORMAL,
            false, VerificationApi.gameRules(creator), optionsHolder.dataConfiguration());
        String saveName = "natural-" + runId.substring(runId.length() - 8);
        Path actualRunDirectory = client.runDirectory.toPath().toRealPath();
        Path ordinarySaves = actualRunDirectory.resolve("saves");
        Path actualSaves = Files.exists(ordinarySaves) ? ordinarySaves.toRealPath() : ordinarySaves.normalize();
        Path actualWorldsDirectory = worldsDirectory.toRealPath();
        if (!actualWorldsDirectory.startsWith(verificationRoot)
                || actualWorldsDirectory.startsWith(actualSaves) || actualSaves.startsWith(actualWorldsDirectory)) {
            throw new IOException("natural world storage no longer resolves to the isolated verifier directory");
        }

        client.setScreen(null);
        IntegratedServerLoader loader = new IntegratedServerLoader(client, LevelStorage.create(actualWorldsDirectory));
        try {
            Method startMethod = VerificationApi.class.getDeclaredMethod("startNormalWorld", IntegratedServerLoader.class,
                String.class, LevelInfo.class, GeneratorOptions.class);
            startMethod.setAccessible(true);
            startMethod.invoke(null, loader, saveName, levelInfo, optionsHolder.generatorOptions());
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("this legacy profile has no startNormalWorld verification helper", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            throw new IllegalStateException("normal-world loader failed", cause);
        }
    }

    private void issueCommand(ServerObservation observation) {
        command = goal.equals("gear_diamond") ? "!lk project gear_diamond" : "!lk get " + goal;
        ClientPlayNetworkHandler network = client.getNetworkHandler();
        if (network == null) throw new IllegalStateException("integrated client is not connected");
        commandX = observation.x;
        commandY = observation.y;
        commandZ = observation.z;
        commandDeaths = observation.deaths;
        commandWalkCentimeters = observation.walkCentimeters;
        commandServerTick = observation.serverTick;
        commandStartedAtNanos = System.nanoTime();
        commandStarted = true;
        network.sendChatMessage(command);
        System.out.println("[Lodekeeper natural verification] Sent " + command + " at server tick " + commandServerTick);
    }

    private void serverTick(MinecraftServer server) {
        if (terminal || playerId == null || server != client.getServer()) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) return;

        Map<String, Integer> inventoryCounts = new HashMap<>();
        PlayerInventory inventory = player.getInventory();
        countStacks(inventory.main, inventoryCounts);
        countStack(player.getEquippedStack(EquipmentSlot.OFFHAND), inventoryCounts);
        Map<String, Integer> armorCounts = new HashMap<>();
        countStack(player.getEquippedStack(EquipmentSlot.HEAD), armorCounts);
        countStack(player.getEquippedStack(EquipmentSlot.CHEST), armorCounts);
        countStack(player.getEquippedStack(EquipmentSlot.LEGS), armorCounts);
        countStack(player.getEquippedStack(EquipmentSlot.FEET), armorCounts);
        Map<String, Integer> heldCounts = new HashMap<>(inventoryCounts);
        armorCounts.forEach((item, count) -> heldCounts.merge(item, count, Integer::sum));
        ItemStack cursor = player.currentScreenHandler.getCursorStack();
        String cursorItem = cursor.isEmpty() ? "" : Registries.ITEM.getId(cursor.getItem()).toString();
        int cursorCount = cursor.isEmpty() ? 0 : cursor.getCount();
        String difficulty = player.getWorld().getDifficulty().name();
        String gameMode = player.interactionManager.getGameMode().getName();
        int deaths = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DEATHS));
        long walkCentimeters = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.WALK_ONE_CM));

        if (commandStarted) {
            recordReceipts(server.getTicks(), heldCounts);
            if (firstServerMovementMillis < 0 && movedFromCommand(player)) {
                firstServerMovementMillis = (int) Math.max(0, (System.nanoTime() - commandStartedAtNanos) / 1_000_000L);
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
        }
        previousX = player.getX();
        previousY = player.getY();
        previousZ = player.getZ();
        previousHeldCounts = Map.copyOf(heldCounts);
        latestObservation = new ServerObservation(server.getTicks(), Map.copyOf(inventoryCounts), Map.copyOf(armorCounts),
            Map.copyOf(heldCounts), cursorItem, cursorCount, player.getHealth(), player.getHungerManager().getFoodLevel(),
            deaths, walkCentimeters, difficulty, gameMode, player.getX(), player.getY(), player.getZ(),
            firstServerMovementMillis, serverMovingTicks, serverDistanceMeters, minimumHealth);
    }

    private boolean movedFromCommand(ServerPlayerEntity player) {
        double dx = player.getX() - commandX;
        double dy = player.getY() - commandY;
        double dz = player.getZ() - commandZ;
        return dx * dx + dy * dy + dz * dz > 0.0025;
    }

    private void recordReceipts(int serverTick, Map<String, Integer> heldCounts) {
        if (!commandStarted) return;
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
                receipt.addProperty("requestedTargetItem", isGoalItem(entry.getKey()));
                receipt.addProperty("authority", "integrated_server_inventory_diff");
                serverTargetReceipts.add(receipt);
            }
        }
    }

    private boolean goalSatisfied(Map<String, Integer> held) {
        return switch (goal) {
            case "wood" -> held.entrySet().stream().anyMatch(entry -> entry.getValue() > 0 && isLog(entry.getKey()));
            case "iron_pickaxe" -> held.getOrDefault(IRON_PICKAXE_ID, 0) >= 1;
            case "gear_diamond" -> DIAMOND_GEAR_IDS.stream().allMatch(item -> held.getOrDefault(item, 0) >= 1);
            default -> false;
        };
    }

    private boolean isGoalItem(String item) {
        return switch (goal) {
            case "wood" -> isLog(item);
            case "iron_pickaxe" -> IRON_PICKAXE_ID.equals(item);
            case "gear_diamond" -> DIAMOND_GEAR_IDS.contains(item);
            default -> false;
        };
    }

    private static boolean isLog(String item) {
        return new ItemStack(Registries.ITEM.get(GameApi.identifier(item))).isIn(ItemTags.LOGS);
    }

    private void finish() {
        if (terminal) return;
        terminal = true;
        state = State.TERMINAL;
        writeEvidence();
        System.out.println("[Lodekeeper natural verification] " + result + "; evidence: " + evidenceDirectory);
        if (client.isRunning()) client.scheduleStop();
    }

    private void fail(String reason) {
        if (terminal) return;
        failure = reason;
        result = "failed";
        finish();
    }

    private void writeEvidence() {
        if (evidenceDirectory == null) {
            System.err.println("[Lodekeeper natural verification] Cannot write evidence: output directory was not prepared");
            return;
        }
        try {
            JsonObject evidence = new JsonObject();
            evidence.addProperty("runId", runId);
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
            evidence.addProperty("firstServerMovementElapsedMillis", latestObservation == null ? firstServerMovementMillis
                : latestObservation.firstMovementMillis);
            evidence.addProperty("commandServerTick", commandServerTick);
            evidence.addProperty("serverMovementTicks", latestObservation == null ? serverMovingTicks
                : latestObservation.movingTicks);
            evidence.addProperty("serverMovementSteps", latestObservation == null ? serverMovingTicks
                : latestObservation.movingTicks);
            evidence.addProperty("serverMovementStepDefinition", "server ticks with more than 0.03 blocks of displacement");
            evidence.addProperty("serverDistanceMeters", latestObservation == null ? serverDistanceMeters
                : latestObservation.distanceMeters);
            evidence.addProperty("serverWalkCentimetersSinceCommand", latestObservation == null ? -1
                : Math.max(0, latestObservation.walkCentimeters - commandWalkCentimeters));
            evidence.addProperty("deathsAtCommand", commandDeaths);
            evidence.addProperty("deathsObserved", latestObservation == null ? -1
                : Math.max(0, latestObservation.deaths - commandDeaths));
            evidence.addProperty("minimumHealth", latestObservation == null ? minimumHealth : latestObservation.minimumHealth);
            evidence.addProperty("failure", failure);
            evidence.addProperty("engineStatus", LodekeeperClient.engine == null ? "unavailable" : LodekeeperClient.engine.status());
            evidence.addProperty("successRequiresIdleAndCancelledNavigation", true);
            evidence.addProperty("candidateSha256", System.getProperty("lodekeeper.verify.candidateSha256", ""));
            evidence.addProperty("renderDistanceChunks", optionInteger("getViewDistance"));
            evidence.addProperty("maxFps", optionInteger("getMaxFps"));
            evidence.addProperty("framebufferWidth", client.getWindow().getFramebufferWidth());
            evidence.addProperty("framebufferHeight", client.getWindow().getFramebufferHeight());
            if (commandStarted) {
                JsonObject startPosition = new JsonObject();
                startPosition.addProperty("x", commandX);
                startPosition.addProperty("y", commandY);
                startPosition.addProperty("z", commandZ);
                evidence.add("commandStartPosition", startPosition);
            }
            evidence.add("expectedItems", expectedItems);
            ServerObservation observation = latestObservation;
            if (observation != null) {
                evidence.addProperty("serverDifficulty", observation.difficulty);
                evidence.addProperty("serverGameMode", observation.gameMode);
                evidence.addProperty("serverHealth", observation.health);
                evidence.addProperty("serverHunger", observation.hunger);
                evidence.addProperty("serverTick", observation.serverTick);
                evidence.add("serverInventoryCounts", stringMap(observation.inventoryCounts));
                evidence.add("serverArmorCounts", stringMap(observation.armorCounts));
                evidence.add("serverHeldCounts", stringMap(observation.heldCounts));
                JsonObject cursor = new JsonObject();
                cursor.addProperty("item", observation.cursorItem);
                cursor.addProperty("count", observation.cursorCount);
                evidence.add("serverCursor", cursor);
                evidence.addProperty("goalSatisfied", goalSatisfied(observation.heldCounts));
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

    private Integer optionInteger(String getter) {
        try {
            Object option = client.options.getClass().getMethod(getter).invoke(client.options);
            Object value = option.getClass().getMethod("getValue").invoke(option);
            return value instanceof Number number ? number.intValue() : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static JsonObject stringMap(Map<String, Integer> values) {
        JsonObject object = new JsonObject();
        values.forEach(object::addProperty);
        return object;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static void countStacks(Iterable<ItemStack> stacks, Map<String, Integer> counts) {
        for (ItemStack stack : stacks) countStack(stack, counts);
    }

    private static void countStack(ItemStack stack, Map<String, Integer> counts) {
        if (!stack.isEmpty()) counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }

    private record ServerObservation(int serverTick, Map<String, Integer> inventoryCounts,
                                     Map<String, Integer> armorCounts, Map<String, Integer> heldCounts,
                                     String cursorItem, int cursorCount, float health, int hunger, int deaths,
                                     long walkCentimeters, String difficulty, String gameMode,
                                     double x, double y, double z, int firstMovementMillis,
                                     long movingTicks, double distanceMeters, float minimumHealth) { }
}
