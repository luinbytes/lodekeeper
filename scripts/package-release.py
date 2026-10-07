#!/usr/bin/env python3
"""Validate receipt-bound Lodekeeper jars and stage a release directory."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import posixpath
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
WORKFLOW_PATH = ".github/workflows/build.yml"
PROFILE_TABLE_PATH = "gradle/owned-kernel-yarn-profiles.json"
PROFILE_MODULES = {"fabric", "fabric-1202", "fabric-1211", "fabric-1212", "fabric-modern"}
PRIMARY_VERSIONS = {"1.21", "1.21.1"}
MODERN_FAMILIES = {
    "26.1": ("26.1", {"26.1", "26.1.1", "26.1.2"}),
    "26.2": ("26.2", {"26.2"}),
    "26.3": ("26.3", {"26.3"}),
}
LICENSE_FILES = (
    "third-party/baritone/NOTICE.md",
    "third-party/baritone/licenses/COPYING",
    "third-party/baritone/licenses/COPYING.LESSER",
    "third-party/baritone/dependencies.json",
)
PROVENANCE_ROOT = "META-INF/lodekeeper/owned-kernel/"
LICENSE_ROOT = "META-INF/licenses/baritone/"
PROFILE_PATTERN = re.compile(
    r"(?m)^\s*- minecraft: '([^']+)'\s*\n"
    r"\s+java: '([0-9]+)'\s*\n"
    r"\s+module: ([A-Za-z0-9_-]+)\s*$"
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CI_ARTIFACT_DIGEST_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
SOURCE_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
JAVA_CLASS_MAJOR = {17: 61, 21: 65, 25: 69}
EXPECTED_SOURCE_FAMILIES = 14
EXPECTED_PROFILE_TARGETS = {
    "1.20": (17, "fabric"), "1.20.1": (17, "fabric"),
    "1.20.2": (17, "fabric-1202"), "1.20.3": (17, "fabric-1202"),
    "1.20.4": (17, "fabric-1202"),
    "1.20.5": (21, "fabric-1211"), "1.20.6": (21, "fabric-1211"),
    "1.21": (21, "fabric-1211"), "1.21.1": (21, "fabric-1211"),
    "1.21.2": (21, "fabric-1212"), "1.21.3": (21, "fabric-1212"),
    "1.21.4": (21, "fabric-1212"), "1.21.5": (21, "fabric-1212"),
    "1.21.6": (21, "fabric-1212"), "1.21.7": (21, "fabric-1212"),
    "1.21.8": (21, "fabric-1212"), "1.21.9": (21, "fabric-1212"),
    "1.21.10": (21, "fabric-1212"), "1.21.11": (21, "fabric-1212"),
    "26.1": (25, "fabric-modern"), "26.1.1": (25, "fabric-modern"),
    "26.1.2": (25, "fabric-modern"), "26.2": (25, "fabric-modern"),
    "26.3": (25, "fabric-modern"),
}


class PackageError(Exception):
    """A release input failed an explicit packaging check."""


@dataclass(frozen=True)
class Profile:
    minecraft: str
    java: int
    module: str


@dataclass(frozen=True)
class KernelFamily:
    key: str
    project_path: str
    project_dir: str
    lock_path: str
    override_manifest: str
    override_dir: str
    java_release: int | None


@dataclass(frozen=True)
class KernelPin:
    family: KernelFamily
    lock: dict[str, Any]
    lock_bytes: bytes
    override_bytes: bytes
    override_sha256: str
    override_entries: dict[str, str]


@dataclass(frozen=True)
class SourceSnapshot:
    commit: str
    mod_version: str
    profiles: tuple[Profile, ...]
    families: dict[str, KernelPin]
    profile_families: dict[str, str]
    source_files: dict[str, bytes]
    module_manifests: dict[str, dict[str, Any]]
    table: dict[str, Any]


@dataclass(frozen=True)
class ArtifactInspection:
    profile: Profile
    family: KernelPin
    source_path: Path
    jar_name: str
    sha256: str
    archive_sha: str
    adapter_class_major: int
    core_class_major: int
    navigation_class_major: int
    refmaps: tuple[str, ...]
    source_manifest_sha256: str
    generated_source_count: int
    mixin_class_count: int


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
        raise PackageError(f"Cannot read exact source snapshot with git {args[0]}: {detail or error}") from error
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
    require({profile.minecraft: (profile.java, profile.module) for profile in profiles}
            == EXPECTED_PROFILE_TARGETS,
            "Source workflow does not match the exact 24-profile release matrix")
    require({profile.module for profile in profiles} == PROFILE_MODULES,
            "Source workflow does not cover the exact five production modules")
    for profile in profiles:
        require(profile.java in JAVA_CLASS_MAJOR, f"Unsupported Java {profile.java} in profile {profile.minecraft}")
    return profiles


def safe_git_path(path: str, label: str) -> None:
    value = PurePosixPath(path)
    require(path and not re.match(r"^[A-Za-z]:", path) and "\\" not in path
            and value.as_posix() == path and not value.is_absolute()
            and all(part not in {"", ".", ".."} for part in value.parts),
            f"Unsafe {label} path {path!r}")


def list_source_files(commit: str, path: str) -> set[str]:
    if path != ".":
        safe_git_path(path, "Git source")
    output = git_bytes("ls-tree", "-r", "-z", "--name-only", commit, "--", path)
    result = set()
    for raw in output.split(b"\0"):
        if not raw:
            continue
        name = raw.decode("utf-8")
        safe_git_path(name, "Git source")
        result.add(name)
    return result


def parse_override_manifest(data: bytes, label: str) -> dict[str, str]:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PackageError(f"{label} is not UTF-8: {error}") from error
    entries: dict[str, str] = {}
    for line_number, line in enumerate(text.splitlines(), 1):
        if not line:
            continue
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        require(match is not None, f"{label}:{line_number} has an invalid SHA-256 entry")
        digest, path = match.groups()
        safe_git_path(path, label)
        require(path not in entries, f"{label} has duplicate path {path}")
        entries[path] = digest
    require(bool(entries), f"{label} is empty")
    return entries


def parse_lock(commit: str, family: KernelFamily) -> KernelPin:
    lock_path = f"{family.project_dir}/{family.lock_path}"
    override_path = f"{family.project_dir}/{family.override_manifest}"
    lock_bytes = source_file(commit, lock_path)
    lock = parse_json(lock_bytes, f"{family.key} source-lock.json")
    override_bytes = source_file(commit, override_path)
    entries = parse_override_manifest(override_bytes, override_path)
    override_hash = sha256(override_bytes)

    source_commit = str(lock.get("source_commit", ""))
    archive_hash = str(lock.get("archive_sha256", ""))
    source_root = str(lock.get("source_root", ""))
    archive_filename = str(lock.get("archive_filename", ""))
    archive_url = str(lock.get("archive_url", ""))
    repository = str(lock.get("repository", ""))
    require(SOURCE_COMMIT_PATTERN.fullmatch(source_commit) is not None,
            f"{family.key} has no full upstream source commit")
    require(SHA256_PATTERN.fullmatch(archive_hash) is not None,
            f"{family.key} has an invalid upstream archive SHA-256")
    require(repository == "https://github.com/cabaletta/baritone",
            f"{family.key} has an unexpected upstream repository {repository!r}")
    require(source_root == f"baritone-{source_commit}",
            f"{family.key} upstream source root does not match its exact source commit")
    require(archive_filename == f"baritone-{lock.get('baritone_version')}-{source_commit}.tar.gz",
            f"{family.key} archive filename does not identify its pinned source")
    require(archive_url.endswith(f"/{source_commit}.tar.gz"),
            f"{family.key} archive URL does not identify its pinned source")
    dependency_metadata = lock.get("dependency_metadata")
    if dependency_metadata is not None:
        metadata_path = posixpath.normpath(posixpath.join(family.project_dir, str(dependency_metadata)))
        require(metadata_path == "third-party/baritone/dependencies.json",
                f"{family.key} dependency metadata path is unexpected")
        metadata_hash = lock.get("dependency_metadata_sha256")
        require(SHA256_PATTERN.fullmatch(str(metadata_hash or "")) is not None
                and sha256(source_file(commit, metadata_path)) == metadata_hash,
                f"{family.key} dependency metadata SHA-256 does not match its source lock")
    require(entries and all(SHA256_PATTERN.fullmatch(value) for value in entries.values()),
            f"{family.key} has invalid override hashes")

    expected_manifest_hash = (
        lock.get("override_manifest_sha256")
        or (lock.get("effective_lifecycle_overrides") or {}).get("manifest_sha256")
    )
    if expected_manifest_hash is not None:
        require(expected_manifest_hash == override_hash,
                f"{family.key} source-lock override manifest SHA-256 does not match")

    override_tree_path = f"{family.project_dir}/{family.override_dir}"
    tracked_override_files = list_source_files(commit, override_tree_path)
    expected_override_files = {f"{override_tree_path}/{path}" for path in entries}
    require(tracked_override_files == expected_override_files,
            f"{family.key} override manifest file set does not match its exact source tree")
    for path, expected_hash in entries.items():
        content = source_file(commit, f"{override_tree_path}/{path}")
        require(sha256(content) == expected_hash,
                f"{family.key} override hash mismatch for {path}")

    return KernelPin(family, lock, lock_bytes, override_bytes, override_hash, entries)


def load_families(commit: str, table: dict[str, Any]) -> dict[str, KernelPin]:
    family_table = table.get("families")
    require(isinstance(family_table, dict) and len(family_table) == 10,
            "Yarn profile table must contain exactly ten owned source families")
    families: dict[str, KernelFamily] = {}
    for key, row in family_table.items():
        require(isinstance(row, dict), f"Invalid Yarn family record {key}")
        families[key] = KernelFamily(
            key=key,
            project_path=str(row.get("project_path", "")),
            project_dir=str(row.get("project_dir", "")),
            lock_path=str(row.get("lock_path", "")),
            override_manifest=str(row.get("override_manifest", "")),
            override_dir=str(row.get("override_dir", "")),
            java_release=int(row.get("java_release", 0)),
        )
        for field in (families[key].project_dir, families[key].lock_path,
                      families[key].override_manifest, families[key].override_dir):
            safe_git_path(field, f"{key} family")
    families["primary1.21.1"] = KernelFamily(
        "primary1.21.1", ":owned-kernel-primary-1211", "owned-kernel/primary1.21.1",
        "source-lock.json", "lifecycle-overrides.sha256", "lifecycle-overrides", 21
    )
    for key, (directory, _) in MODERN_FAMILIES.items():
        project = f"owned-kernel/modern{directory}"
        families[f"modern{directory}"] = KernelFamily(
            f"modern{directory}", f":owned-kernel-modern{directory.replace('.', '')}", project,
            "source-lock.json", "overrides/lifecycle-overrides.sha256",
            "overrides/lifecycle-overrides", 25
        )

    require(len(families) == EXPECTED_SOURCE_FAMILIES,
            f"Expected {EXPECTED_SOURCE_FAMILIES} owned source families, found {len(families)}")
    return {key: parse_lock(commit, family) for key, family in families.items()}


def profile_family(profile: Profile, table: dict[str, Any], families: dict[str, KernelPin]) -> str:
    if profile.minecraft in table.get("profiles", {}):
        row = table["profiles"][profile.minecraft]
        key = row.get("source_family")
        require(key in table["families"], f"No Yarn source family for {profile.minecraft}")
        require(row.get("host_project_path") == f":{profile.module}",
                f"{profile.minecraft} workflow adapter differs from the owned Yarn profile table")
        family = table["families"][key]
        require(row.get("kernel_project") == family.get("project_path")
                and row.get("kernel_directory") == family.get("project_dir"),
                f"{profile.minecraft} Yarn family project does not match the owned profile table")
        require(int(family.get("java_release", -1)) == profile.java,
                f"{profile.minecraft} workflow Java differs from its owned Yarn family")
        pin = families[str(key)]
        supported = pin.lock.get("minecraft_versions", [pin.lock.get("minecraft_version")])
        require(profile.minecraft in supported,
                f"{profile.minecraft} is absent from its exact upstream source lock")
        return str(key)
    if profile.minecraft in PRIMARY_VERSIONS:
        require(profile.module == "fabric-1211" and profile.java == 21,
                f"{profile.minecraft} is not assigned to the primary 1.21 kernel")
        return "primary1.21.1"
    for family_key, (_, versions) in MODERN_FAMILIES.items():
        if profile.minecraft in versions:
            require(profile.module == "fabric-modern" and profile.java == 25,
                    f"{profile.minecraft} is not assigned to its modern owned kernel")
            key = f"modern{family_key}"
            pin = families[key]
            supported = pin.lock.get("minecraft_versions", [pin.lock.get("minecraft_version")])
            require(profile.minecraft in supported,
                    f"{profile.minecraft} is absent from its exact upstream source lock")
            return key
    raise PackageError(f"No owned source family supports workflow profile {profile.minecraft}")


def load_source_snapshot(commit: str, requested_version: str) -> SourceSnapshot:
    require(SOURCE_COMMIT_PATTERN.fullmatch(commit) is not None,
            "source40 must be a full lowercase 40-character commit SHA")
    git_bytes("cat-file", "-e", f"{commit}^{{commit}}")
    profiles = parse_profiles(source_file(commit, WORKFLOW_PATH))
    table = parse_json(source_file(commit, PROFILE_TABLE_PATH), "owned-kernel Yarn profile table")
    require(table.get("schema_version") == 1, "Unsupported owned-kernel profile table schema")
    families = load_families(commit, table)
    profile_families = {
        profile.minecraft: profile_family(profile, table, families) for profile in profiles
    }
    require(len(profiles) == 24, "Source profile table must preserve all 24 CI profiles")
    require(len({profile_families[p.minecraft] for p in profiles}) == 14,
            "The exact CI profile set must cover all fourteen source families")

    source_files = {path: source_file(commit, path) for path in LICENSE_FILES}
    for path in LICENSE_FILES:
        require(bool(source_files[path]), f"Required upstream notice/license file is empty: {path}")
    dependencies = parse_json(source_files["third-party/baritone/dependencies.json"],
                              "Baritone dependency metadata")
    require(dependencies.get("schema_version") == 1, "Unsupported Baritone dependency metadata schema")

    properties = source_file(commit, "gradle.properties").decode("utf-8")
    versions = re.findall(r"(?m)^mod_version=([^\s#]+)\s*$", properties)
    require(len(versions) == 1 and versions[0] == requested_version,
            f"Requested mod version {requested_version} does not match source snapshot")

    module_manifests: dict[str, dict[str, Any]] = {}
    for module in sorted(PROFILE_MODULES):
        path = f"{module}/src/main/resources/fabric.mod.json"
        module_manifests[module] = parse_json(source_file(commit, path), path)
    return SourceSnapshot(commit, requested_version, profiles, families, profile_families,
                          source_files, module_manifests, table)


def check_receipt(receipt_path: Path, commit: str, ci_run: str,
                  profiles: tuple[Profile, ...]) -> dict[str, dict[str, Any]]:
    try:
        receipt = parse_json(receipt_path.read_bytes(), f"CI receipt {receipt_path}")
    except OSError as error:
        raise PackageError(f"Cannot read CI receipt {receipt_path}: {error}") from error
    require(ci_run.isdigit(), "ci-run must be a numeric GitHub Actions run ID")
    require(str(receipt.get("databaseId")) == ci_run, "CI receipt run ID does not match ci-run")
    require(receipt.get("url") == f"https://github.com/{REPOSITORY}/actions/runs/{ci_run}",
            "CI receipt URL does not match this repository and run")
    require(receipt.get("workflowName") == WORKFLOW_NAME,
            f"CI receipt is not for {WORKFLOW_NAME!r}")
    require(receipt.get("headSha") == commit, "CI receipt head SHA does not match source40")
    require(receipt.get("status") == "completed" and receipt.get("conclusion") == "success",
            "CI run has not completed successfully")

    expected_jobs = {f"adapters ({p.minecraft}, {p.java}, {p.module})" for p in profiles}
    jobs = receipt.get("jobs")
    require(isinstance(jobs, list) and len(jobs) == 24, "CI receipt must contain exactly 24 adapter jobs")
    actual_jobs = {job.get("name") for job in jobs if isinstance(job, dict)}
    require(actual_jobs == expected_jobs, "CI receipt job names do not match the exact source matrix")
    for job in jobs:
        require(isinstance(job, dict) and job.get("status") == "completed"
                and job.get("conclusion") == "success",
                f"CI job did not pass: {job.get('name') if isinstance(job, dict) else job}")

    expected_artifacts = {f"lodekeeper-{p.minecraft}-development" for p in profiles}
    artifacts = receipt.get("artifacts")
    require(isinstance(artifacts, list) and len(artifacts) == 24,
            "CI receipt must contain exactly 24 artifacts")
    require({artifact.get("name") for artifact in artifacts if isinstance(artifact, dict)} == expected_artifacts,
            "CI receipt artifacts do not match the exact source matrix")
    ids: set[str] = set()
    for artifact in artifacts:
        require(isinstance(artifact, dict) and artifact.get("expired") is False,
                f"CI artifact is expired or malformed: {artifact}")
        workflow_run = artifact.get("workflow_run")
        require(isinstance(workflow_run, dict) and str(workflow_run.get("id")) == ci_run
                and workflow_run.get("head_sha") == commit,
                f"CI artifact {artifact.get('name')} is not bound to the exact run and source")
        require(CI_ARTIFACT_DIGEST_PATTERN.fullmatch(str(artifact.get("digest", ""))) is not None,
                f"CI artifact {artifact.get('name')} has no SHA-256 ZIP digest")
        identifier = str(artifact.get("id", ""))
        require(identifier and identifier not in ids, f"Missing or duplicate artifact ID for {artifact.get('name')}")
        ids.add(identifier)
    return {artifact["name"]: artifact for artifact in artifacts}


def read_class_major(archive: zipfile.ZipFile, names: list[str], exact_name: str, label: str) -> int:
    require(exact_name in names, f"{label} class is missing: {exact_name}")
    data = archive.read(exact_name)
    require(len(data) >= 8 and data[:4] == b"\xca\xfe\xba\xbe", f"Invalid Java class header in {exact_name}")
    return int.from_bytes(data[6:8], "big")


def validate_source_manifest(manifest: dict[str, Any], family: KernelPin,
                             profile: Profile, names: list[str], archive: zipfile.ZipFile) -> tuple[int, tuple[str, ...], tuple[str, ...]]:
    compile_target = manifest.get("compile_target")
    require(isinstance(compile_target, dict)
            and compile_target.get("minecraft_version") == profile.minecraft
            and compile_target.get("java_release") == profile.java,
            f"Embedded source manifest compile target does not match {profile.minecraft}/Java {profile.java}")
    require(isinstance(compile_target.get("mappings"), str) and compile_target["mappings"],
            "Embedded source manifest has no compile-target mappings")

    lock = family.lock
    if "upstream" in manifest:
        upstream = manifest["upstream"]
        require(isinstance(upstream, dict), "Embedded source manifest has an invalid upstream record")
        upstream_commit = upstream.get("commit")
        upstream_hash = upstream.get("archive_sha256")
    else:
        pins = manifest.get("source_pins")
        upstream_commit = manifest.get("baritone_source_commit")
        upstream_hash = pins.get("archive_sha256") if isinstance(pins, dict) else None
    require(upstream_commit == lock.get("source_commit")
            and upstream_hash == lock.get("archive_sha256"),
            f"Embedded source manifest upstream pin differs from {family.family.key}")

    if isinstance(manifest.get("override_manifest_sha256"), str):
        require(manifest["override_manifest_sha256"] == family.override_sha256,
                f"Embedded source manifest override hash differs from {family.family.key}")
    lifecycle = manifest.get("lifecycle_overrides")
    if isinstance(lifecycle, dict):
        effective = lifecycle.get("effective", lifecycle)
        if isinstance(effective, dict):
            require(effective == family.override_entries,
                    f"Embedded source manifest lifecycle hashes differ from {family.family.key}")

    generated = manifest.get("generated_files")
    require(isinstance(generated, dict) and bool(generated),
            "Embedded source manifest has no generated source inventory")
    for path, digest in generated.items():
        safe_git_path(path, "generated source manifest")
        require(SHA256_PATTERN.fullmatch(str(digest)) is not None,
                f"Invalid generated-source hash for {path}")
        parts = PurePosixPath(path).parts
        if path.endswith(".java"):
            require(len(parts) >= 3 and parts[1] == "java",
                    f"Generated Java source has an unexpected source root: {path}")
            compiled_class = PurePosixPath(*parts[2:]).with_suffix(".class").as_posix()
            require(compiled_class in names,
                    f"Generated owned-kernel class is missing: {compiled_class}")

    mixin_config_path = manifest.get("mixin_config")
    if not isinstance(mixin_config_path, str):
        candidates = [name for name in names if PurePosixPath(name).name == "mixins.lodekeeper-kernel.json"]
        require(len(candidates) == 1, "Owned-kernel mixin config must occur exactly once")
        mixin_config_entry = candidates[0]
    else:
        candidates = [name for name in names if PurePosixPath(name).name == PurePosixPath(mixin_config_path).name]
        require(len(candidates) == 1, "Owned-kernel mixin config named by source manifest must occur once")
        mixin_config_entry = candidates[0]
    mixin_config = parse_json(archive.read(mixin_config_entry), "owned-kernel mixin configuration")
    mixin_package = mixin_config.get("package")
    client_mixins = mixin_config.get("client")
    require(mixin_config.get("required") is True
            and isinstance(mixin_package, str)
            and mixin_package == "dev.lodekeeper.navigation.kernel.launch.mixins"
            and isinstance(client_mixins, list) and bool(client_mixins),
            "Owned-kernel mixin configuration is not required or has no client mixins")
    mixin_classes: list[str] = []
    for mixin in client_mixins:
        require(isinstance(mixin, str) and mixin and "/" not in mixin,
                f"Invalid owned-kernel client mixin name {mixin!r}")
        class_path = mixin_package.replace(".", "/") + "/" + mixin.replace(".", "$") + ".class"
        require(class_path in names, f"Owned-kernel mixin class is missing: {class_path}")
        class_bytes = archive.read(class_path)
        require(len(class_bytes) >= 8 and class_bytes[:4] == b"\xca\xfe\xba\xbe"
                and int.from_bytes(class_bytes[6:8], "big") == profile.java + 44,
                f"Owned-kernel mixin class does not match Java {profile.java}: {class_path}")
        mixin_classes.append(class_path)

    refmaps = tuple(sorted(name for name in names if name.endswith("refmap.json")))
    configured_refmap = mixin_config.get("refmap")
    if profile.module == "fabric-modern":
        require(not refmaps and not configured_refmap and not manifest.get("refmap"),
                f"Modern official-mapping artifact unexpectedly contains a Yarn refmap: {refmaps}")
    else:
        require(bool(refmaps), f"{profile.minecraft} Yarn artifact has no refmap")
        require(isinstance(configured_refmap, str)
                and any(PurePosixPath(name).name == PurePosixPath(configured_refmap).name for name in refmaps),
                f"{profile.minecraft} source manifest mixin config does not name an embedded refmap")
        for refmap in refmaps:
            content = parse_json(archive.read(refmap), f"{profile.minecraft} {refmap}")
            require(isinstance(content.get("mappings"), dict) and bool(content["mappings"]),
                    f"{profile.minecraft} Yarn refmap {refmap} has no mappings")
    return len(generated), refmaps, tuple(mixin_classes)


def verify_owned_runtime(names: list[str], archive: zipfile.ZipFile) -> None:
    require(not any(name.endswith(".jar") for name in names),
            "Final artifact contains a nested jar")
    class_names = [name for name in names if name.endswith(".class")]
    require(bool(class_names), "Final artifact contains no classes")
    require(any(name.startswith("dev/lodekeeper/navigation/kernel/") for name in class_names),
            "Final artifact has no flattened owned-kernel classes")
    forbidden_prefixes = (
        "baritone/",
        "com/github/cabaletta/baritone/",
        "dev/lodekeeper/baritone/",
    )
    forbidden_names = [
        name for name in names
        if name.startswith(forbidden_prefixes)
        or (name.endswith(".class") and "baritone" in PurePosixPath(name).parts)
        or name in {
            "META-INF/lodekeeper/baritone/mining-bridge.json",
            "lodekeeper-baritone.mixins.json",
        }
        or any(token in name for token in (
            "BaritoneMiningAccess", "BaritoneMiningAccessor", "inspect-baritone-mining",
            "mining-bridge.json",
        ))
    ]
    require(not forbidden_names, f"Final artifact contains forbidden legacy Baritone runtime entries: {forbidden_names[:5]}")
    for name in names:
        if name.startswith("META-INF/services/"):
            require("baritone" not in name.lower(),
                    f"Final artifact contains a Baritone service provider descriptor: {name}")
            service_data = archive.read(name).decode("utf-8", errors="replace")
            providers = [line.split("#", 1)[0].strip() for line in service_data.splitlines()]
            require(not any("baritone" in provider.lower() for provider in providers),
                    f"Final artifact registers a Baritone service provider: {name}")


def inspect_jar(jar: Path, profile: Profile, family: KernelPin, snapshot: SourceSnapshot,
                archive_sha: str) -> ArtifactInspection:
    expected_name = f"lodekeeper-{profile.minecraft}-{snapshot.mod_version}.jar"
    require(jar.name == expected_name, f"Artifact filename is {jar.name}, expected {expected_name}")
    try:
        data = jar.read_bytes()
    except OSError as error:
        raise PackageError(f"Cannot read artifact {jar}: {error}") from error
    digest = sha256(data)
    source_module = snapshot.module_manifests[profile.module]
    provenance = PROVENANCE_ROOT
    lock_path = provenance + "source-lock.json"
    override_path = provenance + "lifecycle-overrides.sha256"
    manifest_path = provenance + "source-manifest.json"
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), f"{jar.name} has duplicate ZIP entries")
            require("fabric.mod.json" in names, f"{jar.name} has no root fabric.mod.json")
            manifest = parse_json(archive.read("fabric.mod.json"), f"{jar.name} fabric.mod.json")
            require(manifest.get("id") == "lodekeeper", f"{jar.name} has mod id {manifest.get('id')!r}")
            require(manifest.get("version") == snapshot.mod_version, f"{jar.name} has the wrong mod version")
            for field in ("schemaVersion", "name", "description", "environment", "entrypoints", "license"):
                require(manifest.get(field) == source_module.get(field),
                        f"{jar.name} {field} differs from the exact source descriptor")
            depends = manifest.get("depends")
            require(isinstance(depends, dict)
                    and depends.get("minecraft") == profile.minecraft
                    and depends.get("java") == f">={profile.java}",
                    f"{jar.name} Fabric metadata does not declare exact Minecraft and Java requirements")
            source_depends = source_module.get("depends")
            require(isinstance(source_depends, dict)
                    and all(depends.get(key) == value for key, value in source_depends.items()
                            if key not in {"minecraft", "java"}),
                    f"{jar.name} Fabric dependencies differ from the exact source descriptor")
            verification = [name for name in names if "verification" in name.lower()]
            require(not verification, f"{jar.name} contains verification fixtures: {verification[:5]}")
            verify_owned_runtime(names, archive)

            adapter_suffix = "/modern/AutomationEngine.class" if profile.module == "fabric-modern" else "/AutomationEngine.class"
            adapters = [name for name in names if name.endswith(adapter_suffix)]
            require(len(adapters) == 1, f"{jar.name} must contain exactly one adapter AutomationEngine class")
            adapter_major = read_class_major(archive, names, adapters[0], "Adapter")
            core_major = read_class_major(archive, names, "dev/lodekeeper/core/AcquisitionPlanner.class", "Core")
            navigation_major = read_class_major(archive, names, "dev/lodekeeper/nav/Goal.class", "Navigation")
            require(adapter_major == profile.java + 44,
                    f"{jar.name} adapter class major {adapter_major} does not match Java {profile.java}")
            require(core_major == JAVA_CLASS_MAJOR[17] and navigation_major == JAVA_CLASS_MAJOR[17],
                    f"{jar.name} core/navigation class majors are {core_major}/{navigation_major}, expected Java 17")

            require(lock_path in names and archive.read(lock_path) == family.lock_bytes,
                    f"{jar.name} embedded source lock differs from exact source commit")
            require(override_path in names and archive.read(override_path) == family.override_bytes,
                    f"{jar.name} embedded lifecycle override manifest differs from exact source commit")
            require(manifest_path in names, f"{jar.name} has no owned-kernel source manifest")
            source_manifest_bytes = archive.read(manifest_path)
            source_manifest = parse_json(source_manifest_bytes, f"{jar.name} owned-kernel source manifest")
            generated_count, refmaps, mixin_classes = validate_source_manifest(
                source_manifest, family, profile, names, archive
            )
            fabric_mixins = manifest.get("mixins")
            require(isinstance(fabric_mixins, list), f"{jar.name} has no Fabric mixin configuration list")
            mixin_config_path = source_manifest.get("mixin_config")
            expected_mixin_config = (
                PurePosixPath(mixin_config_path).name
                if isinstance(mixin_config_path, str) else "mixins.lodekeeper-kernel.json"
            )
            configured_mixins = [
                entry if isinstance(entry, str) else entry.get("config") if isinstance(entry, dict) else None
                for entry in fabric_mixins
            ]
            source_mixins = source_module.get("mixins")
            require(isinstance(source_mixins, list),
                    f"{jar.name} source descriptor has no mixin configuration list")
            for module_mixin in source_mixins:
                require(module_mixin in configured_mixins,
                        f"{jar.name} omits module mixin config {module_mixin}")
            require(any(isinstance(entry, str) and PurePosixPath(entry).name == expected_mixin_config
                        for entry in configured_mixins),
                    f"{jar.name} does not enable owned-kernel mixin config {expected_mixin_config}")
            require(any(name.startswith("dev/lodekeeper/navigation/kernel/") for name in names if name.endswith(".class")),
                    f"{jar.name} has no owned-kernel class files")
            require(mixin_classes, f"{jar.name} has no packaged owned-kernel mixin classes")

            require("META-INF/MANIFEST.MF" in names, f"{jar.name} has no Loom mapping namespace manifest")
            jar_manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
            mapping_namespaces = re.findall(r"(?m)^Fabric-Mapping-Namespace: ([^\r\n]+)", jar_manifest)
            minecraft_versions = re.findall(r"(?m)^Fabric-Minecraft-Version: ([^\r\n]+)", jar_manifest)
            expected_namespace = "official" if profile.module == "fabric-modern" else "intermediary"
            require(mapping_namespaces == [expected_namespace],
                    f"{jar.name} mapping namespace is {mapping_namespaces}, expected {expected_namespace}")
            require(minecraft_versions == [profile.minecraft],
                    f"{jar.name} Loom Minecraft metadata is {minecraft_versions}, expected {profile.minecraft}")

            for source_path, content in snapshot.source_files.items():
                embedded = LICENSE_ROOT + PurePosixPath(source_path).name
                require(embedded in names and archive.read(embedded) == content,
                        f"{jar.name} is missing exact licensed source file {source_path}")
    except PackageError:
        raise
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise PackageError(f"Cannot inspect {jar}: {error}") from error

    return ArtifactInspection(
        profile, family, jar, jar.name, digest, archive_sha, adapter_major, core_major,
        navigation_major, refmaps, sha256(source_manifest_bytes), generated_count, len(mixin_classes)
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
    require(PurePosixPath(fields[1].lstrip("* ")).name == jar.name,
            f"{checksum_path} names a different artifact")
    require(fields[0] == sha256(jar.read_bytes()), f"Checksum mismatch for {jar}")


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify_ci_artifact_zip(archive_path: Path, artifact: dict[str, Any], artifact_name: str,
                           artifact_dir: Path, jar_name: str) -> str:
    try:
        archive_sha = "sha256:" + hash_file(archive_path)
        with zipfile.ZipFile(archive_path) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            require(len(names) == len(set(names)), f"Original CI archive has duplicate entries: {artifact_name}")
            for name in names:
                safe_git_path(name, f"{artifact_name} ZIP")
            require(all(not entry.is_dir() for entry in entries),
                    f"Original CI archive contains unexpected directories: {artifact_name}")
            require(len(entries) == 2 and set(names) == {jar_name, "SHA256SUMS"},
                    f"Original CI archive must contain only {jar_name} and SHA256SUMS")
            contents = {name: archive.read(name) for name in names}
    except PackageError:
        raise
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise PackageError(f"Cannot inspect original CI artifact archive {archive_path}: {error}") from error
    require(archive_sha == artifact["digest"],
            f"Original CI artifact ZIP digest differs from receipt for {artifact_name}")

    extracted = list(artifact_dir.iterdir())
    require({path.name for path in extracted} == {jar_name, "SHA256SUMS"} and len(extracted) == 2,
            f"Extracted artifact directory must contain only {jar_name} and SHA256SUMS")
    for name, archived_content in contents.items():
        path = artifact_dir / name
        require(not path.is_symlink() and path.is_file(),
                f"Extracted artifact entry is not a regular file: {path}")
        require(path.read_bytes() == archived_content,
                f"Extracted artifact entry differs from receipt-bound ZIP: {path}")
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
        require(len(jars) == 1,
                f"{artifact_name} must contain exactly one jar, found {[jar.name for jar in jars]}")
        archive_sha = verify_ci_artifact_zip(
            artifact_root / f"{artifact_name}.zip", receipt_artifacts[artifact_name],
            artifact_name, artifact_dir, jars[0].name
        )
        check_checksum_file(artifact_dir, jars[0])
        family = snapshot.families[snapshot.profile_families[profile.minecraft]]
        inspections.append(inspect_jar(jars[0], profile, family, snapshot, archive_sha))
    return inspections


def check_source_zip(source_zip: Path, commit: str) -> dict[str, Any]:
    try:
        zip_digest = hash_file(source_zip)
        with zipfile.ZipFile(source_zip) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            require(len(names) == len(set(names)), "Source ZIP has duplicate entries")
            files = [entry for entry in entries if not entry.is_dir()]
            roots = {PurePosixPath(entry.filename).parts[0] for entry in files}
            require(len(roots) == 1, "Source ZIP must have one top-level directory")
            root_name = next(iter(roots))
            actual: dict[str, str] = {}
            for entry in files:
                path = PurePosixPath(entry.filename)
                safe_git_path(entry.filename, "source ZIP")
                require(len(path.parts) > 1 and path.parts[0] == root_name,
                        f"Source ZIP has an unexpected path {entry.filename!r}")
                relative = PurePosixPath(*path.parts[1:]).as_posix()
                require(relative not in actual, f"Source ZIP has duplicate path {relative}")
                actual[relative] = sha256(archive.read(entry))
    except PackageError:
        raise
    except (OSError, zipfile.BadZipFile) as error:
        raise PackageError(f"Cannot inspect source ZIP {source_zip}: {error}") from error

    expected_files = list_source_files(commit, ".")
    require(set(actual) == expected_files, "Source ZIP file set does not match the exact source commit")
    for path in sorted(expected_files):
        require(actual[path] == sha256(source_file(commit, path)),
                f"Source ZIP differs from exact source commit at {path}")
    return {"sha256": zip_digest, "fileCount": len(actual), "contentMatchesSourceCommit": True}


def job_report(row: ArtifactInspection) -> dict[str, Any]:
    return {
        "minecraft": row.profile.minecraft,
        "module": row.profile.module,
        "java": row.profile.java,
        "artifact": row.jar_name,
        "sha256": row.sha256,
        "artifactZipSha256": row.archive_sha,
        "digestMatchesCiReceipt": True,
        "adapterClassMajor": row.adapter_class_major,
        "coreClassMajor": row.core_class_major,
        "navigationClassMajor": row.navigation_class_major,
        "exactMinecraftAndJavaMetadataVerified": True,
        "verificationFixturesExcluded": True,
        "sourceFamily": row.family.family.key,
        "sourceCommit": row.family.lock["source_commit"],
        "upstreamArchiveSha256": row.family.lock["archive_sha256"],
        "sourceLockSha256": sha256(row.family.lock_bytes),
        "lifecycleOverridesSha256": row.family.override_sha256,
        "sourceManifestSha256": row.source_manifest_sha256,
        "generatedSourceFiles": row.generated_source_count,
        "ownedKernelMixinClasses": row.mixin_class_count,
        "flattenedOwnedKernel": True,
        "baritoneRuntimeAbsent": True,
        "yarnRefmaps": list(row.refmaps),
    }


def stage_package(output: Path, inspections: list[ArtifactInspection], snapshot: SourceSnapshot,
                  ci_run: str, source_zip_info: dict[str, Any] | None) -> None:
    require(len(inspections) == 24, f"Cannot package {len(inspections)} artifacts, expected 24")
    require(not output.exists(), f"Output path already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    evidence: dict[str, Any] = {
        "ciHeadShaConfirmed": True,
        "all24JobsPassed": True,
        "sourceCommit": snapshot.commit,
        "ciRun": f"https://github.com/{REPOSITORY}/actions/runs/{ci_run}",
        "checkedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "modVersion": snapshot.mod_version,
        "ownedKernelSourceFamilies": EXPECTED_SOURCE_FAMILIES,
        "sourceFamilies": {
            key: {
                "sourceCommit": pin.lock["source_commit"],
                "archiveSha256": pin.lock["archive_sha256"],
                "sourceLockSha256": sha256(pin.lock_bytes),
                "lifecycleOverridesSha256": pin.override_sha256,
            }
            for key, pin in sorted(snapshot.families.items())
        },
        "upstreamNoticesAndLicensesSha256": {
            path: sha256(content) for path, content in snapshot.source_files.items()
        },
        "scope": "All 24 exact CI artifacts passed receipt, metadata, Java 17 core/navigation, owned-kernel provenance, and runtime exclusion checks. This does not establish in-game survival acceptance or multiplayer behavior.",
        "artifacts": [job_report(item) for item in inspections],
    }
    if source_zip_info is not None:
        evidence["lodekeeperSourceZip"] = source_zip_info

    stage = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        for item in inspections:
            destination = stage / item.jar_name
            shutil.copyfile(item.source_path, destination)
            require(hash_file(destination) == item.sha256,
                    f"Artifact changed while packaging: {item.source_path}")
        checksum_lines = "".join(
            f"{item.sha256}  {item.jar_name}\n"
            for item in sorted(inspections, key=lambda row: row.jar_name)
        )
        (stage / "SHA256SUMS").write_text(checksum_lines, encoding="utf-8")
        (stage / "build-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        require(len(list(stage.iterdir())) == 26, "Release package must contain 24 jars and two evidence files")
        require(len(list(stage.glob("*.jar"))) == 24, "Release package must contain exactly 24 jars")
        require(not output.exists(), f"Output path appeared during packaging: {output}")
        stage.rename(output)
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact_root", type=Path,
                        help="Directory containing the downloaded CI artifact folders and ZIP files")
    parser.add_argument("source40", help="Full source commit SHA used by the CI run")
    parser.add_argument("ci_run", help="Successful GitHub Actions run ID")
    parser.add_argument("output", type=Path, help="New output directory for 24 jars and release evidence")
    parser.add_argument("receipt_path", type=Path, help="JSON receipt for the exact successful CI run")
    parser.add_argument("modversion", help="Expected mod version, such as 0.1.0-preview.11")
    parser.add_argument("--lodekeeper-source-zip", type=Path,
                        help="Optional exact source ZIP checked against source40")
    args = parser.parse_args()
    try:
        snapshot = load_source_snapshot(args.source40, args.modversion)
        receipt_artifacts = check_receipt(args.receipt_path, args.source40, args.ci_run, snapshot.profiles)
        inspections = inspect_artifacts(args.artifact_root, snapshot, receipt_artifacts)
        source_zip_info = check_source_zip(args.lodekeeper_source_zip, args.source40) if args.lodekeeper_source_zip else None
        stage_package(args.output, inspections, snapshot, args.ci_run, source_zip_info)
    except (PackageError, OSError, ValueError) as error:
        parser.exit(2, f"error: {error}\n")
    print(f"Inspected and staged 24 exact CI artifacts for {args.source40}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
