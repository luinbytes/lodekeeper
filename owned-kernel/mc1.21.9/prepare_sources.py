from __future__ import annotations

import json
import hashlib
import argparse
import re
import shutil
import tarfile
from pathlib import Path
from pathlib import PurePosixPath

ROOT = Path(__file__).resolve().parent
SOURCE_LOCK_FILE = ROOT / "source-lock.json"
SOURCE_LOCK = json.loads(SOURCE_LOCK_FILE.read_text(encoding="utf-8"))
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=Path, required=True)
parser.add_argument("--source-archive", type=Path, required=True)
args = parser.parse_args()
UPSTREAM = args.source_root.resolve()
UPSTREAM_ARCHIVE = args.source_archive.resolve()
UPSTREAM_METADATA = (ROOT / SOURCE_LOCK["dependency_metadata"]).resolve()
OUT = ROOT / "build" / "generated" / "owned-kernel" / "src"
SOURCE_MANIFEST_FILE = ROOT / "build" / "generated" / "owned-kernel" / "source-manifest.json"
OVERRIDES = ROOT / "overrides" / "lifecycle-overrides"
OVERRIDE_MANIFEST = ROOT / "overrides" / "lifecycle-overrides.sha256"
SOURCE_FAMILY_DIFF_MANIFEST = ROOT / "source-family-diff-manifest.json"
PKG = Path("dev/lodekeeper/navigation/kernel")
UPSTREAM_ROOT = SOURCE_LOCK["source_root"]

GROUPS = {
    "api": Path("src/api/java"),
    "main": Path("src/main/java"),
    "launch": Path("src/launch/java"),
    "schematica_api": Path("src/schematica_api/java"),
}

MANDATORY_OVERRIDES = {
    "main/java/dev/lodekeeper/navigation/kernel/Baritone.java",
    "main/java/dev/lodekeeper/navigation/kernel/OwnedKernelRuntime.java",
    "main/java/dev/lodekeeper/navigation/kernel/OwnedWorkScheduler.java",
    "main/java/dev/lodekeeper/navigation/kernel/snapshot/OwnedWorldSnapshots.java",
    "main/java/dev/lodekeeper/navigation/kernel/OwnedMutationGuard.java",
    "main/java/dev/lodekeeper/navigation/kernel/utils/InputOverrideHandler.java",
    "main/java/dev/lodekeeper/navigation/kernel/process/MineProcess.java",
    "main/java/dev/lodekeeper/navigation/kernel/process/OwnedMiningAdmission.java",
    "api/java/dev/lodekeeper/navigation/kernel/api/AutomationInputBarrier.java",
    "api/java/dev/lodekeeper/navigation/kernel/api/process/OwnedMiningTargets.java",
    "main/java/dev/lodekeeper/navigation/kernel/BoundWorldEditPolicy.java",
    "launch/java/dev/lodekeeper/navigation/kernel/launch/mixins/MixinMinecraft.java",
}


def excluded(group: str, rel: Path) -> bool:
    name = rel.as_posix()
    if group == "api":
        return (
            name == "baritone/api/BaritoneAPI.java"
            or name == "baritone/api/IBaritoneProvider.java"
            or name == "baritone/api/utils/SettingsUtil.java"
            or (
                name.startswith("baritone/api/command/")
                and name != "baritone/api/command/registry/Registry.java"
            )
        )
    if group == "main":
        return (
            name == "baritone/BaritoneProvider.java"
            or name == "baritone/utils/GuiClick.java"
            or name == "baritone/utils/accessor/IFireworkRocketEntity.java"
            or name == "baritone/process/ElytraProcess.java"
            or name == "baritone/behavior/WaypointBehavior.java"
            or name.startswith("baritone/command/")
            or (
                name.startswith("baritone/process/elytra/")
                and not name.endswith("/NullElytraProcess.java")
            )
        )
    if group == "launch":
        return (
            name == "baritone/launch/BaritoneMixinConnector.java"
            or name == "baritone/launch/mixins/MixinCommandSuggestionHelper.java"
            or name == "baritone/launch/mixins/MixinFireworkRocketEntity.java"
            or name == "baritone/launch/mixins/MixinScreen.java"
        )
    return False


def owned_path(group: str, rel: Path) -> Path:
    if group == "api" and rel.as_posix() == "baritone/api/command/registry/Registry.java":
        return PKG / "api" / "registry" / "Registry.java"
    if group == "schematica_api":
        return rel
    parts = rel.parts
    if not parts or parts[0] != "baritone":
        raise ValueError(f"unexpected upstream package path: {rel}")
    return PKG.joinpath(*parts[1:])


def rewrite_source(text: str) -> str:
    text = text.replace(
        "baritone.api.BaritoneAPI",
        "dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI",
    )
    text = text.replace("BaritoneAPI", "OwnedKernelAPI")
    text = text.replace("SettingsUtil", "KernelSettingsUtil")
    text = re.sub(
        r"(?m)^(\s*(?:package|import)(?:\s+static)?\s+)baritone(?=[.;])",
        r"\1dev.lodekeeper.navigation.kernel",
        text,
    )
    text = text.replace(
        "dev.lodekeeper.navigation.kernel.api.command.registry",
        "dev.lodekeeper.navigation.kernel.api.registry",
    )
    text = text.replace("ElytraProcess::create", "NullElytraProcess::new")
    text = text.replace(
        "As set by ExampleBaritoneControl or something idk",
        "Active goal supplied by the owning controller",
    )
    return text


def patch_i_baritone(text: str) -> str:
    text = text.replace("import dev.lodekeeper.navigation.kernel.api.command.manager.ICommandManager;\n", "")
    text = re.sub(
        r"    /\*\*\n     \* @return The \{@link ICommandManager\} instance\n"
        r".*?    ICommandManager getCommandManager\(\);\n\n",
        "",
        text,
        flags=re.S,
        count=1,
    )
    text = re.sub(
        r"    /\*\*\n     \* Open click\n     \*/\n    void openClick\(\);\n",
        "",
        text,
        count=1,
    )
    if "ICommandManager" in text or "openClick" in text:
        raise RuntimeError("command API survived IBaritone patch")
    return text


def patch_minecraft_mixin(text: str) -> str:
    pattern = (
        r"    @Inject\(\s*method = \"<init>\",.*?"
        r"    private void postInit\(CallbackInfo ci\) \{\s*"
        r"OwnedKernelAPI\.getProvider\(\)\.getPrimaryBaritone\(\);\s*"
        r"\}\s*"
    )
    text, count = re.subn(pattern, "", text, flags=re.S, count=1)
    if count != 1:
        raise RuntimeError("could not remove eager Baritone provider bootstrap from MixinMinecraft")
    return text


def strip_unsupported_flight_hooks(text: str) -> str:
    field = "    @Unique\n    private RotationMoveEvent elytraRotationEvent;\n"
    if text.count(field) != 1:
        raise RuntimeError("pinned MixinLivingEntity changed its elytra event field")
    text = text.replace(field, "", 1)
    sections = (
        (
            '    @Inject(\n            method = "updateFallFlyingMovement",',
            '    @Inject(\n            method = "travelFallFlying",',
        ),
        (
            '    @Inject(\n            method = "travelFallFlying",',
            "    @Unique\n    private Optional<IBaritone> getBaritone()",
        ),
    )
    for start, end in sections:
        if text.count(start) != 1 or text.count(end) != 1:
            raise RuntimeError("pinned MixinLivingEntity flight hook boundaries changed")
        start_at = text.index(start)
        end_at = text.index(end, start_at)
        text = text[:start_at] + text[end_at:]
    text = text.replace("import net.minecraft.world.phys.Vec3;\n", "")
    text = text.replace("import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;\n", "")
    if any(marker in text for marker in ("elytraRotationEvent", "updateFallFlyingMovement", "travelFallFlying")):
        raise RuntimeError("unsupported flight hook survived MixinLivingEntity adaptation")
    if "jumpRotationEvent" not in text:
        raise RuntimeError("MixinLivingEntity jump rotation hook was removed with flight hooks")
    return text


def put_source(group: str, rel: Path, text: str) -> None:
    if excluded(group, rel):
        return
    dest_rel = owned_path(group, rel)
    if group != "schematica_api":
        text = rewrite_source(text)
    if rel.as_posix() == "baritone/behavior/PathingBehavior.java":
        text = text.replace("import dev.lodekeeper.navigation.kernel.process.ElytraProcess;\n", "")
    elif rel.as_posix() == "baritone/api/IBaritone.java":
        text = patch_i_baritone(text)
    elif rel.as_posix() == "baritone/api/utils/BlockOptionalMeta.java":
        shared_executor_calls = text.count("ForkJoinPool.commonPool()")
        if SOURCE_LOCK["minecraft_api_family"] == "1.21.11":
            if shared_executor_calls != 1:
                raise RuntimeError("1.21.11 BlockOptionalMeta must have exactly one pinned shared-executor call")
            text = text.replace("ForkJoinPool.commonPool()", "Runnable::run", 1)
            text = text.replace("import java.util.concurrent.ForkJoinPool;\n", "")
        elif shared_executor_calls:
            raise RuntimeError("this pinned BlockOptionalMeta API family unexpectedly uses ForkJoinPool")
    elif rel.as_posix() == "baritone/utils/PathRenderer.java":
        text, count = re.subn(
            r"(?m)^\s*if \(ctx\.minecraft\(\)\.(?:gui\.)?screen(?:\(\))? instanceof GuiClick\) \{\n"
            r"\s*\(\(GuiClick\) ctx\.minecraft\(\)\.(?:gui\.)?screen(?:\(\))?\)\.onRender\(event\.getModelViewStack\(\), event\.getProjectionMatrix\(\)\);\n"
            r"\s*\}\n",
            "",
            text,
            count=1,
        )
        if count != 1:
            raise RuntimeError("could not remove PathRenderer custom-GUI branch for this Minecraft family")
    elif rel.as_posix() == "baritone/launch/mixins/MixinMinecraft.java":
        text = patch_minecraft_mixin(text)
    elif rel.as_posix() == "baritone/launch/mixins/MixinLivingEntity.java":
        text = strip_unsupported_flight_hooks(text)
    elif rel.as_posix() == "baritone/process/elytra/NullElytraProcess.java":
        text = text.replace(
            "package dev.lodekeeper.navigation.kernel.process.elytra;",
            "package dev.lodekeeper.navigation.kernel.process;",
        )
        dest_rel = PKG / "process" / "NullElytraProcess.java"
    target = OUT / group / "java" / dest_rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def generate_upstream_sources(archive: tarfile.TarFile) -> None:
    prefix = UPSTREAM_ROOT + "/"
    members = sorted(
        (member for member in archive.getmembers() if member.isfile()),
        key=lambda member: member.name,
    )
    for group, source_root in GROUPS.items():
        source_prefix = prefix + source_root.as_posix() + "/"
        for member in members:
            if not member.name.startswith(source_prefix) or not member.name.endswith(".java"):
                continue
            rel = Path(member.name[len(source_prefix):])
            stream = archive.extractfile(member)
            if stream is None:
                raise RuntimeError(f"cannot read upstream source {member.name}")
            put_source(group, rel, stream.read().decode("utf-8"))


def write_owned_api_sources() -> None:
    # The settings formatter keeps coordinate redaction and value display. File reads
    # and writes stay out of the owned kernel.
    utils = OUT / "api" / "java" / PKG / "api" / "utils" / "KernelSettingsUtil.java"
    utils.parent.mkdir(parents=True, exist_ok=True)
    utils.write_text(
        """package dev.lodekeeper.navigation.kernel.api.utils;

    import dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI;
    import dev.lodekeeper.navigation.kernel.api.Settings;

    public final class KernelSettingsUtil {
        private KernelSettingsUtil() {}

        public static String maybeCensor(int coordinate) {
            return OwnedKernelAPI.getSettings().censorCoordinates.value
                    ? "<censored>"
                    : Integer.toString(coordinate);
        }

        public static String settingToString(Settings.Setting setting) {
            if (setting.isJavaOnly()) {
                return setting.getName();
            }
            return setting.getName() + " " + String.valueOf(setting.value);
        }
    }
    """,
        encoding="utf-8",
    )

    provider_api = OUT / "api" / "java" / PKG / "api" / "OwnedKernelProvider.java"
    provider_api.parent.mkdir(parents=True, exist_ok=True)
    provider_api.write_text(
        """package dev.lodekeeper.navigation.kernel.api;

    import dev.lodekeeper.navigation.kernel.api.cache.IWorldScanner;
    import net.minecraft.client.Minecraft;
    import net.minecraft.client.multiplayer.ClientPacketListener;
    import net.minecraft.client.player.LocalPlayer;

    import java.util.List;

    public interface OwnedKernelProvider {
        IBaritone getPrimaryBaritone();
        List<IBaritone> getAllBaritones();
        IBaritone getBaritoneForPlayer(LocalPlayer player);
        IBaritone getBaritoneForMinecraft(Minecraft minecraft);
        IBaritone getBaritoneForConnection(ClientPacketListener connection);
        IWorldScanner getWorldScanner();
    }
    """,
        encoding="utf-8",
    )

    api_file = OUT / "api" / "java" / PKG / "api" / "OwnedKernelAPI.java"
    api_file.write_text(
        """package dev.lodekeeper.navigation.kernel.api;

    import dev.lodekeeper.navigation.kernel.api.cache.IWorldScanner;
    import net.minecraft.client.Minecraft;
    import net.minecraft.client.multiplayer.ClientPacketListener;
    import net.minecraft.client.player.LocalPlayer;

    import java.util.List;
    import java.util.Objects;

    public final class OwnedKernelAPI {
        private static final Settings SETTINGS = new Settings();
        private static final OwnedKernelProvider EMPTY_PROVIDER = new OwnedKernelProvider() {
            public IBaritone getPrimaryBaritone() { return null; }
            public List<IBaritone> getAllBaritones() { return List.of(); }
            public IBaritone getBaritoneForPlayer(LocalPlayer player) { return null; }
            public IBaritone getBaritoneForMinecraft(Minecraft minecraft) { return null; }
            public IBaritone getBaritoneForConnection(ClientPacketListener connection) { return null; }
            public IWorldScanner getWorldScanner() { return null; }
        };
        private static volatile OwnedKernelProvider provider = EMPTY_PROVIDER;

        private OwnedKernelAPI() {}

        public static Settings getSettings() {
            return SETTINGS;
        }

        public static OwnedKernelProvider getProvider() {
            return provider;
        }

        public static void installProvider(OwnedKernelProvider next) {
            provider = Objects.requireNonNull(next, "next");
        }

        public static void clearProvider(OwnedKernelProvider expected) {
            if (provider == expected) {
                provider = EMPTY_PROVIDER;
            }
        }
    }
    """,
        encoding="utf-8",
    )

    world_policy = OUT / "api" / "java" / PKG / "WorldEditPolicy.java"
    world_policy.parent.mkdir(parents=True, exist_ok=True)
    world_policy.write_text(
        """package dev.lodekeeper.navigation.kernel;

    import net.minecraft.core.BlockPos;

    public interface WorldEditPolicy {
        boolean mayBreak(BlockPos position);
        boolean mayPlace(BlockPos position);

        static WorldEditPolicy denyAll() {
            return DenyAll.INSTANCE;
        }

        enum DenyAll implements WorldEditPolicy {
            INSTANCE;
            public boolean mayBreak(BlockPos position) { return false; }
            public boolean mayPlace(BlockPos position) { return false; }
        }
    }
    """,
        encoding="utf-8",
    )

    policy_snapshot = OUT / "api" / "java" / PKG / "WorldEditPolicySnapshot.java"
    policy_snapshot.write_text(
        """package dev.lodekeeper.navigation.kernel;

    import net.minecraft.core.BlockPos;

    import java.util.List;

    public record WorldEditPolicySnapshot(List<BlockRegion> protectedRegions) implements WorldEditPolicy {
        public WorldEditPolicySnapshot {
            protectedRegions = List.copyOf(protectedRegions);
        }

        public boolean mayBreak(BlockPos position) {
            return protectedRegions.stream().noneMatch(region -> region.contains(position));
        }

        public boolean mayPlace(BlockPos position) {
            return protectedRegions.stream().noneMatch(region -> region.contains(position));
        }

        public record BlockRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            public BlockRegion {
                if (minX > maxX || minY > maxY || minZ > maxZ) {
                    throw new IllegalArgumentException("region minimum exceeds maximum");
                }
            }

            public boolean contains(BlockPos position) {
                return position.getX() >= minX && position.getX() <= maxX
                        && position.getY() >= minY && position.getY() <= maxY
                        && position.getZ() >= minZ && position.getZ() <= maxZ;
            }
        }
    }
    """,
        encoding="utf-8",
    )

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_upstream_file(archive: tarfile.TarFile, relative: str) -> bytes:
    name = f"{UPSTREAM_ROOT}/{relative}"
    member = archive.getmember(name)
    stream = archive.extractfile(member)
    if stream is None:
        raise RuntimeError(f"cannot read required upstream input {name}")
    return stream.read()


def verify_inputs() -> dict[str, str]:
    if SOURCE_LOCK.get("schema_version") != 1:
        raise RuntimeError("unsupported owned-kernel source lock")
    if len(SOURCE_LOCK.get("source_commit", "")) != 40:
        raise RuntimeError("source lock must pin a full upstream commit")
    if len(SOURCE_LOCK.get("archive_sha256", "")) != 64:
        raise RuntimeError("source lock must pin the upstream archive SHA-256")
    if len(SOURCE_LOCK.get("dependency_metadata_sha256", "")) != 64:
        raise RuntimeError("source lock must pin the Baritone dependency metadata SHA-256")
    minecraft_versions = SOURCE_LOCK.get("minecraft_versions")
    if (not isinstance(minecraft_versions, list) or not minecraft_versions
            or any(not isinstance(version, str) for version in minecraft_versions)):
        raise RuntimeError("source lock must list the exact Minecraft versions in its API family")
    if SOURCE_LOCK.get("minecraft_api_family") not in {"1.21.6", "1.21.9", "1.21.11"} or SOURCE_LOCK["java_version"] != 21:
        raise RuntimeError("this source builder supports the 1.21.6, 1.21.9, and 1.21.11 API families on Java 21")
    if SOURCE_LOCK["minecraft_mappings"] != "official Mojang mappings":
        raise RuntimeError("this source builder requires official Mojang mappings")
    repository_root = ROOT.parents[1].resolve()
    if repository_root not in UPSTREAM_METADATA.parents:
        raise RuntimeError("dependency metadata path escapes the repository")
    if SOURCE_LOCK["source_root"] != f"baritone-{SOURCE_LOCK['source_commit']}":
        raise RuntimeError("source root does not match the pinned commit")
    if SOURCE_LOCK["archive_url"] != f"{SOURCE_LOCK['repository']}/archive/{SOURCE_LOCK['source_commit']}.tar.gz":
        raise RuntimeError("source archive URL does not name the pinned commit")
    if SOURCE_LOCK["archive_filename"] != f"baritone-{SOURCE_LOCK['baritone_version']}-{SOURCE_LOCK['source_commit']}.tar.gz":
        raise RuntimeError("source archive filename does not match the pinned release")
    if UPSTREAM.name != UPSTREAM_ROOT:
        raise RuntimeError(f"expected pinned source root {UPSTREAM_ROOT}, got {UPSTREAM.name}")
    if UPSTREAM_ARCHIVE.name != SOURCE_LOCK["archive_filename"]:
        raise RuntimeError("source archive filename does not match source lock")
    if not UPSTREAM_ARCHIVE.is_file():
        raise RuntimeError(f"pinned source archive is missing: {UPSTREAM_ARCHIVE}")
    archive_hash = sha256(UPSTREAM_ARCHIVE)
    if archive_hash != SOURCE_LOCK["archive_sha256"]:
        raise RuntimeError(f"pinned source archive SHA-256 mismatch: {archive_hash}")
    if not UPSTREAM.is_dir():
        raise RuntimeError(f"pinned source directory is missing: {UPSTREAM}")

    required_inputs = [
        UPSTREAM / "src/api/java",
        UPSTREAM / "src/main/java",
        UPSTREAM / "src/launch/java",
        UPSTREAM / "src/schematica_api/java",
        UPSTREAM / "src/launch/resources/mixins.baritone.json",
        UPSTREAM / "LICENSE",
        UPSTREAM / "LICENSE-Part-2.jpg",
    ]
    missing_inputs = [str(path.relative_to(UPSTREAM)) for path in required_inputs if not path.exists()]
    if missing_inputs:
        raise RuntimeError("pinned source archive is missing required inputs: " + ", ".join(missing_inputs))

    metadata_hash = sha256(UPSTREAM_METADATA)
    if metadata_hash != SOURCE_LOCK["dependency_metadata_sha256"]:
        raise RuntimeError(f"Baritone dependency metadata SHA-256 mismatch: {metadata_hash}")
    metadata = json.loads(UPSTREAM_METADATA.read_text(encoding="utf-8"))
    release = next(
        (item for item in metadata["releases"] if item["version"] == SOURCE_LOCK["baritone_version"]),
        None,
    )
    if release is None:
        raise RuntimeError("dependency metadata does not contain the pinned Baritone release")
    if release["source_commit"] != SOURCE_LOCK["source_commit"]:
        raise RuntimeError("dependency metadata source commit does not match the source archive")
    if (release["minecraft_versions"] != minecraft_versions
            or release["minecraft_dependency"] != minecraft_versions
            or release["java_version"] != SOURCE_LOCK["java_version"]
            or release["sha256"] != SOURCE_LOCK["release_sha256"]):
        raise RuntimeError("dependency metadata release, exact Minecraft family, or Java version no longer matches the source lock")

    with tarfile.open(UPSTREAM_ARCHIVE, "r:gz") as archive:
        diff_manifest_hash = verify_source_family_diff_manifest(archive)
    return {
        UPSTREAM_ARCHIVE.name: archive_hash,
        UPSTREAM_METADATA.name: metadata_hash,
        SOURCE_FAMILY_DIFF_MANIFEST.name: diff_manifest_hash,
    }


def expected_owned_path(source_path: str) -> str:
    parts = PurePosixPath(source_path).parts
    if len(parts) < 5 or parts[0] != "src" or parts[2] != "java":
        raise RuntimeError(f"invalid pinned source path in family diff manifest: {source_path}")
    group = parts[1]
    if group not in {"api", "main", "launch", "schematica_api"}:
        raise RuntimeError(f"unexpected source group in family diff manifest: {source_path}")
    rel = PurePosixPath(*parts[3:])
    if group == "schematica_api":
        return f"schematica_api/java/{rel.as_posix()}"
    if not rel.parts or rel.parts[0] != "baritone":
        raise RuntimeError(f"unexpected upstream package path in family diff manifest: {source_path}")
    if group == "api" and rel.as_posix() == "baritone/api/command/registry/Registry.java":
        owned = PKG / "api" / "registry" / "Registry.java"
    elif group == "main" and rel.as_posix() == "baritone/process/elytra/NullElytraProcess.java":
        owned = PKG / "process" / "NullElytraProcess.java"
    else:
        owned = PKG.joinpath(*rel.parts[1:])
    return f"{group}/java/{owned.as_posix()}"


def verify_source_family_diff_manifest(archive: tarfile.TarFile) -> str:
    if not SOURCE_FAMILY_DIFF_MANIFEST.is_file():
        raise RuntimeError("source-family diff manifest is missing")
    diff_manifest = json.loads(SOURCE_FAMILY_DIFF_MANIFEST.read_text(encoding="utf-8"))
    if diff_manifest.get("schema_version") != 1:
        raise RuntimeError("unsupported source-family diff manifest")
    expected_family = {
        "api_family": SOURCE_LOCK["minecraft_api_family"],
        "minecraft_versions": SOURCE_LOCK["minecraft_versions"],
        "baritone_version": SOURCE_LOCK["baritone_version"],
        "source_commit": SOURCE_LOCK["source_commit"],
        "archive_sha256": SOURCE_LOCK["archive_sha256"],
        "java_version": SOURCE_LOCK["java_version"],
        "minecraft_mappings": SOURCE_LOCK["minecraft_mappings"],
    }
    if diff_manifest.get("family") != expected_family:
        raise RuntimeError("source-family diff manifest does not match the exact family lock")
    expected_baseline = {
        "baritone_version": SOURCE_LOCK["canonical_baseline"]["baritone_version"],
        "source_commit": SOURCE_LOCK["canonical_baseline"]["source_commit"],
        "archive_sha256": SOURCE_LOCK["canonical_baseline"]["archive_sha256"],
        "override_manifest_sha256": SOURCE_LOCK["canonical_baseline"]["override_manifest_sha256"],
        "reviewed_baseline_sha256": SOURCE_LOCK["reviewed_baseline_sha256"],
    }
    if diff_manifest.get("baseline") != expected_baseline:
        raise RuntimeError("source-family diff manifest baseline does not match the reviewed lock")
    expected_transforms = {
        "src/launch/java/baritone/launch/mixins/MixinLivingEntity.java": (
            "remove only the unsupported Elytra movement hooks and retain jump rotation"
        ),
        "src/launch/resources/mixins.baritone.json": (
            "set generated launch mixin config refmap to launch-lodekeeper-owned-kernel-refmap.json"
        ),
        "src/api/java/baritone/api/utils/BlockOptionalMeta.java": (
            "replace one ForkJoinPool.commonPool call with Runnable::run"
            if SOURCE_LOCK["minecraft_api_family"] == "1.21.11"
            else "keep pinned native reload overload; source has no ForkJoinPool call"
        ),
    }
    if diff_manifest.get("generator_transforms") != expected_transforms:
        raise RuntimeError("source-family generator transforms do not match the reviewed family adaptation")

    def raw_source_hash(source_path: str) -> str:
        try:
            content = read_upstream_file(archive, source_path)
        except (KeyError, tarfile.TarError) as exc:
            raise RuntimeError(f"source-family diff refers to missing pinned file {source_path}") from exc
        return hashlib.sha256(content).hexdigest()

    for member in archive.getmembers():
        path = PurePosixPath(member.name)
        if (path.is_absolute() or ".." in path.parts or "\\" in member.name
                or not path.parts or path.parts[0] != UPSTREAM_ROOT):
            raise RuntimeError(f"unsafe pinned archive path {member.name}")
        if not (member.isfile() or member.isdir()):
            raise RuntimeError(f"unsupported pinned archive entry {member.name}")

    rows = diff_manifest.get("files")
    if not isinstance(rows, list):
        raise RuntimeError("source-family diff manifest has no file rows")
    by_path = {}
    for row in rows:
        relative = row.get("path")
        if not isinstance(relative, str) or relative in by_path:
            raise RuntimeError("source-family diff manifest contains an invalid or repeated override path")
        by_path[relative] = row
    files = sorted(OVERRIDES.rglob("*.java"))
    actual_paths = {path.relative_to(OVERRIDES).as_posix() for path in files}
    if actual_paths != set(by_path):
        raise RuntimeError("source-family diff rows do not match the owned override set")
    for source in files:
        relative = source.relative_to(OVERRIDES).as_posix()
        row = by_path[relative]
        override_hash = sha256(source)
        if row.get("family_override_sha256") != override_hash:
            raise RuntimeError(f"source-family diff hash does not match override {relative}")
        if not isinstance(row.get("canonical_override_sha256"), str) or len(row["canonical_override_sha256"]) != 64:
            raise RuntimeError(f"source-family diff has no canonical override hash for {relative}")
        source_path = row.get("source_path")
        family_hash = row.get("family_source_sha256")
        baseline_hash = row.get("baseline_source_sha256")
        if source_path is None:
            if family_hash is not None or baseline_hash is not None or row.get("upstream_source_changed"):
                raise RuntimeError(f"new owned helper has inconsistent source hashes: {relative}")
        else:
            if expected_owned_path(source_path) != relative:
                raise RuntimeError(f"source-family path mapping does not match {relative}")
            actual_family_hash = raw_source_hash(source_path)
            if family_hash != actual_family_hash:
                raise RuntimeError(f"pinned source hash does not match family diff row for {relative}")
            if not isinstance(baseline_hash, str) or len(baseline_hash) != 64:
                raise RuntimeError(f"baseline source hash is missing for {relative}")
            if row.get("upstream_source_changed") != (actual_family_hash != baseline_hash):
                raise RuntimeError(f"upstream source-change flag is wrong for {relative}")
        if not isinstance(row.get("merge_conflict_hunks"), int) or row["merge_conflict_hunks"] < 0:
            raise RuntimeError(f"merge conflict count is invalid for {relative}")
        if not isinstance(row.get("port_resolution"), str) or not row["port_resolution"].strip():
            raise RuntimeError(f"port resolution is missing for {relative}")

    excluded = diff_manifest.get("excluded_overrides")
    expected_excluded_path = "api/java/dev/lodekeeper/navigation/kernel/api/utils/BlockOptionalMeta.java"
    if not isinstance(excluded, list) or [row.get("path") for row in excluded] != [expected_excluded_path]:
        raise RuntimeError("family diff must record the one incompatible BlockOptionalMeta full-file override")
    excluded_row = excluded[0]
    if expected_owned_path(excluded_row["source_path"]) != expected_excluded_path:
        raise RuntimeError("excluded BlockOptionalMeta source mapping does not match its owned path")
    if raw_source_hash(excluded_row["source_path"]) != excluded_row.get("family_source_sha256"):
        raise RuntimeError("excluded BlockOptionalMeta source hash does not match the pinned archive")
    if SOURCE_LOCK["minecraft_api_family"] == "1.21.11":
        expected_transform = "replace exactly one ForkJoinPool.commonPool() call with Runnable::run and remove the ForkJoinPool import"
        if excluded_row.get("generator_transform") != expected_transform:
            raise RuntimeError("1.21.11 BlockOptionalMeta executor transform is not recorded")
    elif "generator_transform" in excluded_row:
        raise RuntimeError("an executor transform is recorded for a family that does not use the shared executor")
    return sha256(SOURCE_FAMILY_DIFF_MANIFEST)

def write_mixin_config(archive: tarfile.TarFile) -> None:
    resources = OUT / "launch" / "resources"
    resources.mkdir(parents=True, exist_ok=True)
    config = json.loads(
        read_upstream_file(archive, "src/launch/resources/mixins.baritone.json").decode("utf-8")
    )
    config["package"] = "dev.lodekeeper.navigation.kernel.launch.mixins"
    config["refmap"] = "launch-lodekeeper-owned-kernel-refmap.json"
    config["client"] = [
        name for name in config["client"]
        if name not in {"MixinCommandSuggestionHelper", "MixinFireworkRocketEntity", "MixinScreen"}
    ]
    (resources / "mixins.lodekeeper-kernel.json").write_text(
        json.dumps(config, indent=2) + "\n",
        encoding="utf-8",
    )


def write_license(archive: tarfile.TarFile) -> None:
    license_dir = OUT / "main" / "resources" / "META-INF" / "licenses"
    license_dir.mkdir(parents=True, exist_ok=True)
    (license_dir / "BARITONE-LICENSE.txt").write_bytes(read_upstream_file(archive, "LICENSE"))
    (license_dir / "BARITONE-LICENSE-Part-2.jpg").write_bytes(read_upstream_file(archive, "LICENSE-Part-2.jpg"))


def apply_lifecycle_overrides() -> list[Path]:
    expected = {}
    for line in OVERRIDE_MANIFEST.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        digest, separator, raw_path = line.partition("  ")
        relative = PurePosixPath(raw_path)
        if (not separator or len(digest) != 64 or relative.is_absolute()
                or ".." in relative.parts or "\\" in raw_path):
            raise RuntimeError(f"invalid canonical override hash entry: {line}")
        if raw_path in expected:
            raise RuntimeError(f"duplicate canonical override path: {raw_path}")
        expected[raw_path] = digest

    files = sorted(OVERRIDES.rglob("*.java"))
    actual = {path.relative_to(OVERRIDES).as_posix() for path in files}
    missing = set(expected) - actual
    unexpected = actual - set(expected)
    if missing or unexpected:
        raise RuntimeError(f"canonical override set changed: missing={sorted(missing)} unexpected={sorted(unexpected)}")
    absent = MANDATORY_OVERRIDES - actual
    if absent:
        raise RuntimeError(f"mandatory owned runtime overrides are missing: {sorted(absent)}")
    for source in files:
        relative = source.relative_to(OVERRIDES).as_posix()
        actual_hash = sha256(source)
        if actual_hash != expected[relative]:
            raise RuntimeError(f"canonical override hash mismatch for {relative}: {actual_hash}")
        target = OUT / source.relative_to(OVERRIDES)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    return files


def write_source_manifest(input_hashes: dict[str, str], overrides: list[Path]) -> None:
    generated = {}
    for path in sorted(OUT.rglob("*")):
        if path.is_file():
            generated[path.relative_to(OUT).as_posix()] = sha256(path)
    manifest = {
        "schema_version": 1,
        "minecraft_api_family": SOURCE_LOCK["minecraft_api_family"],
        "minecraft_versions": SOURCE_LOCK["minecraft_versions"],
        "java_version": SOURCE_LOCK["java_version"],
        "minecraft_mappings": SOURCE_LOCK["minecraft_mappings"],
        "baritone_version": SOURCE_LOCK["baritone_version"],
        "baritone_source_commit": SOURCE_LOCK["source_commit"],
        "source_pins": {
            "archive_url": SOURCE_LOCK["archive_url"],
            "archive_filename": SOURCE_LOCK["archive_filename"],
            "archive_sha256": input_hashes[UPSTREAM_ARCHIVE.name],
            "dependency_metadata_sha256": input_hashes[UPSTREAM_METADATA.name],
            "source_family_diff_manifest_sha256": input_hashes[SOURCE_FAMILY_DIFF_MANIFEST.name],
        },
        "override_manifest_sha256": sha256(OVERRIDE_MANIFEST),
        "canonical_override_hashes": {
            path.relative_to(OVERRIDES).as_posix(): sha256(path)
            for path in overrides
        },
        "canonical_override_paths": sorted(
            path.relative_to(OVERRIDES).as_posix()
            for path in overrides
        ),
        "source_transforms": json.loads(SOURCE_FAMILY_DIFF_MANIFEST.read_text(encoding="utf-8"))["generator_transforms"],
        "generated_files": generated,
    }
    SOURCE_MANIFEST_FILE.parent.mkdir(parents=True, exist_ok=True)
    SOURCE_MANIFEST_FILE.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def verify_generated_sources() -> int:
    sources = sorted(OUT.rglob("*.java"))
    banned_names = {
        "BaritoneProvider.java",
        "IBaritoneProvider.java",
        "BaritoneAPI.java",
        "BaritoneMixinConnector.java",
        "ElytraProcess.java",
        "NetherPathfinderContext.java",
        "NetherPath.java",
        "GuiClick.java",
        "CommandManager.java",
        "MixinCommandSuggestionHelper.java",
        "MixinFireworkRocketEntity.java",
        "MixinScreen.java",
    }
    present_banned = [str(path.relative_to(OUT)) for path in sources if path.name in banned_names]
    if present_banned:
        raise RuntimeError(f"excluded runtime sources were generated: {present_banned}")
    runtime = OUT / "main" / "java" / PKG / "Baritone.java"
    runtime_text = runtime.read_text(encoding="utf-8")
    for banned in ("CommandManager", "openClick", "SynchronousQueue", "ThreadPoolExecutor"):
        if banned in runtime_text:
            raise RuntimeError(f"owned Baritone override contains banned runtime feature {banned}")
    mixin = OUT / "launch" / "java" / PKG / "launch" / "mixins" / "MixinMinecraft.java"
    if "postInit" in mixin.read_text(encoding="utf-8"):
        raise RuntimeError("legacy eager provider bootstrap survived MixinMinecraft override")
    block_meta = OUT / "api" / "java" / PKG / "api" / "utils" / "BlockOptionalMeta.java"
    block_meta_text = block_meta.read_text(encoding="utf-8")
    if "ForkJoinPool.commonPool()" in block_meta_text:
        raise RuntimeError("BlockOptionalMeta uses a shared common-pool executor")
    if SOURCE_LOCK["minecraft_api_family"] == "1.21.11" and block_meta_text.count("Runnable::run") != 1:
        raise RuntimeError("1.21.11 BlockOptionalMeta inline executor transform is missing")
    if any(path.name == "fabric.mod.json" for path in OUT.rglob("*")):
        raise RuntimeError("Fabric mod entrypoint metadata was included in the owned kernel source stage")
    mixin_config = json.loads((OUT / "launch" / "resources" / "mixins.lodekeeper-kernel.json").read_text(encoding="utf-8"))
    mixin_dir = OUT / "launch" / "java" / PKG / "launch" / "mixins"
    missing_mixins = [name for name in mixin_config.get("client", []) if not (mixin_dir / f"{name}.java").is_file()]
    if missing_mixins:
        raise RuntimeError(f"mixin config names missing source classes: {missing_mixins}")
    if any(name in mixin_config.get("client", []) for name in ("MixinCommandSuggestionHelper", "MixinFireworkRocketEntity", "MixinScreen")):
        raise RuntimeError("unsupported upstream mixin survived the owned mixin config filter")
    license_dir = OUT / "main" / "resources" / "META-INF" / "licenses"
    if not (license_dir / "BARITONE-LICENSE.txt").is_file() or not (license_dir / "BARITONE-LICENSE-Part-2.jpg").is_file():
        raise RuntimeError("pinned Baritone license notices are missing from generated resources")
    search = OUT / "main" / "java" / PKG / "behavior" / "PathingBehavior.java"
    search_text = search.read_text(encoding="utf-8")
    if ("drainSearchCompletion()" not in search_text
            or search_text.index("drainSearchCompletion()") > search_text.index(".preTick()")):
        raise RuntimeError("search completion is not drained before PathingBehavior preTick")
    snapshots = OUT / "main" / "java" / PKG / "snapshot" / "OwnedWorldSnapshots.java"
    snapshots_text = snapshots.read_text(encoding="utf-8")
    if "CAPACITY = 64" not in snapshots_text or "INTEREST_RADIUS = 3" not in snapshots_text:
        raise RuntimeError("owned snapshot capacity or interest radius changed")
    scheduler = OUT / "main" / "java" / PKG / "OwnedWorkScheduler.java"
    scheduler_text = scheduler.read_text(encoding="utf-8")
    if ("lane(\"search\", 1)" not in scheduler_text
            or "lane(\"maintenance\", 64)" not in scheduler_text
            or "new ThreadPoolExecutor(1, 1" not in scheduler_text
            or "new ArrayBlockingQueue<>(capacity)" not in scheduler_text):
        raise RuntimeError("owned worker scheduler lost its one-thread lanes or bounded queues")
    guard = OUT / "main" / "java" / PKG / "OwnedMutationGuard.java"
    guard_text = guard.read_text(encoding="utf-8")
    required_guard_markers = (
        "MAX_GRAVITY_CELLS = 16",
        "state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)",
        "state.getBlock() instanceof BedBlock",
        "!belowState.getFluidState().isEmpty()",
        "policy.mayBreak(cell)",
        "policy.mayPlace(above)",
        "policy.mayPlace(pos.above())",
        "policy.mayPlace(pos.below())",
        "Direction.Plane.HORIZONTAL",
    )
    if any(marker not in guard_text for marker in required_guard_markers):
        raise RuntimeError("owned edit guard lost its multi-block, gravity, fluid, or placement-footprint checks")
    runtime = OUT / "main" / "java" / PKG / "OwnedKernelRuntime.java"
    runtime_text = runtime.read_text(encoding="utf-8")
    if ("public boolean isCurrent(Session expected)" not in runtime_text
            or "worldEditPolicy = WorldEditPolicy.denyAll()" not in runtime_text
            or "public void requireMainThread()" not in runtime_text):
        raise RuntimeError("owned session or main-thread boundary no longer fails closed")
    snapshot_store = OUT / "main" / "java" / PKG / "snapshot" / "OwnedWorldSnapshots.java"
    snapshot_text = snapshot_store.read_text(encoding="utf-8")
    if ("publishIfCurrent" not in snapshot_text or "owner.isCurrent(snapshot.session())" not in snapshot_text):
        raise RuntimeError("owned snapshots can publish data from a stale world session")
    final_flush = OUT / "main" / "java" / PKG / "OwnedFinalFlush.java"
    final_flush_text = final_flush.read_text(encoding="utf-8")
    if "finishAfterWorkersStop" not in final_flush_text or "workers.isStopped()" not in final_flush_text:
        raise RuntimeError("owned final cache flush no longer waits for worker shutdown")
    mine = OUT / "main" / "java" / PKG / "process" / "MineProcess.java"
    mine_text = mine.read_text(encoding="utf-8")
    if "implements IMineProcess, OwnedMiningTargets" not in mine_text or "OwnedMiningTargets.MAX_TARGETS" not in mine_text:
        raise RuntimeError("owned mining target retention interface or capacity is missing")
    for path in sources:
        text = path.read_text(encoding="utf-8")
        if path.parts[0] != "schematica_api" and re.search(r"(?m)^package baritone\.", text):
            raise RuntimeError(f"upstream package ownership rewrite failed in {path}")
        if path.name == "MixinLivingEntity.java" and any(
            marker in text.lower() for marker in ("elytra", "fallflying", "fall_flying")
        ):
            raise RuntimeError("fall-flying mechanics survived the generic living-entity mixin override")
    return len(sources)


def main() -> None:
    input_hashes = verify_inputs()
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    with tarfile.open(UPSTREAM_ARCHIVE, "r:gz") as archive:
        generate_upstream_sources(archive)
        write_owned_api_sources()
        write_mixin_config(archive)
        write_license(archive)
    overrides = apply_lifecycle_overrides()
    source_count = verify_generated_sources()
    write_source_manifest(input_hashes, overrides)
    print(f"Prepared {source_count} owned Java sources for Minecraft API family {SOURCE_LOCK['minecraft_api_family']} under {OUT}")
    print("PASS pinned source archive, dependency metadata, and family diff manifest")
    print(f"PASS applied {len(overrides)} hash-pinned family lifecycle overrides last; required owned runtime inputs are present")
    print("PASS excluded entrypoint, command, Nether pathfinder, and flight mechanics; kept only an inert fail-closed API stub")
    print("PASS BlockOptionalMeta has no shared common-pool executor")
    print("PASS bounded scheduler, 64-snapshot cache, radius-3 interest, mining targets, and preTick completion drain")
    print("PENDING Java 21 compilation and client runtime checks")


if __name__ == "__main__":
    main()
