package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ClaimBox;
import dev.lodekeeper.core.ClaimSnapshot;
import dev.lodekeeper.core.CommandParser;
import dev.lodekeeper.core.WorldScope;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

final class WorldProtection {
    record PolicySnapshot(WorldScope scope, ClaimSnapshot claims, boolean locked,
                          boolean allowBreak, boolean allowPlace, long epoch) {
        PolicySnapshot {
            Objects.requireNonNull(claims, "claims");
            if (scope == null) locked = true;
        }

        boolean mayBreak(int x, int y, int z) {
            return !locked && allowBreak && !claims.forbidsEdit(scope, x, y, z, ClaimSnapshot.ActionKind.BREAK);
        }

        boolean mayPlace(int x, int y, int z) {
            return !locked && allowPlace && !claims.forbidsEdit(scope, x, y, z, ClaimSnapshot.ActionKind.PLACE);
        }

        boolean preferredStation(int x, int y, int z) {
            return scope != null && !locked && claims.preferredStation(scope, x, y, z);
        }
    }

    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final ClaimStore store;
    private ClientWorld boundWorld;
    private WorldScope scope;
    private BlockPos pos1;
    private BlockPos pos2;
    private long epoch;
    private dev.lodekeeper.navigation.kernel.OwnedKernelRuntime publishedRuntime;
    private dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session publishedSession;
    private long publishedEpoch = -1;
    private volatile PolicySnapshot policy = new PolicySnapshot(null, new ClaimSnapshot(List.of()), true, false, false, 0);

    WorldProtection(MinecraftClient client, LodekeeperConfig config, ClaimStore store) {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
    }

    void sync() {
        requireMainThread();
        boolean changedWorld = boundWorld != client.world;
        if (changedWorld) {
            boundWorld = client.world;
            pos1 = null;
            pos2 = null;
            scope = resolveScope();
        }
        ClaimStore.View view = store.view();
        boolean locked = scope == null || view.locked();
        PolicySnapshot previous = policy;
        if (changedWorld || previous.claims() != view.claims() || previous.locked() != locked
                || previous.allowBreak() != config.allowBreaking || previous.allowPlace() != config.allowBuilding) {
            policy = new PolicySnapshot(scope, view.claims(), locked, config.allowBreaking, config.allowBuilding, ++epoch);
        }
        publishKernelPolicy();
    }

    private void publishKernelPolicy() {
        var runtime = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        if (runtime == null || runtime.isClosed()) return;
        var session = runtime.captureSession();
        if (runtime == publishedRuntime && session == publishedSession && policy.epoch() == publishedEpoch) return;
        dev.lodekeeper.navigation.kernel.WorldEditPolicy next = dev.lodekeeper.navigation.kernel.WorldEditPolicy.denyAll();
        if (runtime.isCurrent(session) && session.world() == boundWorld && !policy.locked()) {
            var regions = policy.claims().forScope(policy.scope()).stream()
                    .map(claim -> new dev.lodekeeper.navigation.kernel.WorldEditPolicySnapshot.BlockRegion(
                            claim.minX(), claim.minY(), claim.minZ(), claim.maxX(), claim.maxY(), claim.maxZ())).toList();
            var everywhere = List.of(new dev.lodekeeper.navigation.kernel.WorldEditPolicySnapshot.BlockRegion(
                    Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
            next = new dev.lodekeeper.navigation.kernel.WorldEditPolicySnapshot(
                    policy.allowBreak() ? regions : everywhere, policy.allowPlace() ? regions : everywhere);
        }
        runtime.updateWorldEditPolicy(next);
        publishedRuntime = runtime;
        publishedSession = session;
        publishedEpoch = policy.epoch();
    }

    PolicySnapshot capture() {
        sync();
        return policy;
    }

    boolean mayInteractEntity(net.minecraft.entity.Entity entity) {
        sync();
        PolicySnapshot current = policy;
        if (entity == null || boundWorld == null || client.world != boundWorld || current.locked()
                || current.scope() == null || boundWorld.getEntityById(entity.getId()) != entity) return false;
        var box = entity.getBoundingBox();
        if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)
                || box.maxX - box.minX > 8 || box.maxY - box.minY > 8 || box.maxZ - box.minZ > 8) return false;
        for (ClaimBox claim : current.claims().forScope(current.scope())) {
            if (box.maxX > claim.minX() && box.minX < (double) claim.maxX() + 1
                    && box.maxY > claim.minY() && box.minY < (double) claim.maxY() + 1
                    && box.maxZ > claim.minZ() && box.minZ < (double) claim.maxZ() + 1) return false;
        }
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++)
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                BlockPos position = new BlockPos(x, (int) Math.floor(box.minY), z);
                if (!boundWorld.isChunkLoaded(position)) return false;
            }
        return true;
    }

    boolean mayInteractBlock(BlockPos position) {
        sync();
        return position != null && boundWorld != null && client.world == boundWorld
                && boundWorld.isChunkLoaded(position) && policy.mayPlace(position.getX(), position.getY(), position.getZ());
    }

    boolean mayPickupDrop(net.minecraft.entity.ItemEntity item) { return mayInteractEntity(item); }

    boolean mayBreak(BlockPos position) {
        return mayEdit(position, true);
    }

    boolean mayPlace(BlockPos position) {
        return mayEdit(position, false);
    }

    boolean preferredStation(BlockPos position) {
        sync();
        return currentPosition(position) && policy.preferredStation(position.getX(), position.getY(), position.getZ());
    }

    private boolean mayEdit(BlockPos position, boolean breaking) {
        sync();
        if (!currentPosition(position)) return false;
        if (breaking && !config.allowBreaking || !breaking && !config.allowBuilding) return false;
        PolicySnapshot current = policy;
        return breaking ? current.mayBreak(position.getX(), position.getY(), position.getZ())
                : current.mayPlace(position.getX(), position.getY(), position.getZ());
    }

    private boolean currentPosition(BlockPos position) {
        return position != null && boundWorld != null && client.world == boundWorld && scope != null
                && !boundWorld.isOutOfHeightLimit(position) && boundWorld.isChunkLoaded(position);
    }

    String execute(CommandParser.ClaimCommand command) {
        Objects.requireNonNull(command, "command");
        sync();
        if (command.action() == CommandParser.ClaimAction.CLEAR_SELECTION) return clearSelection();
        if (scope == null) return "World identity is unavailable. Automated block edits are paused.";
        return switch (command.action()) {
            case POS1 -> selectPos1();
            case POS2 -> selectPos2();
            case ADD -> createClaim(command.name(), command.preferred());
            case LIST -> listClaims();
            case REMOVE -> removeClaim(command.name());
            case PREFER -> setPreferred(command.name(), command.preferred());
            case CLEAR_SELECTION -> clearSelection();
        };
    }

    int[] selectionCoordinates(boolean first) {
        sync();
        BlockPos position = first ? pos1 : pos2;
        return position == null ? null : new int[]{position.getX(), position.getY(), position.getZ()};
    }

    String setCorner(boolean first, int x, int y, int z) {
        sync();
        if (scope == null) return "Join a world before selecting a claim corner.";
        BlockPos position = new BlockPos(x, y, z);
        if (first) pos1 = position;
        else pos2 = position;
        return "Claim pos" + (first ? "1" : "2") + " set to " + coordinates(position) + ".";
    }

    String selectPos1() { return select(true); }
    String selectPos2() { return select(false); }

    private String select(boolean first) {
        sync();
        if (scope == null || client.player == null) return "Join a world before selecting a claim corner.";
        BlockPos position = client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos() : client.player.getBlockPos();
        if (!currentPosition(position)) return "The selected block is not loaded.";
        position = position.toImmutable();
        if (first) pos1 = position;
        else pos2 = position;
        return "Claim pos" + (first ? "1" : "2") + " set to " + coordinates(position) + ".";
    }

    String clearSelection() {
        requireMainThread();
        pos1 = null;
        pos2 = null;
        return "Claim selection cleared.";
    }

    String createClaim(String name, boolean preferred) {
        sync();
        String refusal = mutationRefusal();
        if (refusal != null) return refusal;
        if (pos1 == null || pos2 == null) return "Select both corners with claim pos1 and claim pos2 first.";
        String normalized = validateName(name);
        if (normalized == null) return "Claim name must be 1..128 characters without control characters.";
        ClaimSnapshot claims = policy.claims();
        if (claims.all().size() >= ClaimSnapshot.MAX_CLAIMS) return "The global limit of 128 claims has been reached.";
        if (claims.forScope(scope).stream().anyMatch(claim -> claim.name().equalsIgnoreCase(normalized)))
            return "A claim with that name already exists in this dimension.";
        var next = new ArrayList<>(claims.all());
        next.add(ClaimBox.create(UUID.randomUUID().toString(), normalized, scope,
                pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX(), pos2.getY(), pos2.getZ(), preferred));
        return save(next, "Claim \"" + normalized + "\" created.");
    }

    String listClaims() {
        sync();
        String refusal = mutationRefusal();
        if (refusal != null) return refusal;
        List<ClaimBox> current = policy.claims().forScope(scope);
        if (current.isEmpty()) return "No claims in this dimension.";
        var result = new StringBuilder("Claims in this dimension:");
        for (ClaimBox claim : current) {
            result.append("\n").append(claim.name()).append(" [").append(claim.id()).append("] ")
                    .append(claim.minX()).append(", ").append(claim.minY()).append(", ").append(claim.minZ())
                    .append(" to ").append(claim.maxX()).append(", ").append(claim.maxY()).append(", ").append(claim.maxZ());
            if (claim.preferredStations()) result.append("; preferred stations");
        }
        return result.toString();
    }

    String removeClaim(String nameOrId) {
        sync();
        String refusal = mutationRefusal();
        if (refusal != null) return refusal;
        ClaimBox target = findClaim(nameOrId);
        if (target == null) return "Claim not found, or the name matches more than one claim in this dimension.";
        var next = new ArrayList<>(policy.claims().all());
        next.remove(target);
        return save(next, "Claim \"" + target.name() + "\" removed.");
    }

    String setPreferred(String nameOrId, boolean preferred) {
        sync();
        String refusal = mutationRefusal();
        if (refusal != null) return refusal;
        ClaimBox target = findClaim(nameOrId);
        if (target == null) return "Claim not found, or the name matches more than one claim in this dimension.";
        if (target.preferredStations() == preferred) return "Claim \"" + target.name() + "\" already has preferred stations " + preferred + ".";
        var next = new ArrayList<>(policy.claims().all());
        next.set(next.indexOf(target), new ClaimBox(target.id(), target.name(), target.scope(), target.minX(), target.minY(),
                target.minZ(), target.maxX(), target.maxY(), target.maxZ(), preferred));
        return save(next, "Claim \"" + target.name() + "\" preferred stations set to " + preferred + ".");
    }

    private ClaimBox findClaim(String nameOrId) {
        String normalized = validateName(nameOrId);
        if (normalized == null) return null;
        List<ClaimBox> claims = policy.claims().forScope(scope);
        for (ClaimBox claim : claims) if (claim.id().equals(normalized)) return claim;
        ClaimBox match = null;
        for (ClaimBox claim : claims) {
            if (!claim.name().equalsIgnoreCase(normalized)) continue;
            if (match != null) return null;
            match = claim;
        }
        return match;
    }

    private String save(List<ClaimBox> claims, String success) {
        try {
            store.replace(claims);
            sync();
            return success;
        } catch (IOException | IllegalArgumentException failure) {
            return "Claims could not be saved. Existing protection rules remain active.";
        }
    }

    private String mutationRefusal() {
        if (scope == null) return "World identity is unavailable. Automated block edits are paused.";
        if (policy.locked()) return "Claims could not be loaded. Automated block edits are paused.";
        return null;
    }

    private WorldScope resolveScope() {
        if (boundWorld == null) return null;
        try {
            var server = client.getServer();
            String source;
            if (server != null) {
                source = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).toRealPath().toString();
            } else {
                var remote = client.getCurrentServerEntry();
                if (remote == null || remote.address == null || remote.address.isBlank()) return null;
                source = remote.address.strip().toLowerCase(Locale.ROOT);
            }
            String worldId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            return new WorldScope(worldId, boundWorld.getRegistryKey().getValue().toString());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unresolved) {
            return null;
        }
    }

    private void requireMainThread() {
        if (!client.isOnThread()) throw new IllegalStateException("World protection requires the client thread");
    }

    private static String validateName(String name) {
        if (name == null) return null;
        String value = name.strip();
        return value.isEmpty() || value.length() > ClaimBox.MAX_NAME_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl) ? null : value;
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + ", " + position.getY() + ", " + position.getZ();
    }
}
