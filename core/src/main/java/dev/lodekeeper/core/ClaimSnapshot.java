package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable bounded claim view safe to share between policy consumers. */
public final class ClaimSnapshot {
    public static final int MAX_CLAIMS = 128;
    private static final int MAX_INDEX_ENTRIES = 65_536;
    private static final Comparator<ClaimBox> CLAIM_ORDER = Comparator
            .comparing((ClaimBox claim) -> claim.scope().worldId())
            .thenComparing(claim -> claim.scope().dimension())
            .thenComparing(ClaimBox::id);

    private final List<ClaimBox> all;
    private final Map<WorldScope, List<ClaimBox>> byScope;
    private final Map<ChunkKey, List<ClaimBox>> byChunk;
    private final List<ClaimBox> oversized;

    public ClaimSnapshot(Collection<ClaimBox> claims) {
        Objects.requireNonNull(claims, "claims");
        var copy = new ArrayList<ClaimBox>(Math.min(claims.size(), MAX_CLAIMS));
        var ids = new HashSet<ScopedClaimId>();
        for (ClaimBox claim : claims) {
            Objects.requireNonNull(claim, "claim");
            if (copy.size() == MAX_CLAIMS) {
                throw new IllegalArgumentException("Claim snapshot exceeds " + MAX_CLAIMS + " claims");
            }
            if (!ids.add(new ScopedClaimId(claim.scope(), claim.id()))) {
                throw new IllegalArgumentException("Duplicate claim ID in scope: " + claim.id());
            }
            copy.add(claim);
        }
        copy.sort(CLAIM_ORDER);
        all = List.copyOf(copy);

        var scopeLists = new HashMap<WorldScope, List<ClaimBox>>();
        for (ClaimBox claim : all) {
            scopeLists.computeIfAbsent(claim.scope(), ignored -> new ArrayList<>()).add(claim);
        }
        scopeLists.replaceAll((scope, scopedClaims) -> List.copyOf(scopedClaims));
        byScope = Map.copyOf(scopeLists);

        var chunkLists = new HashMap<ChunkKey, ArrayList<ClaimBox>>();
        var oversizedClaims = new ArrayList<ClaimBox>();
        int indexedEntries = 0;
        for (ClaimBox claim : all) {
            int minChunkX = Math.floorDiv(claim.minX(), 16);
            int maxChunkX = Math.floorDiv(claim.maxX(), 16);
            int minChunkZ = Math.floorDiv(claim.minZ(), 16);
            int maxChunkZ = Math.floorDiv(claim.maxZ(), 16);
            long chunkWidth = (long) maxChunkX - minChunkX + 1;
            long chunkDepth = (long) maxChunkZ - minChunkZ + 1;
            long availableEntries = MAX_INDEX_ENTRIES - (long) indexedEntries;
            if (chunkWidth > availableEntries / chunkDepth) {
                oversizedClaims.add(claim);
                continue;
            }
            for (int chunkX = minChunkX; ; chunkX++) {
                for (int chunkZ = minChunkZ; ; chunkZ++) {
                    ChunkKey key = new ChunkKey(claim.scope(), chunkX, chunkZ);
                    chunkLists.computeIfAbsent(key, ignored -> new ArrayList<>()).add(claim);
                    indexedEntries++;
                    if (chunkZ == maxChunkZ) break;
                }
                if (chunkX == maxChunkX) break;
            }
        }
        var frozenChunks = new HashMap<ChunkKey, List<ClaimBox>>(chunkLists.size());
        chunkLists.forEach((key, chunkClaims) -> frozenChunks.put(key, List.copyOf(chunkClaims)));
        byChunk = Map.copyOf(frozenChunks);
        oversized = List.copyOf(oversizedClaims);
    }

    public List<ClaimBox> all() {
        return all;
    }

    public List<ClaimBox> forScope(WorldScope scope) {
        return byScope.getOrDefault(requireScope(scope), List.of());
    }

    public boolean contains(WorldScope scope, int x, int y, int z) {
        return matchingClaim(scope, x, y, z, false);
    }

    /** BREAK and PLACE are blocked inside every claim. Travel and station interaction stay allowed. */
    public boolean forbidsEdit(WorldScope scope, int x, int y, int z, ActionKind action) {
        requireScope(scope);
        Objects.requireNonNull(action, "action");
        return action == ActionKind.BREAK || action == ActionKind.PLACE
                ? matchingClaim(scope, x, y, z, false)
                : false;
    }

    public boolean preferredStation(WorldScope scope, int x, int y, int z) {
        return matchingClaim(scope, x, y, z, true);
    }

    private boolean matchingClaim(WorldScope scope, int x, int y, int z, boolean preferredOnly) {
        WorldScope queryScope = requireScope(scope);
        ChunkKey key = new ChunkKey(queryScope, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        List<ClaimBox> indexedClaims = byChunk.get(key);
        if (matches(indexedClaims, queryScope, x, y, z, preferredOnly)) return true;
        return matches(oversized, queryScope, x, y, z, preferredOnly);
    }

    private static boolean matches(List<ClaimBox> claims, WorldScope scope,
                                   int x, int y, int z, boolean preferredOnly) {
        if (claims == null) return false;
        for (ClaimBox claim : claims) {
            if (claim.scope().equals(scope) && (!preferredOnly || claim.preferredStations())
                    && claim.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static WorldScope requireScope(WorldScope scope) {
        if (scope == null) throw new IllegalArgumentException("scope is required");
        return scope;
    }

    public enum ActionKind {
        BREAK,
        PLACE,
        TRAVEL,
        STATION_INTERACTION
    }

    private record ChunkKey(WorldScope scope, int x, int z) { }

    private record ScopedClaimId(WorldScope scope, String id) { }
}
