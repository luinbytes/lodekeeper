#!/usr/bin/env python3
"""Run the Loom verification profile with production classes loaded from one jar."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from typing import Any
from urllib.parse import unquote, urlparse
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
MOD_ID = "lodekeeper"
VERIFY_PREFIX = "lodekeeper.verify."
MAIN_CLASS = "net.fabricmc.devlaunchinjector.Main"
FOREIGN_KERNEL_PREFIXES = ("baritone/", "com/github/cabaletta/baritone/")
MAX_RENDER_DISTANCE = 8
MAX_SIMULATION_DISTANCE = 5
MAX_FPS = 30
MAX_HEAP = "1536m"
ACTIVE_PROCESSORS = "2"
DEFAULT_OPTIONS = {
    "renderDistance": "2",
    "simulationDistance": str(MAX_SIMULATION_DISTANCE),
    "maxFps": str(MAX_FPS),
    "fullscreen": "false",
    "enableVsync": "true",
    "guiScale": "2",
    "soundCategory_master": "0.0",
    "particles": "2",
    "clouds": "false",
    "entityDistanceScaling": "0.5",
    "useNativeTransport": "false",
    "pauseOnLostFocus": "false",
}
STATION_LEFT = re.compile(r"\bOWNED_STATION_LEFT\b")
STATION_RECOVERED = re.compile(r"\bOWNED_STATION_RECOVERED\b")
CLASS_LOAD = re.compile(
    r"^\[[^\]]+\]\[[^\]]+\]\[[^\]]+\]\s+(\S+)\s+source:\s+(.+)$"
)


@dataclasses.dataclass(frozen=True)
class RunSpec:
    module: str
    minecraft_version: str
    profile: str
    java: Path
    production_jar: Path
    output_dir: Path
    launch_args: Path
    launch_config: Path
    verify_flags: tuple[str, ...]
    timeout_seconds: int
    require_station_cleanup: bool
    plan_only: bool


@dataclasses.dataclass(frozen=True)
class LaunchPlan:
    argv: tuple[str, ...]
    classpath: tuple[Path, ...]
    removed_production_outputs: tuple[str, ...]
    verifier_entries: tuple[Path, ...]
    source_run_dir: Path
    launch_metadata: Path | None
    output_run_dir: Path
    prepared_config: Path
    class_load_log: Path
    effective_flags: tuple[str, ...]
    options_file: Path
    render_distance: int


class VerificationError(RuntimeError):
    pass


def parse_args(argv: list[str]) -> RunSpec:
    parser = argparse.ArgumentParser(
        description=(
            "Run an isolated Loom verification profile while loading production "
            "classes from the exact supplied Fabric jar."
        ),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
        epilog=(
            "Each --verify-flag sets one lodekeeper.verify.* JVM property. "
            "The run directory is created under --output-dir and receives a "
            "private options.txt with renderDistance capped at 8."
        ),
    )
    parser.add_argument("--module", required=True, help="Gradle module path, such as fabric-modern")
    parser.add_argument("--version", required=True, dest="minecraft_version", help="Minecraft version compiled into the jar")
    parser.add_argument("--profile", required=True, help="Loom run profile name, such as verificationClient")
    parser.add_argument("--java", required=True, type=Path, help="Java executable for the matching toolchain")
    parser.add_argument("--production-jar", required=True, type=Path, help="Final production Fabric jar under verification")
    parser.add_argument("--output-dir", required=True, type=Path, help="New isolated directory for this client and its evidence")
    parser.add_argument(
        "--launch-args",
        type=Path,
        help="Loom-generated launch.args; defaults to the module's unique verification-launch/launch.args",
    )
    parser.add_argument(
        "--launch-config",
        type=Path,
        help="Loom-generated launch.cfg; defaults to the sibling of --launch-args",
    )
    parser.add_argument(
        "--verify-flag",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="Set one lodekeeper.verify.* JVM property; repeat for multiple flags",
    )
    parser.add_argument("--timeout-seconds", type=int, default=900, help="Maximum client lifetime")
    parser.add_argument(
        "--require-station-cleanup",
        action="store_true",
        help="Fail unless a recovered station is logged and no OWNED_STATION_LEFT is logged",
    )
    parser.add_argument(
        "--plan-only",
        action="store_true",
        help="Validate and write the isolated launch plan without starting Java",
    )
    args = parser.parse_args(argv)

    module = args.module.strip().strip("/")
    if not module or Path(module).is_absolute() or ".." in Path(module).parts:
        parser.error("--module must be a repository-relative module path")
    output_dir = args.output_dir.expanduser().resolve()
    launch_args = args.launch_args.expanduser().resolve() if args.launch_args else None
    if launch_args is None:
        launch_args = discover_launch_args(REPO_ROOT / module)
    launch_config = args.launch_config.expanduser().resolve() if args.launch_config else launch_args.with_name("launch.cfg")
    java = args.java.expanduser().resolve()
    production_jar = args.production_jar.expanduser().resolve()
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be greater than zero")

    return RunSpec(
        module=module,
        minecraft_version=args.minecraft_version,
        profile=args.profile,
        java=java,
        production_jar=production_jar,
        output_dir=output_dir,
        launch_args=launch_args,
        launch_config=launch_config,
        verify_flags=tuple(args.verify_flag),
        timeout_seconds=args.timeout_seconds,
        require_station_cleanup=args.require_station_cleanup,
        plan_only=args.plan_only,
    )


def discover_launch_args(module_dir: Path) -> Path:
    if not module_dir.is_dir():
        raise VerificationError(f"module directory does not exist: {module_dir}")
    exported = (module_dir / "build" / "verification-launch" / "launch.args").resolve()
    if is_within(exported, module_dir.resolve()) and exported.is_file():
        return exported
    candidates = [
        path
        for path in module_dir.rglob("verification-launch/launch.args")
        if "build" not in path.relative_to(module_dir).parts
    ]
    if len(candidates) != 1:
        rendered = ", ".join(str(path) for path in candidates) or "none found"
        raise VerificationError(
            f"expected one Loom launch.args under {module_dir}; found {rendered}. "
            "Pass --launch-args for the current profile."
        )
    return candidates[0].resolve()


def require_file(path: Path, label: str, *, executable: bool = False) -> Path:
    if not path.is_file():
        raise VerificationError(f"{label} does not exist or is not a file: {path}")
    if executable and not os.access(path, os.X_OK):
        raise VerificationError(f"{label} is not executable: {path}")
    return path


def is_within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def validate_output_dir(path: Path) -> Path:
    root = REPO_ROOT.resolve()
    if is_within(path, root):
        raise VerificationError(f"--output-dir must be outside the repository to protect normal launcher data: {path}")
    if path.exists() and (not path.is_dir() or any(path.iterdir())):
        raise VerificationError(f"--output-dir must be new or empty; existing contents are preserved: {path}")
    path.mkdir(parents=True, exist_ok=True)
    return path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_state() -> dict[str, Any]:
    def git(*args: str) -> tuple[int, str]:
        result = subprocess.run(
            ["git", *args],
            cwd=REPO_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        return result.returncode, result.stdout.rstrip("\r\n")

    commit_status, commit = git("rev-parse", "HEAD")
    dirty_status, dirty_output = git("status", "--porcelain=v1", "--untracked-files=all")
    dirty_paths = [line[3:] for line in dirty_output.splitlines() if len(line) >= 4]
    return {
        "commit": commit if commit_status == 0 else None,
        "dirty": bool(dirty_paths) if dirty_status == 0 else None,
        "dirtyPaths": dirty_paths if dirty_status == 0 else [],
        "gitStatusError": dirty_status if dirty_status != 0 else None,
    }


def inspect_production_jar(path: Path, minecraft_version: str) -> dict[str, Any]:
    require_file(path, "production jar")
    try:
        with zipfile.ZipFile(path) as jar:
            manifest = json.loads(jar.read("fabric.mod.json"))
            entries = set(jar.namelist())
    except (OSError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        raise VerificationError(f"cannot inspect production Fabric jar {path}: {error}") from error
    if manifest.get("id") != MOD_ID:
        raise VerificationError(f"production jar mod id must be {MOD_ID!r}; found {manifest.get('id')!r}")
    if any(name.startswith(FOREIGN_KERNEL_PREFIXES) or name.endswith(".jar") for name in entries):
        raise VerificationError("production jar contains an external navigation kernel or nested jar")
    declared_minecraft = manifest.get("depends", {}).get("minecraft")
    if declared_minecraft != minecraft_version:
        raise VerificationError(
            f"production jar declares Minecraft {declared_minecraft!r}, "
            f"but --version requested {minecraft_version!r}"
        )
    production_classes = sorted(
        name for name in entries
        if name.startswith("dev/lodekeeper/fabric/") and name.endswith("/AutomationEngine.class")
    )
    if not production_classes:
        raise VerificationError("production jar has no packaged Lodekeeper AutomationEngine class")
    return {
        "modId": manifest.get("id"),
        "modVersion": manifest.get("version"),
        "minecraftDependency": declared_minecraft,
        "productionClassEntries": production_classes,
    }


def normalize_verify_flags(flags: tuple[str, ...], artifact_sha: str) -> tuple[str, ...]:
    parsed: dict[str, str] = {}
    for raw in flags:
        value = raw.strip()
        if value.startswith("-D"):
            value = value[2:]
        name, separator, setting = value.partition("=")
        if not separator or not name.startswith(VERIFY_PREFIX):
            raise VerificationError(f"invalid --verify-flag {raw!r}; use {VERIFY_PREFIX}<name>=<value>")
        if name in parsed:
            raise VerificationError(f"duplicate verification property: {name}")
        parsed[name] = setting
    sha_key = f"{VERIFY_PREFIX}candidateSha256"
    if sha_key in parsed and parsed[sha_key] != artifact_sha:
        raise VerificationError("candidateSha256 must match the supplied production jar")
    parsed[sha_key] = artifact_sha
    parsed.setdefault(f"{VERIFY_PREFIX.rstrip('.')}", "true")
    return tuple(f"-D{name}={setting}" for name, setting in parsed.items())


def is_production_development_output(path: Path) -> bool:
    if path.is_file() and zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            if any(name.startswith(("dev/lodekeeper/core/", "dev/lodekeeper/nav/",
                                    "dev/lodekeeper/navigation/kernel/"))
                   or name.endswith("/AutomationEngine.class") for name in archive.namelist()):
                return True
    parts = path.parts
    if "lodekeeper" not in parts:
        return False
    build_positions = [index for index, part in enumerate(parts) if part == "build"]
    for index in build_positions:
        suffix = parts[index + 1 :]
        if "owned-kernel" in parts[:index] and suffix and suffix[0] in {"classes", "resources"}:
            return True
        if len(suffix) >= 3 and suffix[0] == "classes" and suffix[2] == "main":
            return True
        if len(suffix) >= 2 and suffix[0] == "resources" and suffix[1] == "main":
            return True
    return False


def parse_classpath(value: str, label: str, *, require_nonempty: bool = True) -> tuple[list[Path], list[str]]:
    raw_entries = [entry for entry in value.split(os.pathsep) if entry]
    retained: list[Path] = []
    removed: list[str] = []
    for raw in raw_entries:
        path = Path(raw).expanduser()
        if not path.is_absolute():
            path = (REPO_ROOT / path).resolve()
        else:
            path = path.resolve()
        if is_production_development_output(path):
            removed.append(str(path))
        else:
            if not path.exists():
                raise VerificationError(f"{label} entry does not exist: {path}")
            reject_foreign_kernel(path)
            retained.append(path)
    if require_nonempty and not retained:
        raise VerificationError(f"{label} is empty after removing production development outputs")
    return retained, removed


def reject_foreign_kernel(path: Path) -> None:
    if path.is_dir():
        if any((path / prefix).is_dir() for prefix in FOREIGN_KERNEL_PREFIXES):
            raise VerificationError(f"external Baritone classes on launch classpath: {path}")
        return
    if not zipfile.is_zipfile(path):
        return
    with zipfile.ZipFile(path) as archive:
        if any(name.startswith(FOREIGN_KERNEL_PREFIXES) for name in archive.namelist()):
            raise VerificationError(f"external Baritone classes on launch classpath: {path}")
        if "fabric.mod.json" in archive.namelist():
            metadata = json.loads(archive.read("fabric.mod.json"))
            if str(metadata.get("id", "")).lower().startswith("baritone"):
                raise VerificationError(f"separate Baritone mod on launch classpath: {path}")


def contains_class(path: Path, class_name: str) -> bool:
    entry_name = class_name.replace(".", "/") + ".class"
    if path.is_dir():
        return (path / entry_name).is_file()
    if path.is_file() and zipfile.is_zipfile(path):
        try:
            with zipfile.ZipFile(path) as archive:
                return entry_name in archive.namelist()
        except (OSError, zipfile.BadZipFile):
            return False
    return False


def locate_verifier_entries(classpath: tuple[Path, ...]) -> tuple[Path, ...]:
    required_classes = (
        "dev.lodekeeper.fabric.RuntimeVerification",
        "dev.lodekeeper.fabric.modern.RuntimeVerification",
        "dev.lodekeeper.fabric.VerificationApi",
        "dev.lodekeeper.fabric.modern.VerificationApi",
    )
    verifier_entries = tuple(
        entry for entry in classpath
        if any(contains_class(entry, class_name) for class_name in required_classes)
    )
    found = {
        name
        for entry in verifier_entries
        for name in required_classes
        if contains_class(entry, name)
    }
    if not verifier_entries:
        raise VerificationError("launch classpath has no separate RuntimeVerification or VerificationApi output")
    if not any(name.endswith("RuntimeVerification") for name in found):
        raise VerificationError("launch classpath does not contain RuntimeVerification classes")
    if not any(name.endswith("VerificationApi") for name in found):
        raise VerificationError("launch classpath does not contain VerificationApi classes")
    return verifier_entries


def rewrite_classpath_groups(config_text: str) -> tuple[str, list[str], list[str]]:
    changed_lines: list[str] = []
    removed: list[str] = []
    retained_groups: list[str] = []
    found_property = False
    for line in config_text.splitlines():
        if line.startswith("\tfabric.classPathGroups="):
            found_property = True
            prefix, value = line.split("=", 1)
            groups: list[str] = []
            for group in value.split("::"):
                if not group:
                    continue
                entries, dropped = parse_classpath(group, "fabric.classPathGroups", require_nonempty=False)
                if entries:
                    groups.append(os.pathsep.join(str(entry) for entry in entries))
                removed.extend(dropped)
            retained_groups = [group for group in groups if group]
            if retained_groups:
                changed_lines.append(prefix + "=" + "::".join(retained_groups))
            continue
        changed_lines.append(line)
    if not found_property:
        raise VerificationError("launch.cfg is missing fabric.classPathGroups")
    return "\n".join(changed_lines) + "\n", removed, retained_groups


def verify_class_groups(config_text: str, verifier_entries: tuple[Path, ...]) -> None:
    groups_value = None
    for line in config_text.splitlines():
        if line.startswith("\tfabric.classPathGroups="):
            groups_value = line.split("=", 1)[1]
            break
    if not groups_value:
        raise VerificationError("prepared launch.cfg has no verifier classPathGroups")
    entries = {
        Path(raw).resolve()
        for group in groups_value.split("::")
        for raw in group.split(os.pathsep)
        if raw
    }
    if not entries.intersection(verifier_entries):
        raise VerificationError("prepared fabric.classPathGroups no longer contains verifier output")


def parse_options(source: Path, destination: Path) -> int:
    lines = source.read_text(encoding="utf-8").splitlines() if source.is_file() else []
    seen: set[str] = set()
    output: list[str] = []
    for line in lines:
        name, separator, raw = line.partition(":")
        if separator and name in DEFAULT_OPTIONS:
            if name in seen:
                continue
            seen.add(name)
            value = raw.strip()
            try:
                if name == "renderDistance":
                    value = str(min(MAX_RENDER_DISTANCE, max(2, int(value))))
                elif name == "simulationDistance":
                    value = str(min(MAX_SIMULATION_DISTANCE, max(2, int(value))))
                elif name == "maxFps":
                    value = str(min(MAX_FPS, max(10, int(value))))
                elif name == "entityDistanceScaling":
                    value = str(min(0.5, max(0.0, float(value))))
            except ValueError:
                value = DEFAULT_OPTIONS[name]
            if name in {"fullscreen", "enableVsync", "soundCategory_master", "clouds", "useNativeTransport", "pauseOnLostFocus"}:
                value = DEFAULT_OPTIONS[name]
            output.append(f"{name}:{value}")
        else:
            output.append(line)
    for name, value in DEFAULT_OPTIONS.items():
        if name not in seen:
            output.append(f"{name}:{value}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("\n".join(output) + "\n", encoding="utf-8")
    values = dict(line.partition(":")[::2] for line in output if ":" in line)
    distance = int(values["renderDistance"])
    if distance > MAX_RENDER_DISTANCE or int(values["simulationDistance"]) > MAX_SIMULATION_DISTANCE:
        raise VerificationError("render or simulation distance exceeds the configured cap")
    if int(values["maxFps"]) > MAX_FPS or values["pauseOnLostFocus"] != "false":
        raise VerificationError("frame rate or focus behavior exceeds the verification bounds")
    return distance


def build_launch_plan(spec: RunSpec, artifact_sha: str) -> LaunchPlan:
    module_dir = (REPO_ROOT / spec.module).resolve()
    if not is_within(module_dir, REPO_ROOT.resolve()) or not module_dir.is_dir():
        raise VerificationError(f"module must be an existing repository directory: {module_dir}")
    require_file(spec.java, "Java executable", executable=True)
    require_file(spec.launch_args, "Loom launch.args")
    require_file(spec.launch_config, "Loom launch.cfg")
    if not is_within(spec.launch_args, module_dir) or not is_within(spec.launch_config, module_dir):
        raise VerificationError("Loom launch.args and launch.cfg must come from the selected module in this checkout")

    raw_args = shlex.split(spec.launch_args.read_text(encoding="utf-8"), posix=True)
    if not raw_args:
        raise VerificationError("Loom launch.args is empty")
    if raw_args.count("-cp") + raw_args.count("-classpath") != 1:
        raise VerificationError("expected exactly one -cp or -classpath in Loom launch.args")
    cp_index = next(index for index, value in enumerate(raw_args) if value in {"-cp", "-classpath"})
    if cp_index + 1 >= len(raw_args):
        raise VerificationError("Loom launch.args has no classpath value")
    if MAIN_CLASS not in raw_args:
        raise VerificationError(f"Loom launch.args does not launch {MAIN_CLASS}")
    if not any(value == "-Dfabric.dli.env=client" for value in raw_args):
        raise VerificationError("Loom launch.args is not a client profile")
    if not any(value.startswith("-Dfabric.dli.main=") for value in raw_args):
        raise VerificationError("Loom launch.args is missing fabric.dli.main")

    classpath_list, removed_outputs = parse_classpath(raw_args[cp_index + 1], "launch classpath")
    classpath = tuple(classpath_list)
    verifier_entries = locate_verifier_entries(classpath)
    effective_flags = normalize_verify_flags(spec.verify_flags, artifact_sha)

    config_text = spec.launch_config.read_text(encoding="utf-8")
    config_text, removed_groups, _ = rewrite_classpath_groups(config_text)
    all_removed = tuple(dict.fromkeys(removed_outputs + removed_groups))
    prepared_config = spec.output_dir / "launch.cfg"
    prepared_config.write_text(config_text, encoding="utf-8")
    verify_class_groups(config_text, verifier_entries)

    metadata_path = spec.launch_args.with_name("launch-metadata.json")
    launch_metadata: Path | None = None
    if metadata_path.is_file():
        launch_metadata = metadata_path.resolve()
        if not is_within(launch_metadata, module_dir):
            raise VerificationError("Loom launch-metadata.json must remain inside the selected module")
        try:
            metadata = json.loads(launch_metadata.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise VerificationError(f"cannot read Loom launch metadata {launch_metadata}: {error}") from error
        if not isinstance(metadata, dict) or type(metadata.get("schemaVersion")) is not int or metadata["schemaVersion"] != 1:
            raise VerificationError("Loom launch-metadata.json must use schemaVersion 1")
        raw_working_directory = metadata.get("workingDirectory")
        if not isinstance(raw_working_directory, str) or not Path(raw_working_directory).is_absolute():
            raise VerificationError("Loom launch metadata workingDirectory must be an absolute path")
        source_run_dir = Path(raw_working_directory).resolve()
        if not is_within(source_run_dir, module_dir) or (source_run_dir.exists() and not source_run_dir.is_dir()):
            raise VerificationError(f"Loom workingDirectory must be a directory inside {module_dir}: {source_run_dir}")
    else:
        source_run_dir = spec.launch_args.parent.parent.resolve()
        if not is_within(source_run_dir, module_dir) or not source_run_dir.is_dir():
            raise VerificationError(f"legacy Loom run directory must be an existing directory inside {module_dir}: {source_run_dir}")

    run_dir_name = source_run_dir.name
    if not run_dir_name or run_dir_name in {".", ".."}:
        raise VerificationError("cannot determine Loom run directory from launch metadata or legacy path")
    output_run_dir = spec.output_dir / run_dir_name
    output_run_dir.mkdir(parents=True, exist_ok=False)
    source_options = source_run_dir / "options.txt"
    options_file = output_run_dir / "options.txt"
    render_distance = parse_options(source_options, options_file)

    class_load_log = spec.output_dir / "class-load.log"
    args = list(raw_args)
    args[cp_index + 1] = os.pathsep.join(str(entry) for entry in classpath)
    rewritten: list[str] = []
    for value in args:
        if value.startswith("-Dfabric.dli.config="):
            rewritten.append(f"-Dfabric.dli.config={prepared_config}")
        elif value.startswith("-Dfabric.addMods="):
            continue
        elif value.startswith("-Xlog:class+load="):
            continue
        elif value.startswith("-Xmx"):
            continue
        elif value.startswith("-XX:ActiveProcessorCount="):
            continue
        elif value.startswith("-D" + VERIFY_PREFIX):
            continue
        else:
            rewritten.append(value)

    final_cp_index = next(index for index, value in enumerate(rewritten) if value in {"-cp", "-classpath"})
    injected = [
        f"-Dfabric.addMods={spec.production_jar}",
        f"-Xlog:class+load=info:file={class_load_log}",
        f"-Xmx{MAX_HEAP}",
        f"-XX:ActiveProcessorCount={ACTIVE_PROCESSORS}",
        *effective_flags,
    ]
    rewritten[final_cp_index:final_cp_index] = injected
    fabric_add_mods = [
        value.partition("=")[2]
        for value in rewritten
        if value.startswith("-Dfabric.addMods=")
    ]
    if len(fabric_add_mods) != 1 or Path(fabric_add_mods[0]).resolve() != spec.production_jar:
        raise VerificationError("launch must inject exactly the supplied production jar through fabric.addMods")
    return LaunchPlan(
        argv=(str(spec.java), *rewritten),
        classpath=classpath,
        removed_production_outputs=all_removed,
        verifier_entries=verifier_entries,
        source_run_dir=source_run_dir,
        launch_metadata=launch_metadata,
        output_run_dir=output_run_dir,
        prepared_config=prepared_config,
        class_load_log=class_load_log,
        effective_flags=effective_flags,
        options_file=options_file,
        render_distance=render_distance,
    )


def file_source_path(source: str) -> Path | None:
    if not source.startswith("file:"):
        return None
    parsed = urlparse(source)
    if parsed.scheme != "file":
        return None
    return Path(unquote(parsed.path)).resolve()


def runtime_remap_provenance(
    artifact: Path,
    isolated_run_dir: Path,
    observed_sources: set[Path],
    loaded_classes: set[str],
) -> tuple[dict[str, Any], list[str]]:
    artifact = artifact.resolve()
    processed_mods = (isolated_run_dir.resolve() / ".fabric" / "processedMods").resolve()
    record: dict[str, Any] = {
        "transformation": "fabric-loader-runtime-remap",
        "inputArtifact": {"path": str(artifact), "sha256": sha256_file(artifact)},
        "cacheDirectory": str(processed_mods),
        "cacheWasFresh": True,
        "observedSourceCount": len(observed_sources),
        "candidateCount": 0,
        "verified": False,
    }
    problems: list[str] = []
    if not is_within(processed_mods, isolated_run_dir.resolve()):
        problems.append("runtime remap cache escaped the isolated run directory")

    try:
        with zipfile.ZipFile(artifact) as input_jar:
            input_manifest = input_jar.read("META-INF/MANIFEST.MF")
            input_mod_json = input_jar.read("fabric.mod.json")
            input_metadata = json.loads(input_mod_json)
            input_dev_entries = {name for name in input_jar.namelist() if name.startswith("dev/lodekeeper/")}
            input_classes = {name for name in input_dev_entries if name.endswith(".class")}
            preserved_input = {
                prefix: {name: input_jar.read(name) for name in input_classes if name.startswith(prefix)}
                for prefix in ("dev/lodekeeper/core/", "dev/lodekeeper/nav/")
            }
    except (OSError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        problems.append(f"cannot inspect production jar for runtime remap provenance: {error}")
        return record, problems

    version = input_metadata.get("version")
    version_text = str(version) if version is not None else ""
    sanitized_version = re.sub(r"[^\w.\-+]+", "_", version_text)
    expected_prefix = f"{input_metadata.get('id', '')}-{sanitized_version}-"
    candidates: list[Path] = []
    if processed_mods.is_dir():
        for path in processed_mods.iterdir():
            if not path.is_file() or path.suffix.lower() != ".jar":
                continue
            try:
                with zipfile.ZipFile(path) as candidate_jar:
                    candidate_metadata = json.loads(candidate_jar.read("fabric.mod.json"))
            except (OSError, KeyError, zipfile.BadZipFile, json.JSONDecodeError):
                if path.name.startswith(expected_prefix):
                    problems.append(f"invalid Fabric remap candidate in isolated cache: {path}")
                continue
            if candidate_metadata.get("id") == MOD_ID:
                candidates.append(path.resolve())
    record["candidateCount"] = len(candidates)
    record["candidatePaths"] = [str(path) for path in candidates]
    if len(candidates) != 1:
        problems.append(f"expected one Lodekeeper runtime remap candidate in {processed_mods}; found {len(candidates)}")
    if len(observed_sources) != 1:
        problems.append(f"expected one processed jar class source; found {len(observed_sources)}")

    runtime_jar = next(iter(observed_sources)) if len(observed_sources) == 1 else None
    if runtime_jar is not None:
        record["runtimeArtifact"] = {"path": str(runtime_jar)}
        if runtime_jar.parent != processed_mods:
            problems.append(f"runtime remap source is not a direct child of the isolated processedMods cache: {runtime_jar}")
        if len(candidates) == 1 and candidates[0] != runtime_jar:
            problems.append("loaded Lodekeeper classes came from a different jar than the sole cache candidate")
        filename_suffix = (
            runtime_jar.name[len(expected_prefix) : -4]
            if runtime_jar.name.startswith(expected_prefix) and runtime_jar.name.endswith(".jar")
            else ""
        )
        filename_matches = bool(filename_suffix and re.fullmatch(r"[0-9a-f]+", filename_suffix))
        record["runtimeArtifact"]["filenameMatchesLoaderPattern"] = filename_matches
        if not filename_matches:
            problems.append(f"runtime remap source filename does not match the Loader mod-version cache pattern: {runtime_jar.name}")
        if runtime_jar.is_file():
            record["runtimeArtifact"]["sha256"] = sha256_file(runtime_jar)
        else:
            problems.append(f"runtime remap source is missing: {runtime_jar}")
    else:
        problems.append("no unique processed jar source was observed for Lodekeeper classes")

    if runtime_jar is not None and runtime_jar.is_file():
        try:
            with zipfile.ZipFile(runtime_jar) as runtime_archive:
                runtime_manifest = runtime_archive.read("META-INF/MANIFEST.MF")
                runtime_mod_json = runtime_archive.read("fabric.mod.json")
                runtime_metadata = json.loads(runtime_mod_json)
                runtime_dev_entries = {
                    name for name in runtime_archive.namelist() if name.startswith("dev/lodekeeper/")
                }
                runtime_classes = {name for name in runtime_dev_entries if name.endswith(".class")}
                preserved_runtime = {
                    prefix: {name: runtime_archive.read(name) for name in runtime_classes if name.startswith(prefix)}
                    for prefix in ("dev/lodekeeper/core/", "dev/lodekeeper/nav/")
                }
        except (OSError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
            problems.append(f"cannot inspect Fabric runtime-remapped jar: {error}")
        else:
            entries_match = input_dev_entries == runtime_dev_entries
            metadata_matches = input_mod_json == runtime_mod_json
            manifest_matches = input_manifest == runtime_manifest
            loaded_entries = {class_name.replace(".", "/") + ".class" for class_name in loaded_classes}
            loaded_classes_match = loaded_entries <= input_classes and loaded_entries <= runtime_classes
            preserved_classes: dict[str, Any] = {}
            for prefix in ("dev/lodekeeper/core/", "dev/lodekeeper/nav/"):
                input_prefix = preserved_input[prefix]
                runtime_prefix = preserved_runtime[prefix]
                names_match = input_prefix.keys() == runtime_prefix.keys()
                bytes_match = names_match and all(input_prefix[name] == runtime_prefix[name] for name in input_prefix)
                preserved_classes[prefix] = {
                    "classCount": len(input_prefix),
                    "namesMatch": names_match,
                    "bytesMatch": bytes_match,
                }
                if not input_prefix:
                    problems.append(f"production jar has no classes under identity-preserved prefix {prefix}")
                elif not bytes_match:
                    problems.append(f"runtime remap changed classes under identity-preserved prefix {prefix}")
            record.update(
                {
                    "devLodekeeperEntryCount": len(input_dev_entries),
                    "devLodekeeperEntryNamesMatch": entries_match,
                    "fabricModJsonMatches": metadata_matches,
                    "manifestMatches": manifest_matches,
                    "identityPreservedClasses": preserved_classes,
                    "loadedClassCount": len(loaded_classes),
                    "loadedClassesPresentInInputAndOutput": loaded_classes_match,
                    "runtimeModId": runtime_metadata.get("id"),
                    "runtimeModVersion": runtime_metadata.get("version"),
                }
            )
            if not entries_match:
                problems.append("runtime remapped jar has a different dev/lodekeeper entry-name set than the supplied artifact")
            if not metadata_matches or runtime_metadata.get("id") != input_metadata.get("id") or runtime_metadata.get("version") != version:
                problems.append("runtime remapped jar fabric.mod.json does not match the supplied artifact")
            if not manifest_matches:
                problems.append("runtime remapped jar manifest does not match the supplied artifact")
            if not loaded_classes_match:
                problems.append("observed runtime-loaded production classes are not all present in both artifacts")

    record["verified"] = not problems
    return record, problems


def classload_provenance(
    log_path: Path,
    artifact: Path,
    verifier_entries: tuple[Path, ...],
    isolated_run_dir: Path,
) -> dict[str, Any]:
    artifact = artifact.resolve()
    processed_mods = (isolated_run_dir.resolve() / ".fabric" / "processedMods").resolve()
    sources: dict[str, int] = {}
    violations: list[str] = []
    production_class_count = 0
    remapped_class_count = 0
    verifier_class_count = 0
    generated_count = 0
    lodekeeper_class_count = 0
    foreign_kernel_class_count = 0
    remap_sources: set[Path] = set()
    remapped_classes: set[str] = set()
    production_classes: set[str] = set()
    if not log_path.is_file():
        return {
            "log": str(log_path),
            "lodekeeperClasses": 0,
            "productionJarClasses": 0,
            "runtimeRemappedClasses": 0,
            "verifierClasses": 0,
            "generatedClasses": 0,
            "sourceCounts": {},
            "runtimeArtifactTransformation": None,
            "violations": ["class-load log was not created"],
        }
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = CLASS_LOAD.match(line)
        if not match:
            continue
        class_name, source = match.groups()
        if class_name.startswith(tuple(prefix.replace("/", ".") for prefix in FOREIGN_KERNEL_PREFIXES)):
            foreign_kernel_class_count += 1
            violations.append(f"external navigation class {class_name} loaded from {source}")
            continue
        if not class_name.startswith("dev.lodekeeper."):
            continue
        lodekeeper_class_count += 1
        source_path = file_source_path(source)
        if source_path == artifact:
            production_class_count += 1
            production_classes.add(class_name)
            sources[str(source_path)] = sources.get(str(source_path), 0) + 1
            continue
        if source_path is not None and any(
            source_path == entry or (entry.is_dir() and is_within(source_path, entry))
            for entry in verifier_entries
        ):
            verifier_class_count += 1
            sources[str(source_path)] = sources.get(str(source_path), 0) + 1
            continue
        if source_path is not None and is_within(source_path, processed_mods):
            remap_sources.add(source_path)
            remapped_classes.add(class_name)
            sources[str(source_path)] = sources.get(str(source_path), 0) + 1
            continue
        if source == "__JVM_LookupDefineClass__" or source.startswith("dev.lodekeeper."):
            generated_count += 1
            continue
        if source == "__dynamic_proxy__" and re.fullmatch(r"dev\.lodekeeper\..*\$Proxy\d+", class_name):
            generated_count += 1
            continue
        sources[source] = sources.get(source, 0) + 1
        violations.append(f"{class_name} loaded from {source}")

    transformation = None
    if remap_sources:
        transformation, remap_problems = runtime_remap_provenance(
            artifact,
            isolated_run_dir,
            remap_sources,
            remapped_classes,
        )
        violations.extend(remap_problems)
        remapped_class_count = sum(sources.get(str(path), 0) for path in remap_sources)
        production_classes.update(remapped_classes)
    if production_class_count == 0 and remapped_class_count == 0:
        violations.append("no dev.lodekeeper class was observed loading from the production artifact or its verified runtime remap")
    if verifier_class_count == 0:
        violations.append("no dev.lodekeeper class was observed loading from separate verifier output")

    try:
        with zipfile.ZipFile(artifact) as production_jar:
            production_entries = {
                name for name in production_jar.namelist()
                if name.startswith("dev/lodekeeper/") and name.endswith(".class")
            }
    except (OSError, zipfile.BadZipFile) as error:
        production_entries = set()
        violations.append(f"cannot inspect production classes for class-load membership: {error}")
    loaded_entries = {class_name.replace(".", "/") + ".class" for class_name in production_classes}
    if not loaded_entries <= production_entries:
        violations.append("observed production classes are not all present in the supplied production jar")

    return {
        "log": str(log_path),
        "lodekeeperClasses": lodekeeper_class_count,
        "productionJarClasses": production_class_count,
        "runtimeRemappedClasses": remapped_class_count,
        "productionArtifactClasses": len(production_classes),
        "verifierClasses": verifier_class_count,
        "generatedClasses": generated_count,
        "foreignKernelClasses": foreign_kernel_class_count,
        "sourceCounts": sources,
        "runtimeArtifactTransformation": transformation,
        "violations": violations,
    }


def evidence_files(run_dir: Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, list[dict[str, str]]]]:
    native_receipts: list[dict[str, Any]] = []
    screenshots: list[dict[str, Any]] = []
    station_logs: dict[str, list[dict[str, str]]] = {"left": [], "recovered": []}
    evidence_root = run_dir / "verification"
    if evidence_root.is_dir():
        evidence_json = sorted(
            path for path in evidence_root.rglob("*.json")
            if "evidence" in path.relative_to(evidence_root).parts
        )
        for path in evidence_json:
            try:
                contents = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as error:
                contents = {"parseError": str(error)}
            native_receipts.append(
                {
                    "path": str(path.relative_to(run_dir)),
                    "sha256": sha256_file(path),
                    "status": contents.get("status") if isinstance(contents, dict) else None,
                    "verificationMode": contents.get("verificationMode") if isinstance(contents, dict) else None,
                    "failure": contents.get("failure") if isinstance(contents, dict) else None,
                }
            )
        evidence_screenshots = sorted(
            path for path in evidence_root.rglob("*.png")
            if "evidence" in path.relative_to(evidence_root).parts
        )
        for path in evidence_screenshots:
            screenshots.append({
                "path": str(path.relative_to(run_dir)),
                "sha256": sha256_file(path),
            })

    for log_path in sorted(run_dir.rglob("logs/*.log")):
        label = str(log_path.relative_to(run_dir))
        for line_number, line in enumerate(log_path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
            if STATION_LEFT.search(line):
                station_logs["left"].append({"log": label, "line": str(line_number), "text": line.strip()})
            if STATION_RECOVERED.search(line):
                station_logs["recovered"].append({"log": label, "line": str(line_number), "text": line.strip()})
    return native_receipts, screenshots, station_logs


def native_receipt_gate(receipts: list[dict[str, Any]]) -> tuple[bool, list[str]]:
    problems: list[str] = []
    run_receipts = [receipt for receipt in receipts if receipt.get("status") is not None]
    if not run_receipts:
        problems.append("no native run receipt with a status field was produced")
    for receipt in run_receipts:
        if str(receipt.get("status")).lower() not in {"passed", "pass", "success", "succeeded"}:
            problems.append(f"native run receipt {receipt['path']} status is {receipt.get('status')!r}")
        if receipt.get("failure") not in (None, "", False):
            problems.append(f"native run receipt {receipt['path']} reports failure {receipt.get('failure')!r}")
    return not problems, problems


def station_cleanup_gate(station_logs: dict[str, list[dict[str, str]]]) -> tuple[bool, list[str]]:
    problems: list[str] = []
    if station_logs["left"]:
        problems.append(f"{len(station_logs['left'])} OWNED_STATION_LEFT log line(s) were recorded")
    if not station_logs["recovered"]:
        problems.append("no OWNED_STATION_RECOVERED log line was recorded")
    return not problems, problems


def write_json(path: Path, content: dict[str, Any]) -> None:
    path.write_text(json.dumps(content, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def stop_owned_process(process: subprocess.Popen[bytes]) -> int:
    if process.poll() is None:
        try:
            process.terminate()
        except OSError:
            pass
        try:
            return process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            try:
                process.kill()
            except OSError:
                pass
    return process.wait()


def run_client(spec: RunSpec, plan: LaunchPlan, receipt: dict[str, Any]) -> tuple[int | None, bool]:
    process_log = spec.output_dir / "client-process.log"
    timed_out = False
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    receipt["client"] = {
        "pid": None,
        "startedAtUtc": started,
        "timeoutSeconds": spec.timeout_seconds,
        "processLog": str(process_log),
        "exitCode": None,
        "timedOut": False,
    }
    with process_log.open("wb") as output:
        process = subprocess.Popen(
            plan.argv,
            cwd=plan.output_run_dir,
            stdout=output,
            stderr=subprocess.STDOUT,
            close_fds=True,
        )
        receipt["client"]["pid"] = process.pid
        write_json(spec.output_dir / "runner-receipt.json", receipt)
        try:
            exit_code = process.wait(timeout=spec.timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            exit_code = stop_owned_process(process)
        except BaseException:
            stop_owned_process(process)
            raise
    receipt["client"].update(
        {
            "finishedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "exitCode": exit_code,
            "timedOut": timed_out,
        }
    )
    return exit_code, timed_out


def prepare(spec: RunSpec) -> tuple[LaunchPlan, dict[str, Any]]:
    module_dir = (REPO_ROOT / spec.module).resolve()
    if not is_within(module_dir, REPO_ROOT.resolve()) or not module_dir.is_dir():
        raise VerificationError(f"module must be an existing repository directory: {module_dir}")
    require_file(spec.java, "Java executable", executable=True)
    require_file(spec.launch_args, "Loom launch.args")
    require_file(spec.launch_config, "Loom launch.cfg")
    if not is_within(spec.launch_args, module_dir) or not is_within(spec.launch_config, module_dir):
        raise VerificationError("Loom launch.args and launch.cfg must come from the selected module in this checkout")
    require_file(spec.production_jar, "production jar")
    artifact = inspect_production_jar(spec.production_jar, spec.minecraft_version)
    artifact_sha = sha256_file(spec.production_jar)
    plan = build_launch_plan(spec, artifact_sha)
    metadata = {
        "schemaVersion": 1,
        "startedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "repository": str(REPO_ROOT.resolve()),
        "source": git_state(),
        "module": spec.module,
        "minecraftVersion": spec.minecraft_version,
        "profile": spec.profile,
        "java": str(spec.java),
        "productionJar": {
            "path": str(spec.production_jar),
            "sha256": artifact_sha,
            **artifact,
        },
        "loom": {
            "launchArgs": str(spec.launch_args),
            "launchConfig": str(spec.launch_config),
            "launchMetadata": str(plan.launch_metadata) if plan.launch_metadata else None,
            "sourceRunDirectory": str(plan.source_run_dir),
            "preparedConfig": str(plan.prepared_config),
        },
        "verificationFlags": list(plan.effective_flags),
        "launch": {
            "argv": list(plan.argv),
            "workingDirectory": str(plan.output_run_dir),
            "outputIsolated": True,
            "workingDirectoryCreatedFresh": True,
            "optionsFile": str(plan.options_file),
            "renderDistance": plan.render_distance,
            "productionModInput": str(spec.production_jar),
            "productionModArgumentCount": sum(value.startswith("-Dfabric.addMods=") for value in plan.argv),
            "maxHeap": MAX_HEAP,
            "activeProcessors": int(ACTIVE_PROCESSORS),
            "classpath": [str(path) for path in plan.classpath],
            "classpathCount": len(plan.classpath),
            "removedProductionDevelopmentOutputs": list(plan.removed_production_outputs),
            "productionDevelopmentOutputsRemoved": True,
            "verificationClassesRetainedSeparately": [str(path) for path in plan.verifier_entries],
            "classLoadLog": str(plan.class_load_log),
            "separateBaritoneMod": False,
        },
        "requireStationCleanup": spec.require_station_cleanup,
    }
    return plan, metadata


def main(argv: list[str] | None = None) -> int:
    try:
        spec = parse_args(sys.argv[1:] if argv is None else argv)
        spec = dataclasses.replace(spec, output_dir=validate_output_dir(spec.output_dir))
        plan, receipt = prepare(spec)
        if spec.plan_only:
            receipt["planOnly"] = True
            receipt["client"] = {"started": False, "reason": "--plan-only"}
            write_json(spec.output_dir / "runner-receipt.json", receipt)
            print(json.dumps({"planOnly": True, "receipt": str(spec.output_dir / "runner-receipt.json")}, indent=2))
            return 0

        exit_code, timed_out = run_client(spec, plan, receipt)
        native_receipts, screenshots, station_logs = evidence_files(plan.output_run_dir)
        provenance = classload_provenance(
            plan.class_load_log,
            spec.production_jar,
            plan.verifier_entries,
            plan.output_run_dir,
        )
        native_ok, native_problems = native_receipt_gate(native_receipts)
        station_ok, station_problems = station_cleanup_gate(station_logs)
        jar_unchanged = sha256_file(spec.production_jar) == receipt["productionJar"]["sha256"]
        failures: list[str] = []
        if timed_out:
            failures.append("owned client process exceeded its timeout")
        if exit_code != 0:
            failures.append(f"owned client process exited with code {exit_code}")
        if not jar_unchanged:
            failures.append("production jar changed during the run")
        if provenance["violations"]:
            failures.extend(provenance["violations"])
        if not native_ok:
            failures.extend(native_problems)
        if spec.require_station_cleanup and not station_ok:
            failures.extend(station_problems)

        receipt.update(
            {
                "finishedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
                "artifactUnchangedDuringRun": jar_unchanged,
                "nativeReceipts": native_receipts,
                "screenshots": screenshots,
                "stationDiagnostics": station_logs,
                "stationCleanupGate": {
                    "required": spec.require_station_cleanup,
                    "passed": station_ok if spec.require_station_cleanup else None,
                    "problems": station_problems if spec.require_station_cleanup else [],
                },
                "classLoadProvenance": provenance,
                "nativeReceiptGate": {"passed": native_ok, "problems": native_problems},
                "status": "passed" if not failures else "failed",
                "failures": failures,
            }
        )
        write_json(spec.output_dir / "runner-receipt.json", receipt)
        print(json.dumps({
            "status": receipt["status"],
            "receipt": str(spec.output_dir / "runner-receipt.json"),
            "clientExitCode": exit_code,
            "nativeReceipts": native_receipts,
            "screenshots": screenshots,
            "stationLeft": station_logs["left"],
            "stationRecovered": station_logs["recovered"],
            "classLoadViolations": provenance["violations"],
            "failures": failures,
        }, indent=2))
        return 0 if not failures else 1
    except VerificationError as error:
        print(f"verification runner: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
