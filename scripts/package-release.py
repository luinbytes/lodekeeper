#!/usr/bin/env python3
"""Inspect exact CI artifacts and stage a release package."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_NAME = "Build and regression checks"
REPOSITORY = "luinbytes/lodekeeper"
PROFILE_MODULES = {"fabric", "fabric-1202", "fabric-1211", "fabric-1212", "fabric-modern"}
SOURCE_FILES = (
    "third-party/baritone/dependencies.json",
    "third-party/baritone/mining-bridge.json",
    "third-party/baritone/NOTICE.md",
    "third-party/baritone/licenses/COPYING",
    "third-party/baritone/licenses/COPYING.LESSER",
    "gradle/baritone.gradle",
    "scripts/fetch-baritone.py",
    "scripts/inspect-baritone-mining.py",
)
EMBEDDED_FILES = {
    "third-party/baritone/dependencies.json": "META-INF/licenses/baritone/dependencies.json",
    "third-party/baritone/mining-bridge.json": "META-INF/lodekeeper/baritone/mining-bridge.json",
    "third-party/baritone/NOTICE.md": "META-INF/licenses/baritone/NOTICE.md",
    "third-party/baritone/licenses/COPYING": "META-INF/licenses/baritone/COPYING",
    "third-party/baritone/licenses/COPYING.LESSER": "META-INF/licenses/baritone/COPYING.LESSER",
    "scripts/inspect-baritone-mining.py": "META-INF/lodekeeper/baritone/inspect-baritone-mining.py",
}
PROFILE_PATTERN = re.compile(
    r"(?m)^\s*- minecraft: '([^']+)'\s*\n"
    r"\s+java: '([0-9]+)'\s*\n"
    r"\s+module: ([A-Za-z0-9_-]+)\s*$"
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CI_ARTIFACT_DIGEST_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
SOURCE_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
JAVA_CLASS_MAJOR = {17: 61, 21: 65, 25: 69}


class PackageError(Exception):
    """A release input failed an explicit packaging check."""


@dataclass(frozen=True)
class Profile:
    minecraft: str
    java: int
    module: str


@dataclass(frozen=True)
class SourceSnapshot:
    commit: str
    mod_version: str
    profiles: tuple[Profile, ...]
    source_files: dict[str, bytes]
    module_manifests: dict[str, dict[str, Any]]
    dependencies: dict[str, Any]
    bridge: dict[str, Any]
    gradle_sha256: str
    fetch_script_sha256: str


@dataclass(frozen=True)
class ArtifactInspection:
    profile: Profile
    source_path: Path
    jar_name: str
    sha256: str
    archive_sha: str
    digest_matches_ci_receipt: bool
    adapter_class_major: int
    core_class_major: int
    navigation_class_major: int
    refmaps: tuple[str, ...]
    baritone_version: str
    baritone_sha256: str
    baritone_class_majors: tuple[int, ...]
    baritone_nested_libraries: tuple[str, ...]
    bridge_sha256: str


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PackageError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_bytes(*args: str) -> bytes:
    try:
        result = subprocess.run(
            ["git", *args], cwd=ROOT, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
    except (OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, "stderr", b"")
        if isinstance(detail, bytes):
            detail = detail.decode("utf-8", errors="replace").strip()
        raise PackageError(f"Cannot read source commit with git {args[0]}: {detail or error}") from error
    return result.stdout


def source_file(commit: str, path: str) -> bytes:
    try:
        return git_bytes("show", f"{commit}:{path}")
    except PackageError as error:
        raise PackageError(f"Source commit {commit} does not contain {path}: {error}") from error


def parse_json(data: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PackageError(f"{label} is not valid UTF-8 JSON: {error}") from error
    require(isinstance(value, dict), f"{label} must contain a JSON object")
    return value


def parse_profiles(workflow: bytes) -> tuple[Profile, ...]:
    try:
        text = workflow.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PackageError(f"Build workflow is not UTF-8: {error}") from error
    profiles = tuple(Profile(mc, int(java), module) for mc, java, module in PROFILE_PATTERN.findall(text))
    require(len(profiles) == 24, f"Source workflow defines {len(profiles)} adapter profiles, expected 24")
    require(len({profile.minecraft for profile in profiles}) == 24, "Source workflow has duplicate Minecraft profiles")
    unknown = sorted({profile.module for profile in profiles} - PROFILE_MODULES)
    require(not unknown, f"Source workflow names unexpected Fabric modules: {unknown}")
    require({profile.module for profile in profiles} == PROFILE_MODULES, "Source workflow does not cover all five production modules")
    for profile in profiles:
        require(profile.java in JAVA_CLASS_MAJOR, f"Unsupported Java {profile.java} in profile {profile.minecraft}")
    return profiles


def require_source_packaging_policy(source_files: dict[str, bytes]) -> None:
    gradle = source_files["gradle/baritone.gradle"].decode("utf-8")
    gradle_requirements = (
        "rootProject.file('third-party/baritone/dependencies.json')",
        "dependencies.add('include', baritoneCoordinate)",
        "matches = metadata.supported.findAll { it.jarSha256 == jarSha }",
        "rootProject.file('third-party/baritone/NOTICE.md')",
        "rootProject.file('third-party/baritone/licenses')",
        "from(baritoneMetadataFile)",
        "from(bridgeMetadataFile)",
        "rename { 'mining-bridge.json' }",
    )
    for fragment in gradle_requirements:
        require(fragment in gradle, f"Source Gradle packaging policy is missing {fragment!r}")

    fetcher = source_files["scripts/fetch-baritone.py"].decode("utf-8")
    for fragment in ('"sha256"', 'actual_digest != release["sha256"]', 'metadata.get("minecraft_profiles")'):
        require(fragment in fetcher, f"Source Baritone fetcher is missing pin check {fragment!r}")


def load_source_snapshot(commit: str, requested_version: str) -> SourceSnapshot:
    require(SOURCE_COMMIT_PATTERN.fullmatch(commit) is not None, "source40 must be a full lowercase 40-character commit SHA")
    git_bytes("cat-file", "-e", f"{commit}^{{commit}}")
    workflow = source_file(commit, ".github/workflows/build.yml")
    profiles = parse_profiles(workflow)

    source_files = {path: source_file(commit, path) for path in SOURCE_FILES}
    require_source_packaging_policy(source_files)
    dependencies = parse_json(source_files["third-party/baritone/dependencies.json"], "Baritone dependency ledger")
    bridge = parse_json(source_files["third-party/baritone/mining-bridge.json"], "Baritone bridge metadata")
    require(dependencies.get("schema_version") == 1, "Unsupported Baritone dependency ledger schema")
    require(bridge.get("schema_version") == 1, "Unsupported Baritone bridge metadata schema")
    releases = dependencies.get("releases")
    require(isinstance(releases, list) and all(isinstance(item, dict) for item in releases),
            "Baritone dependency ledger releases must be a list of objects")

    properties = source_file(commit, "gradle.properties").decode("utf-8")
    version_matches = re.findall(r"(?m)^mod_version=([^\s#]+)\s*$", properties)
    require(len(version_matches) == 1, "Source gradle.properties must define one mod_version")
    require(version_matches[0] == requested_version, f"Requested mod version {requested_version} differs from source {version_matches[0]}")

    module_manifests = {}
    for module in sorted(PROFILE_MODULES):
        path = f"{module}/src/main/resources/fabric.mod.json"
        module_manifests[module] = parse_json(source_file(commit, path), path)
    descriptions = [manifest.get("description") for manifest in module_manifests.values()]
    require(all(isinstance(value, str) and value.strip() for value in descriptions)
            and len(set(descriptions)) == 1,
            "Production Fabric descriptors must share one nonempty description")

    profile_pins = dependencies.get("minecraft_profiles")
    require(isinstance(profile_pins, dict), "Baritone dependency ledger has no Minecraft profile map")
    for profile in profiles:
        require(profile.minecraft in profile_pins, f"No pinned Baritone release for Minecraft {profile.minecraft}")
        release_version = profile_pins[profile.minecraft]
        releases = [item for item in dependencies.get("releases", []) if item.get("version") == release_version]
        require(len(releases) == 1, f"Expected one Baritone ledger entry for {release_version}")
        release = releases[0]
        require(profile.minecraft in release.get("minecraft_versions", []),
                f"Baritone {release_version} does not list Minecraft {profile.minecraft}")
        require(SHA256_PATTERN.fullmatch(str(release.get("sha256", ""))) is not None,
                f"Invalid pinned Baritone SHA-256 for Minecraft {profile.minecraft}")
        expected_major = JAVA_CLASS_MAJOR.get(release.get("java_version"))
        require(expected_major == release.get("class_major"), f"Invalid class-major pin for Baritone {release_version}")

    return SourceSnapshot(
        commit=commit,
        mod_version=requested_version,
        profiles=profiles,
        source_files=source_files,
        module_manifests=module_manifests,
        dependencies=dependencies,
        bridge=bridge,
        gradle_sha256=sha256(source_files["gradle/baritone.gradle"]),
        fetch_script_sha256=sha256(source_files["scripts/fetch-baritone.py"]),
    )


def check_receipt(receipt_path: Path, commit: str, ci_run: str,
                  profiles: tuple[Profile, ...]) -> tuple[dict[str, Any], dict[str, Any], dict[str, dict[str, Any]]]:
    try:
        receipt = parse_json(receipt_path.read_bytes(), f"CI receipt {receipt_path}")
    except OSError as error:
        raise PackageError(f"Cannot read CI receipt {receipt_path}: {error}") from error
    require(ci_run.isdigit(), "ci-run must be a numeric GitHub Actions run ID")
    require(str(receipt.get("databaseId")) == ci_run, "CI receipt run ID does not match ci-run")
    require(receipt.get("url") == f"https://github.com/{REPOSITORY}/actions/runs/{ci_run}", "CI receipt URL does not match this repository and run")
    require(receipt.get("workflowName") == WORKFLOW_NAME, f"CI receipt is not for {WORKFLOW_NAME!r}")
    require(receipt.get("headSha") == commit, "CI receipt head SHA does not match source40")
    require(receipt.get("status") == "completed" and receipt.get("conclusion") == "success", "CI run has not completed successfully")

    expected_job_names = {f"adapters ({p.minecraft}, {p.java}, {p.module})" for p in profiles}
    jobs = receipt.get("jobs")
    require(isinstance(jobs, list) and len(jobs) == 24, "CI receipt must contain exactly 24 adapter jobs")
    actual_job_names = {job.get("name") for job in jobs if isinstance(job, dict)}
    require(actual_job_names == expected_job_names, "CI receipt job names do not match the exact source matrix")
    for job in jobs:
        require(isinstance(job, dict) and job.get("status") == "completed" and job.get("conclusion") == "success",
                f"CI job did not pass: {job.get('name') if isinstance(job, dict) else job}")

    expected_artifacts = {f"lodekeeper-{p.minecraft}-development" for p in profiles}
    artifacts = receipt.get("artifacts")
    require(isinstance(artifacts, list), "CI receipt has no artifact list")
    artifact_names = {artifact.get("name") for artifact in artifacts if isinstance(artifact, dict)}
    require(artifact_names == expected_artifacts and len(artifacts) == 24, "CI receipt artifacts do not match the exact source matrix")
    artifact_ids = set()
    for artifact in artifacts:
        require(isinstance(artifact, dict) and artifact.get("expired") is False,
                f"CI artifact is expired or malformed: {artifact.get('name') if isinstance(artifact, dict) else artifact}")
        workflow_run = artifact.get("workflow_run")
        require(isinstance(workflow_run, dict)
                and str(workflow_run.get("id")) == ci_run
                and workflow_run.get("head_sha") == commit,
                f"CI artifact {artifact['name']} is not bound to run {ci_run} at source40")
        require(CI_ARTIFACT_DIGEST_PATTERN.fullmatch(str(artifact.get("digest", ""))) is not None,
                f"CI artifact {artifact['name']} has no SHA-256 archive digest")
        require(artifact.get("id") is not None and str(artifact["id"]) not in artifact_ids,
                f"CI artifact {artifact['name']} has a missing or duplicate artifact ID")
        artifact_ids.add(str(artifact["id"]))
    return receipt, {job["name"]: job for job in jobs}, {artifact["name"]: artifact for artifact in artifacts}


def release_for(profile: Profile, snapshot: SourceSnapshot) -> dict[str, Any]:
    version = snapshot.dependencies["minecraft_profiles"][profile.minecraft]
    return next(item for item in snapshot.dependencies["releases"] if item["version"] == version)


def bridge_mapping_for(release: dict[str, Any], bridge: dict[str, Any]) -> dict[str, Any]:
    supported = bridge.get("supported")
    require(isinstance(supported, list) and all(isinstance(entry, dict) for entry in supported),
            "Baritone bridge metadata has no valid supported mapping list")
    matches = [entry for entry in supported if entry.get("jarSha256") == release["sha256"]]
    require(len(matches) == 1, f"Bridge metadata must map pinned Baritone SHA-256 {release['sha256']} exactly once")
    mapping = matches[0]
    identifier = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")
    class_name = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
    require(mapping.get("version") == release["version"], "Bridge mapping version does not match the pinned Baritone release")
    require(mapping.get("class_major") == release["class_major"], "Bridge mapping class major does not match the pinned Baritone release")
    require(isinstance(mapping.get("targetClass"), str) and class_name.fullmatch(mapping["targetClass"]), "Invalid bridge target class")
    require(isinstance(mapping.get("knownField"), str) and identifier.fullmatch(mapping["knownField"]), "Invalid bridge known field")
    require(isinstance(mapping.get("blacklistField"), str) and identifier.fullmatch(mapping["blacklistField"]), "Invalid bridge blacklist field")
    require(mapping["knownField"] != mapping["blacklistField"], "Bridge fields must be distinct")
    require(mapping.get("field_descriptor") == "Ljava/util/List;", "Bridge field descriptor is not a List")
    return mapping


def read_class_major(archive: zipfile.ZipFile, names: list[str], suffix: str, label: str) -> int:
    matches = [name for name in names if name.endswith(suffix)]
    require(len(matches) == 1, f"{label} class {suffix} must occur once, found {matches}")
    data = archive.read(matches[0])
    require(len(data) >= 8 and data[:4] == b"\xca\xfe\xba\xbe", f"Invalid Java class header in {matches[0]}")
    return int.from_bytes(data[6:8], "big")


def inspect_nested_baritone(data: bytes, release: dict[str, Any], profile: Profile) -> tuple[tuple[int, ...], tuple[str, ...]]:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "Nested Baritone jar has duplicate ZIP entries")
            manifest = parse_json(archive.read("fabric.mod.json"), "Nested Baritone fabric.mod.json")
            require(manifest.get("id") == "baritone", "Nested dependency is not the Baritone Fabric mod")
            require(manifest.get("version") == release["version"], "Nested Baritone manifest version differs from the exact source pin")
            require(profile.minecraft in release["minecraft_versions"], "Pinned Baritone release does not support this Minecraft profile")

            class_majors = set()
            class_count = 0
            for name in names:
                if name.endswith(".class"):
                    header = archive.read(name)[:8]
                    require(len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe", f"Invalid class header in nested Baritone entry {name}")
                    class_count += 1
                    class_majors.add(int.from_bytes(header[6:8], "big"))
            expected_major = release["class_major"]
            require(class_count > 0 and expected_major in class_majors and max(class_majors) <= expected_major,
                    f"Nested Baritone class majors {sorted(class_majors)} do not match pinned major {expected_major}")

            jars = manifest.get("jars", [])
            require(isinstance(jars, list), "Nested Baritone manifest jars field must be a list")
            nested_paths = []
            for nested in jars:
                nested_path = nested.get("file") if isinstance(nested, dict) else None
                require(isinstance(nested_path, str) and nested_path in names,
                        f"Nested Baritone manifest refers to missing jar {nested_path!r}")
                nested_data = archive.read(nested_path)
                with zipfile.ZipFile(io.BytesIO(nested_data)):
                    pass
                nested_paths.append(nested_path)
            expected_nested = set(release.get("nested_libraries", []))
            actual_nested = {PurePosixPath(path).name.removesuffix(".jar") for path in nested_paths}
            require(actual_nested == expected_nested,
                    f"Nested Baritone libraries are {sorted(actual_nested)}, expected {sorted(expected_nested)}")
            return tuple(sorted(class_majors)), tuple(sorted(actual_nested))
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise PackageError(f"Cannot inspect pinned nested Baritone jar: {error}") from error


def is_verification_content(name: str) -> bool:
    return "verification" in name.lower()


def inspect_jar(jar: Path, profile: Profile, snapshot: SourceSnapshot,
                archive_sha: str) -> ArtifactInspection:
    expected_name = f"lodekeeper-{profile.minecraft}-{snapshot.mod_version}.jar"
    require(jar.name == expected_name, f"Artifact filename is {jar.name}, expected {expected_name}")
    try:
        data = jar.read_bytes()
    except OSError as error:
        raise PackageError(f"Cannot read artifact {jar}: {error}") from error
    digest = sha256(data)
    release = release_for(profile, snapshot)
    mapping = bridge_mapping_for(release, snapshot.bridge)
    embedded_baritone_path = f"META-INF/jars/baritone-api-fabric-{release['version']}.jar"
    module_source = snapshot.module_manifests[profile.module]

    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), f"{jar.name} has duplicate ZIP entries")
            require("fabric.mod.json" in names, f"{jar.name} has no root fabric.mod.json")
            manifest = parse_json(archive.read("fabric.mod.json"), f"{jar.name} fabric.mod.json")
            require(manifest.get("id") == "lodekeeper", f"{jar.name} has mod id {manifest.get('id')!r}")
            require(manifest.get("version") == snapshot.mod_version, f"{jar.name} has the wrong mod version")
            require(manifest.get("description") == module_source.get("description"),
                    f"{jar.name} description differs from the exact source descriptor")
            depends = manifest.get("depends")
            require(isinstance(depends, dict), f"{jar.name} has no Fabric dependency map")
            require(depends.get("minecraft") == profile.minecraft, f"{jar.name} does not declare exact Minecraft {profile.minecraft}")
            require(depends.get("java") == f">={profile.java}", f"{jar.name} does not declare Java >= {profile.java}")

            verification_entries = [name for name in names if is_verification_content(name)]
            require(not verification_entries, f"{jar.name} contains verification content: {verification_entries[:5]}")

            adapter_suffix = "/modern/AutomationEngine.class" if profile.module == "fabric-modern" else "/AutomationEngine.class"
            adapter_major = read_class_major(archive, names, adapter_suffix, "Adapter")
            if profile.module != "fabric-modern":
                adapter_matches = [name for name in names if name.endswith("/AutomationEngine.class")]
                require(len(adapter_matches) == 1, f"{jar.name} must have one adapter AutomationEngine class")
            core_major = read_class_major(archive, names, "/AcquisitionPlanner.class", "Core")
            navigation_major = read_class_major(archive, names, "/Goal.class", "Navigation")
            require(adapter_major == profile.java + 44,
                    f"{jar.name} adapter class major {adapter_major} does not match Java {profile.java}")
            require(core_major == 61 and navigation_major == 61,
                    f"{jar.name} core/navigation class majors are {core_major}/{navigation_major}, expected Java 17 major 61")

            refmaps = tuple(sorted(name for name in names if name.endswith("refmap.json")))
            if profile.module == "fabric-modern":
                require(not refmaps, f"{jar.name} unexpectedly contains Yarn refmaps: {refmaps}")
            else:
                require(bool(refmaps), f"{jar.name} has no Yarn refmap")
                for refmap in refmaps:
                    content = parse_json(archive.read(refmap), f"{jar.name} {refmap}")
                    require(isinstance(content.get("mappings"), dict) and bool(content["mappings"]),
                            f"{jar.name} has an empty Yarn refmap {refmap}")

            nested_paths = [name for name in names if name.startswith("META-INF/jars/") and name.endswith(".jar")]
            require(nested_paths == [embedded_baritone_path],
                    f"{jar.name} must contain exactly the pinned Baritone jar {embedded_baritone_path}, found {nested_paths}")
            baritone_bytes = archive.read(embedded_baritone_path)
            baritone_digest = sha256(baritone_bytes)
            require(baritone_digest == release["sha256"],
                    f"{jar.name} nested Baritone SHA-256 {baritone_digest} differs from source pin {release['sha256']}")
            baritone_class_majors, baritone_nested = inspect_nested_baritone(baritone_bytes, release, profile)

            for source_path, embedded_path in EMBEDDED_FILES.items():
                require(embedded_path in names, f"{jar.name} is missing {embedded_path}")
                require(archive.read(embedded_path) == snapshot.source_files[source_path],
                        f"{jar.name} {embedded_path} differs from exact source {source_path}")

            bridge_digest = sha256(snapshot.source_files["third-party/baritone/mining-bridge.json"])
            bridge_in_jar = parse_json(archive.read(EMBEDDED_FILES["third-party/baritone/mining-bridge.json"]),
                                      f"{jar.name} embedded Baritone bridge metadata")
            embedded_mapping = bridge_mapping_for(release, bridge_in_jar)
            require(embedded_mapping == mapping, f"{jar.name} bridge mapping differs from the exact source pin")
            mixins = manifest.get("mixins", [])
            require(isinstance(mixins, list), f"{jar.name} has an invalid mixins list")
            require(any(
                entry == "lodekeeper-baritone.mixins.json"
                or (isinstance(entry, dict) and entry.get("config") == "lodekeeper-baritone.mixins.json")
                for entry in mixins
            ), f"{jar.name} does not load the pinned Baritone accessor mixin")
            bridge_config = parse_json(archive.read("lodekeeper-baritone.mixins.json"),
                                       f"{jar.name} Baritone mixin configuration")
            client_mixins = bridge_config.get("client")
            require(bridge_config.get("required") is True and isinstance(client_mixins, list)
                    and "BaritoneMiningAccessor" in client_mixins,
                    f"{jar.name} Baritone accessor mixin is not required")
            accessors = [name for name in names if name.endswith("/mixin/BaritoneMiningAccessor.class")]
            require(len(accessors) == 1, f"{jar.name} must contain one generated Baritone mining accessor")
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise PackageError(f"Cannot inspect {jar}: {error}") from error

    return ArtifactInspection(
        profile=profile,
        source_path=jar,
        jar_name=jar.name,
        sha256=digest,
        archive_sha=archive_sha,
        digest_matches_ci_receipt=True,
        adapter_class_major=adapter_major,
        core_class_major=core_major,
        navigation_class_major=navigation_major,
        refmaps=refmaps,
        baritone_version=release["version"],
        baritone_sha256=baritone_digest,
        baritone_class_majors=baritone_class_majors,
        baritone_nested_libraries=baritone_nested,
        bridge_sha256=bridge_digest,
    )


def check_checksum_file(artifact_dir: Path, jar: Path) -> None:
    checksum_path = artifact_dir / "SHA256SUMS"
    try:
        lines = [line.strip() for line in checksum_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    except OSError as error:
        raise PackageError(f"Cannot read {checksum_path}: {error}") from error
    require(len(lines) == 1, f"{checksum_path} must contain one checksum line")
    fields = lines[0].split(maxsplit=1)
    require(len(fields) == 2 and SHA256_PATTERN.fullmatch(fields[0]) is not None,
            f"Malformed checksum line in {checksum_path}")
    require(PurePosixPath(fields[1].lstrip("* ")).name == jar.name, f"{checksum_path} names a different artifact")
    try:
        actual = sha256(jar.read_bytes())
    except OSError as error:
        raise PackageError(f"Cannot read artifact {jar}: {error}") from error
    require(fields[0] == actual, f"Checksum mismatch for {jar}")


def verify_ci_artifact_zip(archive_path: Path, artifact: dict[str, Any], artifact_name: str,
                           artifact_dir: Path, jar_name: str) -> str:
    try:
        archive_bytes = archive_path.read_bytes()
    except OSError as error:
        raise PackageError(f"Cannot read original CI artifact archive {archive_path}: {error}") from error
    archive_sha = f"sha256:{sha256(archive_bytes)}"
    require(archive_sha == artifact["digest"],
            f"Original CI artifact archive digest differs from receipt for {artifact_name}")

    try:
        with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            require(len(names) == len(set(names)), f"Original CI archive has duplicate entries: {artifact_name}")
            for name in names:
                path = PurePosixPath(name)
                require(name == path.name and not path.is_absolute() and "\\" not in name
                        and all(part not in {".", ".."} for part in path.parts),
                        f"Original CI archive has an unsafe or nested path: {name!r}")
            require(all(not entry.is_dir() for entry in entries),
                    f"Original CI archive contains unexpected directories: {artifact_name}")
            require(len(entries) == 2 and set(names) == {jar_name, "SHA256SUMS"},
                    f"Original CI archive must contain only {jar_name} and SHA256SUMS")
            contents = {name: archive.read(name) for name in names}
    except PackageError:
        raise
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise PackageError(f"Cannot inspect original CI artifact archive {archive_path}: {error}") from error

    extracted_entries = list(artifact_dir.iterdir())
    require({path.name for path in extracted_entries} == {jar_name, "SHA256SUMS"}
            and len(extracted_entries) == 2,
            f"Extracted artifact directory must contain only {jar_name} and SHA256SUMS")
    for name, archived_content in contents.items():
        extracted_path = artifact_dir / name
        require(not extracted_path.is_symlink() and extracted_path.is_file(),
                f"Extracted artifact entry is not a regular file: {extracted_path}")
        try:
            extracted_content = extracted_path.read_bytes()
        except OSError as error:
            raise PackageError(f"Cannot read extracted artifact entry {extracted_path}: {error}") from error
        require(archived_content == extracted_content,
                f"Extracted artifact entry differs from receipt-bound archive: {extracted_path}")
    return archive_sha


def inspect_artifacts(artifact_root: Path, snapshot: SourceSnapshot,
                      receipt_artifacts: dict[str, dict[str, Any]]) -> list[ArtifactInspection]:
    require(artifact_root.is_dir(), f"Artifact root does not exist: {artifact_root}")
    inspections = []
    for profile in snapshot.profiles:
        artifact_name = f"lodekeeper-{profile.minecraft}-development"
        artifact_dir = artifact_root / artifact_name
        require(artifact_dir.is_dir(), f"Missing CI artifact directory {artifact_dir}")
        jars = sorted(artifact_dir.glob("*.jar"))
        require(len(jars) == 1, f"{artifact_name} must contain exactly one jar, found {[jar.name for jar in jars]}")
        archive_path = artifact_root / f"{artifact_name}.zip"
        archive_sha = verify_ci_artifact_zip(archive_path, receipt_artifacts[artifact_name],
                                             artifact_name, artifact_dir, jars[0].name)
        check_checksum_file(artifact_dir, jars[0])
        inspections.append(inspect_jar(jars[0], profile, snapshot, archive_sha))
    return inspections


def check_source_zip(source_zip: Path, commit: str) -> dict[str, Any]:
    try:
        zip_bytes = source_zip.read_bytes()
    except OSError as error:
        raise PackageError(f"Cannot read source zip {source_zip}: {error}") from error
    try:
        expected_tar = git_bytes("archive", "--format=tar", commit)
        with tarfile.open(fileobj=io.BytesIO(expected_tar), mode="r:") as archive:
            expected = {member.name: archive.extractfile(member).read() for member in archive.getmembers() if member.isfile()}
        with zipfile.ZipFile(io.BytesIO(zip_bytes)) as archive:
            members = [item for item in archive.infolist() if not item.is_dir()]
            roots = {PurePosixPath(item.filename).parts[0] for item in members}
            require(len(roots) == 1, "Source zip must have one top-level directory")
            root_name = next(iter(roots))
            actual = {}
            for item in members:
                parts = PurePosixPath(item.filename).parts
                require(len(parts) > 1 and parts[0] == root_name and ".." not in parts,
                        f"Unsafe or unexpected source zip path {item.filename!r}")
                relative = PurePosixPath(*parts[1:]).as_posix()
                require(relative not in actual, f"Duplicate source zip entry {relative}")
                actual[relative] = archive.read(item)
    except PackageError:
        raise
    except (OSError, KeyError, tarfile.TarError, zipfile.BadZipFile) as error:
        raise PackageError(f"Cannot inspect source zip {source_zip}: {error}") from error
    require(actual.keys() == expected.keys(), "Source zip file set does not match the exact source commit")
    for name, content in expected.items():
        require(actual[name] == content, f"Source zip file differs from exact source commit: {name}")
    return {"sha256": sha256(zip_bytes), "fileCount": len(actual), "contentMatchesSourceCommit": True}


def job_report(inspection: ArtifactInspection) -> dict[str, Any]:
    profile = inspection.profile
    return {
        "minecraft": profile.minecraft,
        "module": profile.module,
        "java": profile.java,
        "artifact": inspection.jar_name,
        "sha256": inspection.sha256,
        "archiveSHA": inspection.archive_sha,
        "digestMatchesCiReceipt": inspection.digest_matches_ci_receipt,
        "adapterClassMajor": inspection.adapter_class_major,
        "coreClassMajor": inspection.core_class_major,
        "navigationClassMajor": inspection.navigation_class_major,
        "exactMinecraftMetadataVerified": True,
        "modVersionVerified": True,
        "verificationFixturesExcluded": True,
        "yarnRefmaps": list(inspection.refmaps),
        "baritone": {
            "version": inspection.baritone_version,
            "sha256": inspection.baritone_sha256,
            "sha256MatchesSourcePin": True,
            "classMajors": list(inspection.baritone_class_majors),
            "nestedLibraries": list(inspection.baritone_nested_libraries),
            "dependencyLedgerEmbeddedFromSource": True,
            "bridgeMetadataEmbeddedFromSource": True,
            "bridgeMetadataSha256": inspection.bridge_sha256,
            "licensesAndNoticeEmbeddedFromSource": True,
            "accessorMixinConfigured": True,
        },
    }


def stage_package(output: Path, inspections: list[ArtifactInspection], snapshot: SourceSnapshot,
                  ci_run: str, source_zip_info: dict[str, Any] | None) -> None:
    require(len(inspections) == 24, f"Cannot package {len(inspections)} artifacts, expected 24")
    require(not output.exists(), f"Output path already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    evidence = {
        "ciHeadShaConfirmed": True,
        "all24JobsPassed": True,
        "sourceCommit": snapshot.commit,
        "ciRun": f"https://github.com/{REPOSITORY}/actions/runs/{ci_run}",
        "checkedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "modVersion": snapshot.mod_version,
        "sourcePackaging": {
            "baritoneGradleSha256": snapshot.gradle_sha256,
            "baritoneFetcherSha256": snapshot.fetch_script_sha256,
            "dependencyLedgerSha256": sha256(snapshot.source_files["third-party/baritone/dependencies.json"]),
            "bridgeMetadataSha256": sha256(snapshot.source_files["third-party/baritone/mining-bridge.json"]),
            "noticeAndLicenseSha256": {
                path: sha256(snapshot.source_files[path])
                for path in (
                    "third-party/baritone/NOTICE.md",
                    "third-party/baritone/licenses/COPYING",
                    "third-party/baritone/licenses/COPYING.LESSER",
                )
            },
        },
        "scope": "Successful CI for all 24 adapter profiles and exact JAR packaging checks. This record does not establish survival acceptance or multiplayer behavior.",
        "artifacts": [job_report(item) for item in inspections],
    }
    if source_zip_info is not None:
        evidence["lodekeeperSourceZip"] = source_zip_info

    stage = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        for item in inspections:
            packaged_jar = stage / item.jar_name
            shutil.copyfile(item.source_path, packaged_jar)
            require(sha256(packaged_jar.read_bytes()) == item.sha256,
                    f"Artifact changed while packaging: {item.source_path}")
        (stage / "SHA256SUMS").write_text(
            "".join(f"{item.sha256}  {item.jar_name}\n" for item in sorted(inspections, key=lambda row: row.jar_name)),
            encoding="utf-8",
        )
        (stage / "build-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        require(len(list(stage.iterdir())) == 26, "Staged release package must contain 24 jars and two evidence files")
        require(len(list(stage.glob("*.jar"))) == 24, "Staged release package must contain exactly 24 jars")
        require(not output.exists(), f"Output path appeared during packaging: {output}")
        stage.rename(output)
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def main() -> int:
    parser = argparse.ArgumentParser(description="Inspect exact Lodekeeper CI jars and stage a release package")
    parser.add_argument("artifact_root", type=Path, help="Directory containing the downloaded CI artifact folders")
    parser.add_argument("source40", help="Full source commit SHA used by the CI run")
    parser.add_argument("ci_run", help="Successful GitHub Actions run ID")
    parser.add_argument("output", type=Path, help="New output directory for 24 jars and release evidence")
    parser.add_argument("receipt_path", type=Path, help="JSON receipt for the exact successful CI run")
    parser.add_argument("modversion", help="Expected mod version, such as 0.1.0-preview.8")
    parser.add_argument("--lodekeeper-source-zip", type=Path,
                        help="Optional Lodekeeper source zip to compare byte-for-byte with source40")
    args = parser.parse_args()

    try:
        snapshot = load_source_snapshot(args.source40, args.modversion)
        _, _, receipt_artifacts = check_receipt(args.receipt_path, args.source40, args.ci_run, snapshot.profiles)
        inspections = inspect_artifacts(args.artifact_root, snapshot, receipt_artifacts)
        require(len(inspections) == 24, f"Inspected {len(inspections)} jars, expected 24")
        source_zip_info = check_source_zip(args.lodekeeper_source_zip, args.source40) if args.lodekeeper_source_zip else None
        stage_package(args.output, inspections, snapshot, args.ci_run, source_zip_info)
    except (PackageError, OSError) as error:
        parser.exit(2, f"error: {error}\n")
    print(f"Inspected and staged 24 exact CI artifacts for {args.source40}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
