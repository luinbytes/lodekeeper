#!/usr/bin/env python3
"""Build one source bundle containing the exact Lodekeeper tree and pinned kernel sources."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import posixpath
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PROFILE_TABLE_PATH = "gradle/owned-kernel-yarn-profiles.json"
WORKFLOW_PATH = ".github/workflows/build.yml"
LICENSE_FILES = (
    "third-party/baritone/NOTICE.md",
    "third-party/baritone/licenses/COPYING",
    "third-party/baritone/licenses/COPYING.LESSER",
    "third-party/baritone/dependencies.json",
)
PRIMARY_VERSIONS = {"1.21", "1.21.1"}
MODERN_VERSIONS = {
    "modern26.1": {"26.1", "26.1.1", "26.1.2"},
    "modern26.2": {"26.2"},
    "modern26.3": {"26.3"},
}
PROFILE_PATTERN = re.compile(
    r"(?m)^\s*- minecraft: '([^']+)'\s*\n"
    r"\s+java: '([0-9]+)'\s*\n"
    r"\s+module: ([A-Za-z0-9_-]+)\s*$"
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
MAX_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_TAR_MEMBERS = 200_000
COPY_BUFFER_SIZE = 1024 * 1024
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


class SourcePackageError(Exception):
    """A pinned source input failed an explicit packaging check."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SourcePackageError(message)


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(COPY_BUFFER_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def git_bytes(*args: str) -> bytes:
    try:
        result = subprocess.run(
            ["git", *args], cwd=ROOT, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
    except (OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, "stderr", b"")
        if isinstance(detail, bytes):
            detail = detail.decode("utf-8", errors="replace").strip()
        raise SourcePackageError(f"Cannot read exact source snapshot with git {args[0]}: {detail or error}") from error
    return result.stdout


def source_file(commit: str, path: str) -> bytes:
    try:
        return git_bytes("show", f"{commit}:{path}")
    except SourcePackageError as error:
        raise SourcePackageError(f"Source commit {commit} does not contain {path}: {error}") from error


def parse_json(data: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourcePackageError(f"{label} is not UTF-8 JSON: {error}") from error
    require(isinstance(value, dict), f"{label} must contain a JSON object")
    return value


def safe_path(path: str, label: str) -> None:
    value = PurePosixPath(path)
    require(path and not re.match(r"^[A-Za-z]:", path) and "\\" not in path
            and value.as_posix() == path and not value.is_absolute()
            and all(part not in {"", ".", ".."} for part in value.parts),
            f"Unsafe {label} path {path!r}")


def file_list(commit: str, path: str) -> set[str]:
    if path != ".":
        safe_path(path, "Git tree")
    try:
        output = git_bytes("ls-tree", "-r", "-z", "--name-only", commit, "--", path)
    except SourcePackageError:
        raise
    names = set()
    for raw in output.split(b"\0"):
        if raw:
            name = raw.decode("utf-8")
            safe_path(name, "Git tree")
            names.add(name)
    return names


def parse_override_manifest(data: bytes, label: str) -> dict[str, str]:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SourcePackageError(f"{label} is not UTF-8: {error}") from error
    entries: dict[str, str] = {}
    for line_number, line in enumerate(text.splitlines(), 1):
        if not line:
            continue
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        require(match is not None, f"{label}:{line_number} has an invalid hash entry")
        digest, path = match.groups()
        safe_path(path, label)
        require(path not in entries, f"{label} repeats {path}")
        entries[path] = digest
    require(bool(entries), f"{label} is empty")
    return entries


def load_families(commit: str, table: dict[str, Any]) -> dict[str, dict[str, Any]]:
    yarn = table.get("families")
    require(isinstance(yarn, dict) and len(yarn) == 10,
            "Owned Yarn profile table must contain exactly ten source families")
    rows: dict[str, dict[str, Any]] = {}
    for key, row in yarn.items():
        require(isinstance(row, dict), f"Invalid Yarn family row {key}")
        rows[key] = {
            "key": key,
            "project_dir": str(row.get("project_dir", "")),
            "lock_path": str(row.get("lock_path", "")),
            "override_dir": str(row.get("override_dir", "")),
            "override_manifest": str(row.get("override_manifest", "")),
            "java_release": int(row.get("java_release", 0)),
        }
    rows["primary1.21.1"] = {
        "key": "primary1.21.1", "project_dir": "owned-kernel/primary1.21.1",
        "lock_path": "source-lock.json", "override_dir": "lifecycle-overrides",
        "override_manifest": "lifecycle-overrides.sha256", "java_release": 21,
    }
    for key in MODERN_VERSIONS:
        directory = "owned-kernel/" + key
        rows[key] = {
            "key": key, "project_dir": directory, "lock_path": "source-lock.json",
            "override_dir": "overrides/lifecycle-overrides",
            "override_manifest": "overrides/lifecycle-overrides.sha256", "java_release": 25,
        }
    require(len(rows) == 14, f"Expected fourteen source families, found {len(rows)}")

    for row in rows.values():
        key = row["key"]
        for field in ("project_dir", "lock_path", "override_dir", "override_manifest"):
            safe_path(row[field], f"{key} family")
        row["lock_file"] = row["project_dir"] + "/" + row["lock_path"]
        row["override_file"] = row["project_dir"] + "/" + row["override_manifest"]
        row["override_tree"] = row["project_dir"] + "/" + row["override_dir"]
        row["lock_bytes"] = source_file(commit, row["lock_file"])
        row["lock"] = parse_json(row["lock_bytes"], f"{key} source lock")
        row["override_bytes"] = source_file(commit, row["override_file"])
        row["override_sha256"] = hashlib.sha256(row["override_bytes"]).hexdigest()
        row["override_entries"] = parse_override_manifest(row["override_bytes"], row["override_file"])
        lock = row["lock"]
        source_commit = str(lock.get("source_commit", ""))
        require(COMMIT_PATTERN.fullmatch(source_commit) is not None,
                f"{key} lock has no exact upstream commit")
        require(lock.get("source_root") == f"baritone-{source_commit}",
                f"{key} source root does not match its commit")
        require(lock.get("repository") == "https://github.com/cabaletta/baritone",
                f"{key} lock has an unexpected upstream repository")
        archive_hash = str(lock.get("archive_sha256", ""))
        require(SHA256_PATTERN.fullmatch(archive_hash) is not None,
                f"{key} lock has an invalid upstream archive hash")
        require(lock.get("archive_filename")
                == f"baritone-{lock.get('baritone_version')}-{source_commit}.tar.gz",
                f"{key} archive filename differs from its source pin")
        require(str(lock.get("archive_url", "")).endswith(f"/{source_commit}.tar.gz"),
                f"{key} archive URL differs from its source pin")
        require(int(lock.get("java_version", row["java_release"])) == row["java_release"],
                f"{key} source-lock Java version differs from its compile family")
        expected_hash = lock.get("override_manifest_sha256")
        if expected_hash is None:
            override_record = lock.get("effective_lifecycle_overrides")
            if isinstance(override_record, dict):
                expected_hash = override_record.get("manifest_sha256")
        if expected_hash is not None:
            require(expected_hash == row["override_sha256"],
                    f"{key} lock does not match its lifecycle override manifest hash")
        dependency_metadata = lock.get("dependency_metadata")
        if dependency_metadata is not None:
            metadata_path = posixpath.normpath(posixpath.join(row["project_dir"], str(dependency_metadata)))
            require(metadata_path == "third-party/baritone/dependencies.json",
                    f"{key} dependency metadata path is unexpected")
            metadata_hash = lock.get("dependency_metadata_sha256")
            require(SHA256_PATTERN.fullmatch(str(metadata_hash or "")) is not None
                    and hashlib.sha256(source_file(commit, metadata_path)).hexdigest() == metadata_hash,
                    f"{key} dependency metadata SHA-256 does not match its source lock")
        actual_files = file_list(commit, row["override_tree"])
        expected_files = {row["override_tree"] + "/" + path for path in row["override_entries"]}
        require(actual_files == expected_files,
                f"{key} lifecycle override file set differs from its manifest")
        for path, digest in row["override_entries"].items():
            require(hashlib.sha256(source_file(commit, row["override_tree"] + "/" + path)).hexdigest() == digest,
                    f"{key} lifecycle override hash mismatch: {path}")
    return rows


def validate_snapshot(commit: str) -> tuple[dict[str, Any], dict[str, Any], dict[str, bytes]]:
    require(COMMIT_PATTERN.fullmatch(commit) is not None,
            "source40 must be a full lowercase 40-character Git commit")
    git_bytes("cat-file", "-e", f"{commit}^{{commit}}")
    table = parse_json(source_file(commit, PROFILE_TABLE_PATH), "owned-kernel profile table")
    require(table.get("schema_version") == 1, "Unsupported owned-kernel profile table schema")
    workflow = source_file(commit, WORKFLOW_PATH).decode("utf-8")
    profiles = [row for row in PROFILE_PATTERN.findall(workflow)]
    require(len(profiles) == 24 and len({row[0] for row in profiles}) == 24
            and {minecraft: (int(java), module) for minecraft, java, module in profiles}
            == EXPECTED_PROFILE_TARGETS,
            "Exact source snapshot must retain the exact 24-profile CI matrix")
    families = load_families(commit, table)

    covered: set[str] = set()
    table_profiles = table.get("profiles")
    require(isinstance(table_profiles, dict), "Owned Yarn table has no profile records")
    for minecraft, java, module in profiles:
        if minecraft in table_profiles:
            row = table_profiles[minecraft]
            key = row.get("source_family")
            require(key in table["families"], f"No source family for Yarn profile {minecraft}")
            family = table["families"][key]
            require(row.get("host_project_path") == f":{module}"
                    and row.get("kernel_project") == family.get("project_path")
                    and row.get("kernel_directory") == family.get("project_dir"),
                    f"Yarn profile {minecraft} differs from its source-family table")
            require(int(family.get("java_release", -1)) == int(java),
                    f"Yarn profile {minecraft} has a mismatched Java target")
        elif minecraft in PRIMARY_VERSIONS:
            key = "primary1.21.1"
            require(module == "fabric-1211" and int(java) == 21,
                    f"Primary profile {minecraft} has the wrong module or Java target")
        else:
            matching = [key for key, versions in MODERN_VERSIONS.items() if minecraft in versions]
            require(len(matching) == 1 and module == "fabric-modern" and int(java) == 25,
                    f"No compatible modern source family for workflow profile {minecraft}")
            key = matching[0]
        covered.add(key)
    require(covered == set(families), "The 24 CI profiles do not cover all fourteen source families")

    files = {path: source_file(commit, path) for path in LICENSE_FILES}
    for path, content in files.items():
        require(bool(content), f"Required notice or license is empty: {path}")
    metadata = parse_json(files["third-party/baritone/dependencies.json"], "Baritone dependencies")
    require(metadata.get("schema_version") == 1, "Unsupported Baritone dependency metadata")
    return table, families, files


def validate_tar_archive(path: Path, source_root: str) -> dict[str, Any]:
    require(path.stat().st_size <= MAX_ARCHIVE_BYTES,
            f"Upstream archive exceeds {MAX_ARCHIVE_BYTES} bytes: {path.name}")
    member_count = 0
    expanded_size = 0
    seen: set[str] = set()
    found_file = False
    try:
        with tarfile.open(path, mode="r:gz") as archive:
            for member in archive:
                member_count += 1
                require(member_count <= MAX_TAR_MEMBERS,
                        f"Upstream archive has too many entries: {path.name}")
                name = member.name.rstrip("/")
                safe_path(name, f"{path.name} member")
                parts = PurePosixPath(name).parts
                require(parts[0] == source_root,
                        f"Upstream archive has unexpected root {member.name!r}")
                require(name not in seen, f"Upstream archive repeats path {name}")
                seen.add(name)
                if member.isfile():
                    found_file = True
                    expanded_size += member.size
                elif not (member.isdir() or member.issym() or member.islnk()):
                    raise SourcePackageError(f"Unsupported special tar entry {member.name!r}")
                require(expanded_size <= MAX_EXPANDED_BYTES,
                        f"Upstream archive expands beyond {MAX_EXPANDED_BYTES} bytes")

                if member.issym() or member.islnk():
                    link = PurePosixPath(member.linkname)
                    require(not link.is_absolute() and "\\" not in member.linkname,
                            f"Upstream archive has an unsafe link target at {member.name!r}")
                    base = () if member.islnk() else parts[:-1]
                    resolved: list[str] = list(base)
                    for part in link.parts:
                        if part in {"", "."}:
                            continue
                        if part == "..":
                            require(len(resolved) > 1,
                                    f"Upstream archive link escapes its source root at {member.name!r}")
                            resolved.pop()
                        else:
                            resolved.append(part)
                    require(bool(resolved) and resolved[0] == source_root,
                            f"Upstream archive link escapes its source root at {member.name!r}")
    except SourcePackageError:
        raise
    except (OSError, tarfile.TarError) as error:
        raise SourcePackageError(f"Cannot inspect upstream archive {path}: {error}") from error
    require(found_file, f"Upstream archive contains no regular source files: {path.name}")
    return {"memberCount": member_count, "expandedBytes": expanded_size}


def download_archive(pin: dict[str, Any], destination: Path) -> dict[str, Any]:
    lock = pin["lock"]
    url = lock["archive_url"]
    expected_hash = lock["archive_sha256"]
    timeout_at = time.monotonic() + 180
    request = urllib.request.Request(url, headers={"User-Agent": "lodekeeper-owned-source-bundle/1"})
    digest = hashlib.sha256()
    size = 0
    try:
        with urllib.request.urlopen(request, timeout=30) as response, destination.open("wb") as output:
            while chunk := response.read(COPY_BUFFER_SIZE):
                size += len(chunk)
                require(size <= MAX_ARCHIVE_BYTES,
                        f"Upstream download exceeds {MAX_ARCHIVE_BYTES} bytes: {lock['archive_filename']}")
                require(time.monotonic() <= timeout_at,
                        f"Upstream download exceeded its time bound: {lock['archive_filename']}")
                digest.update(chunk)
                output.write(chunk)
    except SourcePackageError:
        raise
    except OSError as error:
        raise SourcePackageError(f"Cannot download pinned upstream archive {url}: {error}") from error
    actual_hash = digest.hexdigest()
    require(actual_hash == expected_hash,
            f"Upstream archive SHA-256 mismatch for {lock['archive_filename']}: {actual_hash}")
    tar_info = validate_tar_archive(destination, lock["source_root"])
    return {
        "sha256": actual_hash,
        "bytes": size,
        "sourceCommit": lock["source_commit"],
        "sourceRoot": lock["source_root"],
        "tarValidation": tar_info,
    }


def stream_git_archive(bundle: zipfile.ZipFile, commit: str, member_name: str) -> dict[str, Any]:
    command = ["git", "archive", "--format=zip", f"--prefix=lodekeeper-{commit}/", commit]
    try:
        process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError as error:
        raise SourcePackageError(f"Cannot start exact Git source archive: {error}") from error
    require(process.stdout is not None, "git archive has no output stream")
    digest = hashlib.sha256()
    size = 0
    info = zipfile.ZipInfo(member_name)
    info.compress_type = zipfile.ZIP_STORED
    try:
        with bundle.open(info, mode="w", force_zip64=True) as output:
            while chunk := process.stdout.read(COPY_BUFFER_SIZE):
                output.write(chunk)
                digest.update(chunk)
                size += len(chunk)
        stderr = process.stderr.read() if process.stderr is not None else b""
        status = process.wait()
    except BaseException:
        process.kill()
        process.wait()
        raise
    require(status == 0, f"git archive failed: {stderr.decode('utf-8', errors='replace').strip()}")
    return {"sha256": digest.hexdigest(), "bytes": size, "sourceCommit": commit}


def add_bytes(bundle: zipfile.ZipFile, name: str, data: bytes, contents: dict[str, dict[str, Any]]) -> None:
    safe_path(name, "bundle")
    bundle.writestr(name, data)
    contents[name] = {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}


def package_sources(commit: str, output: Path, families: dict[str, Any],
                    source_files: dict[str, bytes]) -> dict[str, Any]:
    require(not output.exists(), f"Output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".part", dir=output.parent)
    os.close(descriptor)
    temporary_path = Path(temporary_name)
    contents: dict[str, dict[str, Any]] = {}
    try:
        with zipfile.ZipFile(temporary_path, mode="w", compression=zipfile.ZIP_STORED, allowZip64=True) as bundle:
            git_member = f"source/lodekeeper-{commit}.zip"
            git_receipt = stream_git_archive(bundle, commit, git_member)
            contents[git_member] = {"sha256": git_receipt["sha256"], "bytes": git_receipt["bytes"]}

            source_manifest_families = []
            with tempfile.TemporaryDirectory(prefix="lodekeeper-upstream-", dir=output.parent) as scratch_name:
                scratch = Path(scratch_name)
                for key, pin in sorted(families.items()):
                    lock = pin["lock"]
                    archive_path = scratch / lock["archive_filename"]
                    upstream_receipt = download_archive(pin, archive_path)
                    member_name = f"upstream/{key}/{lock['archive_filename']}"
                    safe_path(member_name, "upstream bundle")
                    bundle.write(archive_path, member_name)
                    contents[member_name] = {
                        "sha256": upstream_receipt["sha256"],
                        "bytes": upstream_receipt["bytes"],
                    }
                    source_manifest_families.append({
                        "family": key,
                        "projectDirectory": pin["project_dir"],
                        "archiveCacheDirectory": ".cache/baritone/sources"
                        if key == "primary1.21.1" else f"{pin['project_dir']}/build/sources",
                        "sourceCommit": lock["source_commit"],
                        "baritoneVersion": lock["baritone_version"],
                        "archive": member_name,
                        "archiveSha256": upstream_receipt["sha256"],
                        "archiveBytes": upstream_receipt["bytes"],
                        "sourceLock": pin["lock_file"],
                        "sourceLockSha256": hashlib.sha256(pin["lock_bytes"]).hexdigest(),
                        "lifecycleOverrideManifest": pin["override_file"],
                        "lifecycleOverrideManifestSha256": pin["override_sha256"],
                        "lifecycleOverrides": [
                            {"path": path, "sha256": digest}
                            for path, digest in sorted(pin["override_entries"].items())
                        ],
                        "minecraftVersions": lock.get("minecraft_versions", [lock.get("minecraft_version")]),
                        "javaVersion": lock.get("java_version", pin["java_release"]),
                    })
                    archive_path.unlink()

            for path, data in source_files.items():
                add_bytes(bundle, "licenses/" + PurePosixPath(path).name, data, contents)

            instructions = f"""# Rebuilding Lodekeeper from this source bundle

This bundle contains the exact Lodekeeper Git source snapshot {commit}, the hash-pinned Baritone source archives used by all fourteen owned-kernel families, their override manifests, and the upstream notices and licenses.

## Verify and unpack

1. Check each file listed in SHA256SUMS before use.
2. Unpack source/lodekeeper-{commit}.zip to obtain the exact application source and build scripts.
3. Extract a family archive from upstream/<family>/ when working without network access. The family lock in the source tree records its commit and expected SHA-256. source-manifest.json records the same values.
4. Keep licenses/NOTICE.md, licenses/COPYING, and licenses/COPYING.LESSER with redistributed copies of the corresponding source.

The project is MIT-licensed in LICENSE. Generated owned-kernel source is modified LGPL-3.0-or-later Baritone source; exact upstream commits and locally modified lifecycle files are listed in source-manifest.json. Preserve the upstream license notices when using or redistributing that portion.

## Build

Use the Java version recorded for the selected Minecraft profile. From the extracted source tree, run:

    ./scripts/build-version.sh <minecraft-version> --no-daemon ':<fabric-module>:check'

The workflow maps each exact Minecraft version to its adapter and source family. The build verifies source archive pins, regenerates the owned kernel, compiles the exact profile, and checks the final jar. Gradle dependencies may still need to be available through configured repositories or the local Gradle cache.

To seed an offline source cache, copy the matching archive from upstream/<family>/ to archiveCacheDirectory recorded for that family in source-manifest.json, then append archive_filename from source-lock.json. The fetch task rechecks the SHA-256 before extracting.

The release check distinguishes successful compilation from in-game behavior. Review docs/GAME-VERIFICATION.md for runtime verification status and limits.
"""
            add_bytes(bundle, "BUILDING.md", instructions.encode("utf-8"), contents)

            manifest = {
                "schemaVersion": 1,
                "sourceCommit": commit,
                "lodekeeperSourceArchive": {"file": git_member, **git_receipt},
                "sourceFamilies": source_manifest_families,
                "sourceFamilyCount": len(source_manifest_families),
                "licenseSummary": {
                    "application": "MIT",
                    "ownedKernel": "Modified Baritone source under LGPL-3.0-or-later",
                    "sourceChanges": "Lifecycle and integration overrides are identified by exact per-family SHA-256 manifests.",
                },
                "members": {name: contents[name] for name in sorted(contents)},
                "checksumPolicy": "SHA256SUMS lists every bundle member except SHA256SUMS itself.",
            }
            manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
            add_bytes(bundle, "source-manifest.json", manifest_bytes, contents)
            checksum_data = "".join(
                f"{metadata['sha256']}  {name}\n" for name, metadata in sorted(contents.items())
            ).encode("utf-8")
            bundle.writestr("SHA256SUMS", checksum_data)

        require(not output.exists(), f"Output appeared while packaging: {output}")
        os.link(temporary_path, output)
        temporary_path.unlink()
    finally:
        temporary_path.unlink(missing_ok=True)
    return {
        "output": str(output.resolve()),
        "sourceCommit": commit,
        "sourceFamilies": len(families),
        "bundleSha256": digest_file(output),
        "bundleBytes": output.stat().st_size,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source40", required=True,
                        help="Full Git commit whose exact source tree will be included")
    parser.add_argument("--output", type=Path, help="New ZIP output path")
    parser.add_argument("--validate-only", action="store_true",
                        help="Validate locks, override hashes, and profile coverage without downloading")
    args = parser.parse_args()
    try:
        table, families, source_files = validate_snapshot(args.source40)
        if args.validate_only:
            print(json.dumps({
                "sourceCommit": args.source40,
                "sourceFamilies": len(families),
                "profiles": 24,
                "upstreamArchives": len({row["lock"]["source_commit"] for row in families.values()}),
                "pinsAndOverridesValidated": True,
            }, sort_keys=True))
            return 0
        require(args.output is not None, "--output is required unless --validate-only is used")
        result = package_sources(args.source40, args.output, families, source_files)
    except (SourcePackageError, OSError, ValueError) as error:
        parser.exit(2, f"error: {error}\n")
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
