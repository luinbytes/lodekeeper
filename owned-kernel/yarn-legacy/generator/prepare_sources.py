from __future__ import annotations

import json
import argparse
import hashlib
import re
import shutil
import tarfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--profile", required=True)
parser.add_argument("--source-root", required=True)
parser.add_argument("--archive", type=Path)
parser.add_argument("--output", type=Path)
parser.add_argument("--lock-file", type=Path)
parser.add_argument("--lifecycle-overrides", type=Path)
parser.add_argument("--base-only", action="store_true")
args = parser.parse_args()
LOCK_PATH = args.lock_file.resolve() if args.lock_file is not None else ROOT / "locks" / f"{args.profile}.json"
LOCK = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
UPSTREAM = Path(args.source_root).resolve()
MODULE = ROOT / "modules" / args.profile
OUT = (args.output or (MODULE / "src")).resolve()
CANONICAL_GROUP = args.lifecycle_overrides.resolve() if args.lifecycle_overrides is not None else None
CANONICAL_MODE = CANONICAL_GROUP is not None
SHARED_OVERRIDES = (CANONICAL_GROUP / "lifecycle-overrides") if CANONICAL_MODE else ROOT / "overrides" / "shared"
PROFILE_OVERRIDES = ROOT / "overrides" / args.profile
SHARED_OVERRIDE_MANIFEST = (
    CANONICAL_GROUP / "lifecycle-overrides.sha256" if CANONICAL_MODE
    else ROOT / "overrides" / "shared.sha256"
)
PROFILE_OVERRIDE_MANIFEST = ROOT / "overrides" / f"{args.profile}.sha256"
PKG = Path("dev/lodekeeper/navigation/kernel")

if UPSTREAM.name != LOCK["source_root"]:
    raise SystemExit(f"Expected pinned Baritone source root {LOCK['source_root']}, got {UPSTREAM.name}")
if args.archive is not None:
    archive_digest = hashlib.sha256(args.archive.read_bytes()).hexdigest()
    if archive_digest != LOCK["archive_sha256"]:
        raise SystemExit(
            f"Pinned Baritone source archive hash mismatch: expected {LOCK['archive_sha256']}, got {archive_digest}"
        )
    archive_files = {}
    with tarfile.open(args.archive, "r:gz") as archive:
        for member in archive.getmembers():
            member_path = PurePosixPath(member.name)
            if member_path.is_absolute() or ".." in member_path.parts:
                raise SystemExit(f"Unsafe path in pinned Baritone source archive: {member.name}")
            if member.issym() or member.islnk():
                raise SystemExit(f"Unexpected link in pinned Baritone source archive: {member.name}")
            if not member.isfile():
                continue
            if not member_path.parts or member_path.parts[0] != LOCK["source_root"]:
                raise SystemExit(f"Unexpected top-level path in pinned source archive: {member.name}")
            source = archive.extractfile(member)
            if source is None:
                raise SystemExit(f"Could not read pinned archive member: {member.name}")
            relative = PurePosixPath(*member_path.parts[1:]).as_posix()
            archive_files[relative] = hashlib.sha256(source.read()).hexdigest()
    disk_files = {}
    for source in UPSTREAM.rglob("*"):
        if source.is_symlink():
            raise SystemExit(f"Unexpected symlink in extracted Baritone source: {source}")
        if source.is_file():
            disk_files[source.relative_to(UPSTREAM).as_posix()] = hashlib.sha256(source.read_bytes()).hexdigest()
    if archive_files != disk_files:
        missing = sorted(set(archive_files) - set(disk_files))
        extra = sorted(set(disk_files) - set(archive_files))
        changed = sorted(
            rel for rel in set(archive_files) & set(disk_files)
            if archive_files[rel] != disk_files[rel]
        )
        raise SystemExit(
            f"Extracted Baritone source differs from pinned archive; missing={missing}, extra={extra}, changed={changed}"
        )
elif CANONICAL_MODE:
    raise SystemExit("Canonical per-group source preparation requires --archive for source-tree verification")

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
    raise SystemExit("Pinned Baritone source archive is missing required inputs: " + ", ".join(missing_inputs))

def read_override_manifest(directory: Path, manifest: Path) -> dict[str, str]:
    expected = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        digest, rel = line.split("  ", 1)
        expected[rel] = digest
    actual = {
        path.relative_to(directory).as_posix()
        for path in directory.rglob("*.java")
    }
    if actual != set(expected):
        missing = sorted(set(expected) - actual)
        extra = sorted(actual - set(expected))
        raise SystemExit(f"Override set differs from its manifest; missing={missing}, extra={extra}")
    for rel, expected_digest in expected.items():
        actual_digest = hashlib.sha256((directory / rel).read_bytes()).hexdigest()
        if actual_digest != expected_digest:
            raise SystemExit(
                f"Override hash mismatch for {directory.name}/{rel}: expected {expected_digest}, got {actual_digest}"
            )
    return expected


if args.base_only:
    shared_override_hashes = {}
    profile_override_hashes = {}
    source_adapter_hashes = {}
else:
    shared_override_hashes = read_override_manifest(SHARED_OVERRIDES, SHARED_OVERRIDE_MANIFEST)
    if CANONICAL_MODE:
        expected_manifest_hash = LOCK.get("effective_lifecycle_overrides", {}).get("manifest_sha256")
        actual_manifest_hash = hashlib.sha256(SHARED_OVERRIDE_MANIFEST.read_bytes()).hexdigest()
        if expected_manifest_hash != actual_manifest_hash:
            raise SystemExit("Effective lifecycle override manifest hash mismatch")
        profile_override_hashes = {}
        source_adapter_hashes = LOCK.get("source_adapters", {})
    else:
        profile_override_hashes = read_override_manifest(PROFILE_OVERRIDES, PROFILE_OVERRIDE_MANIFEST)
        input_inventory_path = ROOT / LOCK["source_inputs_file"]
        if hashlib.sha256(input_inventory_path.read_bytes()).hexdigest() != LOCK["source_inputs_sha256"]:
            raise SystemExit("Pinned source-input inventory hash mismatch")
        input_inventory = json.loads(input_inventory_path.read_text(encoding="utf-8"))
        baseline_inventory_path = ROOT / LOCK["shared_source_baseline_file"]
        if hashlib.sha256(baseline_inventory_path.read_bytes()).hexdigest() != LOCK["shared_source_baseline_sha256"]:
            raise SystemExit("Shared source-baseline inventory hash mismatch")
        baseline_inventory = json.loads(baseline_inventory_path.read_text(encoding="utf-8"))
        source_adapter_hashes = {}

if OUT.exists():
    shutil.rmtree(OUT)

GROUPS = {
    "api": UPSTREAM / "src/api/java",
    "main": UPSTREAM / "src/main/java",
    "launch": UPSTREAM / "src/launch/java",
    "schematica_api": UPSTREAM / "src/schematica_api/java",
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


def patch_baritone(text: str) -> str:
    for line in (
        "import dev.lodekeeper.navigation.kernel.command.manager.CommandManager;\n",
        "import dev.lodekeeper.navigation.kernel.utils.GuiClick;\n",
        "import java.util.concurrent.SynchronousQueue;\n",
        "import java.util.concurrent.ThreadPoolExecutor;\n",
        "import java.util.concurrent.TimeUnit;\n",
    ):
        text = text.replace(line, "")
    text = text.replace(
        "import java.util.concurrent.Executor;\n",
        "import java.util.concurrent.Executor;\n"
        "import dev.lodekeeper.navigation.kernel.process.NullElytraProcess;\n"
        "import dev.lodekeeper.navigation.kernel.WorldEditPolicy;\n",
    )
    text = re.sub(
        r"    private static final ThreadPoolExecutor threadPool;\s*"
        r"    static \{\s*threadPool = new ThreadPoolExecutor\(4, Integer\.MAX_VALUE, 60L, TimeUnit\.SECONDS, new SynchronousQueue<>\(\)\);\s*\}",
        "",
        text,
        count=1,
    )
    text = text.replace("    private final CommandManager commandManager;\n", "")
    text = text.replace('resolve("baritone")', 'resolve("lodekeeper")')
    text = text.replace("Baritone(Minecraft mc) {", "Baritone(Minecraft mc, WorldEditPolicy worldEditPolicy) {\n        this.worldEditPolicy = worldEditPolicy;")
    text = text.replace("    private final WorldProvider worldProvider;\n", "    private final WorldProvider worldProvider;\n    private volatile WorldEditPolicy worldEditPolicy;\n")
    text = text.replace("        this.commandManager = new CommandManager(this);\n", "")
    text = text.replace("            this.registerBehavior(WaypointBehavior::new);\n", "")
    text = text.replace(
        "    @Override\n    public CommandManager getCommandManager() {\n"
        "        return this.commandManager;\n    }\n\n",
        "",
    )
    text = re.sub(
        r"    @Override\n    public void openClick\(\) \{.*?\n    \}\n\n",
        "",
        text,
        flags=re.S,
        count=1,
    )
    text = text.replace("return OwnedKernelAPI.getSettings();", "return OwnedKernelAPI.getSettings();")
    text = text.replace("return threadPool;", "return OwnedKernelRuntime.getExecutor();")
    text = text.replace("    public Path getDirectory() {\n", "    public WorldEditPolicy getWorldEditPolicy() {\n        return worldEditPolicy;\n    }\n\n    public void updateWorldEditPolicy(WorldEditPolicy policy) {\n        this.worldEditPolicy = policy;\n    }\n\n    public Path getDirectory() {\n")
    if "ThreadPoolExecutor threadPool" in text or "new SynchronousQueue" in text:
        raise RuntimeError("unbounded upstream executor survived Baritone patch")
    if "CommandManager" in text or "openClick" in text:
        raise RuntimeError("command control survived Baritone patch")
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


def adapt_owned_kernel_for_source_family() -> dict[str, str]:
    """Use the pinned Baritone enchantment query on every supported MC API family."""
    rel = Path("main/java/dev/lodekeeper/navigation/kernel/OwnedMutationGuard.java")
    target = OUT / rel
    text = target.read_text(encoding="utf-8")
    legacy = (
        "import net.minecraft.world.item.enchantment.Enchantments;\n"
        "import net.minecraft.world.level.block.CropBlock;\n"
    )
    if legacy not in text:
        raise RuntimeError("OwnedMutationGuard import block changed; update the source-family adapter")
    text = text.replace(
        legacy,
        "import net.minecraft.world.item.enchantment.Enchantments;\n"
        "import net.minecraft.world.item.enchantment.EnchantmentHelper;\n"
        "import net.minecraft.world.level.block.CropBlock;\n",
        1,
    )
    component_api = (
        "        for (EquipmentSlot slot : EquipmentSlot.values()) {\n"
        "            for (var enchantment : player.getItemBySlot(slot).getEnchantments().keySet()) {\n"
        "                if (enchantment.is(Enchantments.FROST_WALKER)) { return false; }\n"
        "            }\n"
        "        }\n"
    )
    helper_api = (
        "        for (EquipmentSlot slot : EquipmentSlot.values()) {\n"
        "            if (EnchantmentHelper.getItemEnchantmentLevel(\n"
        "                    Enchantments.FROST_WALKER, player.getItemBySlot(slot)) > 0) { return false; }\n"
        "        }\n"
    )
    if text.count(component_api) != 1:
        raise RuntimeError("OwnedMutationGuard enchantment query changed; update the source-family adapter")
    target.write_text(text.replace(component_api, helper_api, 1), encoding="utf-8")
    return {rel.as_posix(): hashlib.sha256(target.read_bytes()).hexdigest()}


def put_source(group: str, source: Path) -> None:
    rel = source.relative_to(GROUPS[group])
    if excluded(group, rel):
        return
    dest_rel = owned_path(group, rel)
    text = source.read_text(encoding="utf-8")
    if group != "schematica_api":
        text = rewrite_source(text)
    if rel.as_posix() == "baritone/Baritone.java":
        text = patch_baritone(text)
    elif rel.as_posix() == "baritone/behavior/PathingBehavior.java":
        text = text.replace("import dev.lodekeeper.navigation.kernel.process.ElytraProcess;\n", "")
    elif rel.as_posix() == "baritone/api/IBaritone.java":
        text = patch_i_baritone(text)
    elif rel.as_posix() == "baritone/utils/PathRenderer.java":
        obsolete_gui_branch = (
            "        if (ctx.minecraft().screen instanceof GuiClick) {\n"
            "            ((GuiClick) ctx.minecraft().screen).onRender(event.getModelViewStack(), event.getProjectionMatrix());\n"
            "        }\n"
        )
        if obsolete_gui_branch not in text:
            raise RuntimeError("could not remove PathRenderer custom-GUI branch")
        text = text.replace(obsolete_gui_branch, "", 1)
    elif rel.as_posix() == "baritone/launch/mixins/MixinMinecraft.java":
        text = patch_minecraft_mixin(text)
    elif rel.as_posix() == "baritone/process/elytra/NullElytraProcess.java":
        text = text.replace(
            "package dev.lodekeeper.navigation.kernel.process.elytra;",
            "package dev.lodekeeper.navigation.kernel.process;",
        )
        dest_rel = PKG / "process" / "NullElytraProcess.java"
    if group == "schematica_api":
        target = OUT / "schematica_api" / "java" / dest_rel
    else:
        target = OUT / group / "java" / dest_rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


for group, source_root in GROUPS.items():
    for source in sorted(source_root.rglob("*.java")):
        put_source(group, source)

if not args.base_only and not CANONICAL_MODE:
    if set(input_inventory["generated_sources"]) != set(shared_override_hashes):
        raise SystemExit("Pinned source-input inventory does not cover the shared override set")
    if set(baseline_inventory["generated_sources"]) != set(shared_override_hashes):
        raise SystemExit("Shared source-baseline inventory does not cover the shared override set")
    source_hashes = {}
    for rel, expected in input_inventory["generated_sources"].items():
        source = OUT / rel
        actual = hashlib.sha256(source.read_bytes()).hexdigest() if source.is_file() else None
        if actual != expected:
            raise SystemExit(
                f"Pinned transformed source mismatch for {args.profile}/{rel}: expected {expected}, got {actual}"
            )
        source_hashes[rel] = actual

    for rel in shared_override_hashes:
        baseline_hash = baseline_inventory["generated_sources"].get(rel)
        target_hash = source_hashes.get(rel)
        if target_hash != baseline_hash and rel not in profile_override_hashes:
            raise SystemExit(
                f"Pinned source differs from shared override base without a profile port: {args.profile}/{rel}"
            )

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

# Keep only the licensed source notice, not upstream loader metadata.
resources = OUT / "launch" / "resources"
resources.mkdir(parents=True, exist_ok=True)
config = json.loads(
    (UPSTREAM / "src/launch/resources/mixins.baritone.json").read_text(encoding="utf-8")
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

license_dir = OUT / "main" / "resources" / "META-INF" / "licenses"
license_dir.mkdir(parents=True, exist_ok=True)
shutil.copy2(UPSTREAM / "LICENSE", license_dir / "BARITONE-LICENSE.txt")
shutil.copy2(UPSTREAM / "LICENSE-Part-2.jpg", license_dir / "BARITONE-LICENSE-Part-2.jpg")

if not args.base_only:
    for source in sorted(SHARED_OVERRIDES.rglob("*.java")):
        target = OUT / source.relative_to(SHARED_OVERRIDES)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    if not CANONICAL_MODE:
        for source in sorted(PROFILE_OVERRIDES.rglob("*.java")):
            target = OUT / source.relative_to(PROFILE_OVERRIDES)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    for rel, expected in source_adapter_hashes.items():
        target = OUT / rel
        actual = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else None
        if actual != expected:
            raise SystemExit(f"Source-family adapter hash mismatch for {args.profile}/{rel}")

if not args.base_only and not CANONICAL_MODE:
    source_adapter_hashes = adapt_owned_kernel_for_source_family()

generated_files = {
    path.relative_to(OUT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
    for path in sorted(OUT.rglob("*"))
    if path.is_file()
}
manifest = {
    "schema_version": 2,
    "profile": args.profile,
    "generation_mode": "base_only" if args.base_only else "owned",
    "minecraft_versions": LOCK["minecraft_versions"],
    "java_version": LOCK["java_version"],
    "upstream": {
        "repository": LOCK["repository"],
        "version": LOCK["baritone_version"],
        "commit": LOCK["source_commit"],
        "archive_sha256": LOCK["archive_sha256"],
        "dependency_artifact_sha256": LOCK["dependency_artifact_sha256"],
        "license": "LGPL-3.0-or-later",
    },
    "lifecycle_overrides": (
        {"effective": shared_override_hashes} if CANONICAL_MODE else {
            "shared": shared_override_hashes,
            "profile": profile_override_hashes,
        }
    ),
    "source_adapters": source_adapter_hashes,
    "generated_files": generated_files,
    "mixin_config": "launch/resources/mixins.lodekeeper-kernel.json",
    "refmap": "launch-lodekeeper-owned-kernel-refmap.json",
}
(OUT.parent / "source-manifest.json").write_text(
    json.dumps(manifest, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)

print(f"Prepared {sum(1 for _ in OUT.rglob('*.java'))} Java sources under {OUT}")
