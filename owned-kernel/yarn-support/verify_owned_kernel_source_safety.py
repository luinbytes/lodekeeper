from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OWNED_KERNEL = ROOT / "owned-kernel"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def method_body(source: str, marker: str) -> str:
    start = source.find(marker)
    require(start >= 0, f"Missing source method: {marker}")
    open_brace = source.find("{", start)
    require(open_brace >= 0, f"Missing method body: {marker}")
    depth = 0
    for index in range(open_brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[open_brace + 1:index]
    raise SystemExit(f"Unterminated method body: {marker}")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_override_manifest(path: Path) -> None:
    override_root = path.parent / "lifecycle-overrides"
    expected = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        value, relative = line.split("  ", 1)
        expected[relative] = value
    actual = {
        source.relative_to(override_root).as_posix()
        for source in override_root.rglob("*.java")
    }
    require(actual == set(expected), f"{path}: override manifest source inventory differs")
    for relative, value in expected.items():
        require(digest(override_root / relative) == value,
                f"{path}: source hash mismatch for {relative}")


handlers = sorted(
    path
    for path in OWNED_KERNEL.rglob("MixinClientPlayNetHandler.java")
    if "lifecycle-overrides" in path.parts and "build" not in path.parts and "upstream" not in path.parts
)
guards = sorted(
    path
    for path in OWNED_KERNEL.rglob("OwnedMutationGuard.java")
    if "lifecycle-overrides" in path.parts and "build" not in path.parts and "upstream" not in path.parts
)
require(len(handlers) == 14, f"Expected 14 owned single-block handlers, found {len(handlers)}")
require(len(guards) == 14, f"Expected 14 owned mutation guards, found {len(guards)}")
manifests = sorted({
    next(parent for parent in path.parents if parent.name == "lifecycle-overrides").parent
    / "lifecycle-overrides.sha256"
    for path in handlers + guards
})
for manifest in manifests:
    verify_override_manifest(manifest)

for path in handlers:
    source = path.read_text(encoding="utf-8")
    marker = "private void postHandleBlockChange(ClientboundBlockUpdatePacket packetIn, CallbackInfo ci)"
    body = method_body(source, marker)
    method_start = source.find(marker)
    annotation = source.rfind('@Inject(\n            method = "handleBlockUpdate"', 0, method_start)
    require(annotation >= 0, f"{path}: single-block callback lost its RETURN injection")
    return_injection = source.find('@At("RETURN")', annotation, method_start)
    require(return_injection >= 0, f"{path}: single-block callback is not after packet handling")
    thread_check = body.find("if (!this.minecraft.isSameThread()) return;")
    connection_check = body.find("getBaritoneForConnection((ClientPacketListener) (Object) this)")
    event_call = body.find("getGameEventHandler().onBlockChange(new BlockChangeEvent(")
    require(thread_check >= 0 and thread_check < connection_check < event_call,
            f"{path}: packet thread/connection checks must precede snapshot invalidation")
    require("if (baritone == null)" in body[connection_check:event_call],
            f"{path}: matching connection must be required before event delivery")
    for token in ("BlockPos pos = packetIn.getPos().immutable();",
                  "new Pair<>(pos, packetIn.getBlockState())"):
        require(token in body, f"{path}: block-change event is missing {token}")
    require("ChunkPos(" in body or "ChunkPos.containing(pos)" in body,
            f"{path}: block-change event is missing its chunk coordinate")
    require("repackOnAnyBlockChange" not in body and "BLOCKS_TO_KEEP_TRACK_OF" not in body
            and "CachedChunk" not in body,
            f"{path}: disk-cache config or block filter can drop AIR snapshot invalidation")
    require(body.count("return;") == 2 and body.count("new BlockChangeEvent(") == 1,
            f"{path}: only the main-thread and unmatched-connection guards may skip event delivery")

    override_root = next(parent for parent in path.parents if parent.name == "lifecycle-overrides")
    event_method = override_root / "main/java/dev/lodekeeper/navigation/kernel/event/GameEventHandler.java"
    event_source = event_method.read_text(encoding="utf-8")
    event_body = method_body(event_source, "public void onBlockChange(BlockChangeEvent event)")
    dirty_at = event_body.find("snapshots().dirtyBlock(changed.first())")
    repack_at = event_body.find("repackOnAnyBlockChange")
    require(dirty_at >= 0 and repack_at > dirty_at,
            f"{event_method}: snapshot dirtying must run before the optional repack gate")

for path in guards:
    source = path.read_text(encoding="utf-8")
    body = method_body(source, "private static boolean breakClaims(")
    require("for (BlockPos cell : removed)" in body, f"{path}: removed footprint checks are missing")
    require("for (Direction direction : Direction.values())" in body,
            f"{path}: all six neighbor claims are not checked")
    require("cell.relative(direction)" in body and "!policy.mayBreak(neighbor)" in body,
            f"{path}: neighbor claim boundary is not fail-closed")
    require("neighbor.getY() >= minY" in body and "neighbor.getY() < maxYExclusive" in body,
            f"{path}: neighbor checks must be bounded to build height")
    require("DOUBLE_BLOCK_HALF" in body and "instanceof BedBlock" in body,
            f"{path}: paired-block safety checks were removed")
    require("gravityClaims(policy, world, loaded, cell, removed)" in body,
            f"{path}: existing falling-block safety checks were removed")

primary = OWNED_KERNEL / "primary1.21.1/lifecycle-overrides"
modern = OWNED_KERNEL / "modern26.3/overrides/lifecycle-overrides"
tracked = {
    "main/java/dev/lodekeeper/navigation/kernel/OwnedMutationGuard.java": "OwnedMutationGuard.java",
    "launch/java/dev/lodekeeper/navigation/kernel/launch/mixins/MixinClientPlayNetHandler.java": "MixinClientPlayNetHandler.java",
}
primary_hashes = {relative: digest(primary / relative) for relative in tracked}
modern_hashes = {relative: digest(modern / relative) for relative in tracked}
primary_manifest_hash = digest(primary.parent / "lifecycle-overrides.sha256")
for family in ("mc1.21.6", "mc1.21.9", "mc1.21.11"):
    family_root = OWNED_KERNEL / family
    manifest_doc = json.loads((family_root / "source-family-diff-manifest.json").read_text(encoding="utf-8"))
    source_lock = json.loads((family_root / "source-lock.json").read_text(encoding="utf-8"))
    require(manifest_doc["baseline"]["override_manifest_sha256"] == primary_manifest_hash,
            f"{family}: canonical source manifest hash is stale")
    require(source_lock["canonical_baseline"]["override_manifest_sha256"] == primary_manifest_hash,
            f"{family}: canonical source lock hash is stale")
    family_override_root = family_root / "overrides/lifecycle-overrides"
    for relative in tracked:
        row = next(item for item in manifest_doc["files"] if item["path"] == relative)
        require(row["canonical_override_sha256"] == primary_hashes[relative]
                and row["family_override_sha256"] == digest(family_override_root / relative),
                f"{family}: source-family override hashes are stale for {relative}")

for family in ("modern26.1", "modern26.2"):
    family_root = OWNED_KERNEL / family
    manifest_doc = json.loads((family_root / "source-family-diff-manifest.json").read_text(encoding="utf-8"))
    family_override_root = family_root / "overrides/lifecycle-overrides"
    for relative in tracked:
        row = next(item for item in manifest_doc["files"] if item["path"] == relative)
        require(row["canonical_26_3_override_sha256"] == modern_hashes[relative]
                and row["family_override_sha256"] == digest(family_override_root / relative),
                f"{family}: source-family override hashes are stale for {relative}")

for group in sorted((OWNED_KERNEL / "yarn-legacy/groups").glob("mc1.20*")):
    lock = json.loads((group / "source-lock.json").read_text(encoding="utf-8"))
    guard_path = "main/java/dev/lodekeeper/navigation/kernel/OwnedMutationGuard.java"
    require(lock["source_adapters"][guard_path] == digest(group / "lifecycle-overrides" / guard_path),
            f"{group}: source adapter hash is stale")
    require(lock["effective_lifecycle_overrides"]["manifest_sha256"]
            == digest(group / "lifecycle-overrides.sha256"),
            f"{group}: source override manifest hash is stale")

print(f"PASS static owned-kernel source safety: {len(handlers)} AIR-safe block-change routes and {len(guards)} neighbor-protected break guards")
