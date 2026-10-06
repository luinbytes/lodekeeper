#!/usr/bin/env python3
"""Fetch or validate the exact Fabric Baritone artifact for a Minecraft profile."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
METADATA_PATH = ROOT / "third-party" / "baritone" / "dependencies.json"
CACHE_DIR = ROOT / ".cache" / "baritone"
CONNECT_READ_TIMEOUT_SECONDS = 20
TOTAL_DOWNLOAD_TIMEOUT_SECONDS = 120
MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_NESTED_JAR_BYTES = 32 * 1024 * 1024
EXPECTED_MAJOR_BY_JAVA = {17: 61, 21: 65, 25: 69}
VERSION_PARTS = re.compile(r"^[0-9]+(?:\.[0-9]+)*$")
CONSTRAINT_PART = re.compile(r"^(>=|<=|>|<|=)?([0-9]+(?:\.[0-9]+)*)$")


class ArtifactError(Exception):
    pass


def load_profile(minecraft_version: str) -> tuple[dict[str, Any], dict[str, Any]]:
    try:
        metadata = json.loads(METADATA_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ArtifactError(f"Cannot read Baritone metadata at {METADATA_PATH}: {error}") from error
    if metadata.get("schema_version") != 1:
        raise ArtifactError("Unsupported Baritone dependency metadata schema")

    releases = metadata.get("releases")
    profiles = metadata.get("minecraft_profiles")
    if not isinstance(releases, list) or not isinstance(profiles, dict):
        raise ArtifactError("Baritone metadata must define releases and exact Minecraft profiles")
    version = profiles.get(minecraft_version)
    if version is None:
        known = ", ".join(profiles)
        raise ArtifactError(
            f"No Baritone profile for Minecraft {minecraft_version}. Supported exact profiles: {known}"
        )

    matches = [release for release in releases if release.get("version") == version]
    if len(matches) != 1:
        raise ArtifactError(f"Expected one metadata entry for Baritone {version}, found {len(matches)}")
    release = matches[0]
    if minecraft_version not in release.get("minecraft_versions", []):
        raise ArtifactError(
            f"Metadata maps Minecraft {minecraft_version} to Baritone {version}, "
            "but the release profile does not list that Minecraft version"
        )
    if EXPECTED_MAJOR_BY_JAVA.get(release.get("java_version")) != release.get("class_major"):
        raise ArtifactError(f"Invalid Java class-major metadata for Baritone {version}")
    if not re.fullmatch(r"[0-9a-f]{64}", str(release.get("sha256", ""))):
        raise ArtifactError(f"Invalid SHA-256 metadata for Baritone {version}")
    if not re.fullmatch(r"[0-9a-f]{40}", str(release.get("source_commit", ""))):
        raise ArtifactError(f"Invalid source commit metadata for Baritone {version}")

    for release_entry in releases:
        release_version = release_entry.get("version")
        actual_profiles = {
            profile for profile, mapped_version in profiles.items() if mapped_version == release_version
        }
        if actual_profiles != set(release_entry.get("minecraft_versions", [])):
            raise ArtifactError(f"Profile map does not exactly match Baritone {release_version} metadata")

    return metadata, release


def version_tuple(value: str) -> tuple[int, ...]:
    if not VERSION_PARTS.fullmatch(value):
        raise ArtifactError(f"Unsupported Minecraft version selector: {value}")
    return tuple(int(part) for part in value.split("."))


def compare_versions(left: str, right: str) -> int:
    left_parts = version_tuple(left)
    right_parts = version_tuple(right)
    width = max(len(left_parts), len(right_parts))
    left_parts += (0,) * (width - len(left_parts))
    right_parts += (0,) * (width - len(right_parts))
    return (left_parts > right_parts) - (left_parts < right_parts)


def selector_supports(selector: str, minecraft_version: str) -> bool:
    if selector == minecraft_version:
        return True

    if selector.endswith(".*"):
        prefix = selector[:-2]
        return minecraft_version == prefix or minecraft_version.startswith(prefix + ".")

    if selector.startswith("~"):
        base = selector[1:]
        base_parts = version_tuple(base)
        target_parts = version_tuple(minecraft_version)
        return target_parts[: len(base_parts)] == base_parts

    pieces = selector.split()
    for piece in pieces:
        match = CONSTRAINT_PART.fullmatch(piece)
        if match is None:
            raise ArtifactError(f"Unsupported Minecraft version selector: {selector}")
        operator, bound = match.groups()
        comparison = compare_versions(minecraft_version, bound)
        if operator in (None, "=") and comparison != 0:
            return False
        if operator == ">" and comparison <= 0:
            return False
        if operator == ">=" and comparison < 0:
            return False
        if operator == "<" and comparison >= 0:
            return False
        if operator == "<=" and comparison > 0:
            return False
    return True


def manifest_supports(manifest: dict[str, Any], minecraft_version: str) -> str:
    depends = manifest.get("depends")
    constraint = depends.get("minecraft") if isinstance(depends, dict) else None
    selectors = constraint if isinstance(constraint, list) else [constraint]
    if not selectors or any(not isinstance(selector, str) for selector in selectors):
        raise ArtifactError("Fabric manifest does not declare a string Minecraft dependency")
    if not any(selector_supports(selector, minecraft_version) for selector in selectors):
        raise ArtifactError(
            f"Baritone manifest Minecraft dependency {constraint!r} does not support "
            f"Minecraft {minecraft_version}"
        )
    return json.dumps(constraint, separators=(",", ":"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_archive(
    path: Path,
    release: dict[str, Any],
    minecraft_version: str,
    require_pinned_version: bool,
) -> dict[str, Any]:
    try:
        with zipfile.ZipFile(path) as archive:
            entries = {entry.filename: entry for entry in archive.infolist()}
            manifest_entry = entries.get("fabric.mod.json")
            if manifest_entry is None or manifest_entry.file_size > MAX_MANIFEST_BYTES:
                raise ArtifactError("Artifact has no small root fabric.mod.json")
            manifest = json.loads(archive.read(manifest_entry).decode("utf-8"))

            if manifest.get("id") != "baritone":
                raise ArtifactError(f"Fabric manifest id is {manifest.get('id')!r}, expected 'baritone'")
            manifest_version = manifest.get("version")
            if not isinstance(manifest_version, str) or not manifest_version:
                raise ArtifactError("Fabric manifest has no Baritone version")
            if require_pinned_version and manifest_version != release["version"]:
                raise ArtifactError(
                    f"Fabric manifest version is {manifest_version}, expected {release['version']}"
                )
            constraint = manifest_supports(manifest, minecraft_version)

            class_majors: set[int] = set()
            class_count = 0
            for filename, entry in entries.items():
                if not filename.endswith(".class"):
                    continue
                with archive.open(entry) as class_file:
                    header = class_file.read(8)
                if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe":
                    raise ArtifactError(f"Invalid Java class header in {filename}")
                class_count += 1
                class_majors.add(int.from_bytes(header[6:8], "big"))

            expected_major = release["class_major"]
            if class_count == 0 or expected_major not in class_majors:
                raise ArtifactError(
                    f"Artifact class majors are {sorted(class_majors)}, expected Java {release['java_version']} "
                    f"class major {expected_major}"
                )
            if any(major > expected_major for major in class_majors):
                raise ArtifactError(
                    f"Artifact contains class majors above expected {expected_major}: {sorted(class_majors)}"
                )

            nested_paths: list[str] = []
            jars = manifest.get("jars", [])
            if not isinstance(jars, list):
                raise ArtifactError("Fabric manifest jars entry must be a list")
            for nested in jars:
                nested_path = nested.get("file") if isinstance(nested, dict) else None
                nested_entry = entries.get(nested_path) if isinstance(nested_path, str) else None
                if nested_entry is None or nested_entry.file_size > MAX_NESTED_JAR_BYTES:
                    raise ArtifactError(f"Fabric manifest declares missing or oversized nested jar {nested_path!r}")
                try:
                    with zipfile.ZipFile(archive.open(nested_entry)) as nested_archive:
                        nested_archive.infolist()
                except (OSError, zipfile.BadZipFile) as error:
                    raise ArtifactError(f"Invalid nested jar {nested_path}: {error}") from error
                nested_paths.append(nested_path)

            expected_nested = set(release.get("nested_libraries", []))
            actual_nested = {
                Path(path).name.removesuffix(".jar") for path in nested_paths
            }
            if require_pinned_version and expected_nested != actual_nested:
                raise ArtifactError(
                    f"Nested libraries are {sorted(actual_nested)}, expected {sorted(expected_nested)}"
                )
    except ArtifactError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        raise ArtifactError(f"Cannot inspect Baritone jar {path}: {error}") from error

    return {
        "path": str(path.resolve()),
        "minecraft_version": minecraft_version,
        "baritone_version": manifest_version,
        "minecraft_dependency": constraint,
        "class_majors": sorted(class_majors),
        "nested_jars": nested_paths,
        "sha256": sha256_file(path),
    }


def fetch_pinned_artifact(
    url: str,
    output: Path,
    release: dict[str, Any],
    minecraft_version: str,
) -> tuple[dict[str, Any], str]:
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".part", dir=output.parent
    )
    temporary_path = Path(temporary_name)
    deadline = time.monotonic() + TOTAL_DOWNLOAD_TIMEOUT_SECONDS
    byte_count = 0
    digest = hashlib.sha256()

    try:
        request = urllib.request.Request(url, headers={"User-Agent": "lodekeeper-baritone-fetch/1"})
        with os.fdopen(descriptor, "wb") as destination:
            try:
                response = urllib.request.urlopen(request, timeout=CONNECT_READ_TIMEOUT_SECONDS)
            except (urllib.error.URLError, TimeoutError, socket.timeout) as error:
                raise ArtifactError(f"Could not fetch pinned Baritone artifact: {error}") from error
            with response:
                content_length = response.headers.get("Content-Length")
                if content_length is not None and int(content_length) > MAX_DOWNLOAD_BYTES:
                    raise ArtifactError(f"Baritone artifact exceeds {MAX_DOWNLOAD_BYTES} bytes")
                while True:
                    if time.monotonic() > deadline:
                        raise ArtifactError(
                            f"Baritone download exceeded {TOTAL_DOWNLOAD_TIMEOUT_SECONDS} seconds"
                        )
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    byte_count += len(chunk)
                    if byte_count > MAX_DOWNLOAD_BYTES:
                        raise ArtifactError(f"Baritone artifact exceeds {MAX_DOWNLOAD_BYTES} bytes")
                    digest.update(chunk)
                    destination.write(chunk)
            destination.flush()
            os.fsync(destination.fileno())

        actual_digest = digest.hexdigest()
        if actual_digest != release["sha256"]:
            raise ArtifactError(
                f"SHA-256 mismatch for Baritone {release['version']}: "
                f"expected {release['sha256']}, received {actual_digest}"
            )
        report = inspect_archive(temporary_path, release, minecraft_version, True)
        os.replace(temporary_path, output)
        report["path"] = str(output.resolve())
        return report, "downloaded"
    finally:
        if temporary_path.exists():
            temporary_path.unlink()


def copy_replacement(
    source: Path,
    output: Path,
    release: dict[str, Any],
    minecraft_version: str,
) -> dict[str, Any]:
    if not source.is_file():
        raise ArtifactError(f"Replacement Baritone jar is not a regular file: {source}")
    report = inspect_archive(source, release, minecraft_version, False)
    output.parent.mkdir(parents=True, exist_ok=True)
    if source.resolve() == output.resolve():
        report["path"] = str(output.resolve())
        report["action"] = "verified-replacement"
        report["expected_sha256"] = None
        return report

    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".part", dir=output.parent
    )
    temporary_path = Path(temporary_name)
    try:
        with source.open("rb") as original, os.fdopen(descriptor, "wb") as destination:
            shutil.copyfileobj(original, destination, length=1024 * 1024)
            destination.flush()
            os.fsync(destination.fileno())
        if sha256_file(temporary_path) != report["sha256"]:
            raise ArtifactError("Replacement Baritone jar changed while it was copied")
        inspect_archive(temporary_path, release, minecraft_version, False)
        os.replace(temporary_path, output)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()

    report["path"] = str(output.resolve())
    report["action"] = "verified-replacement"
    report["expected_sha256"] = None
    return report


def run() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-version", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-jar", type=Path)
    parser.add_argument("--replacement-jar", type=Path)
    args = parser.parse_args()

    try:
        if args.verify_jar is not None and (args.output is not None or args.replacement_jar is not None):
            raise ArtifactError("--verify-jar cannot be combined with --output or --replacement-jar")
        if args.replacement_jar is not None and args.output is None:
            raise ArtifactError("--replacement-jar requires --output")

        metadata, release = load_profile(args.minecraft_version)
        if args.verify_jar is None and args.replacement_jar is None:
            output = args.output or CACHE_DIR / f"baritone-api-fabric-{release['version']}.jar"
            if output.exists() and not output.is_file():
                raise ArtifactError(f"Baritone output path is not a regular file: {output}")
            if output.is_file():
                try:
                    existing_digest = sha256_file(output)
                    if existing_digest == release["sha256"]:
                        report = inspect_archive(output, release, args.minecraft_version, True)
                        report["action"] = "cache-hit"
                        report["expected_sha256"] = release["sha256"]
                        print(json.dumps(report, sort_keys=True))
                        return 0
                except ArtifactError:
                    pass

            artifact_url = metadata["upstream"]["release_url_template"].format(
                version=release["version"]
            )
            report, action = fetch_pinned_artifact(
                artifact_url, output, release, args.minecraft_version
            )
            report["action"] = action
            report["expected_sha256"] = release["sha256"]
        elif args.replacement_jar is not None:
            report = copy_replacement(
                args.replacement_jar, args.output, release, args.minecraft_version
            )
        else:
            report = inspect_archive(args.verify_jar, release, args.minecraft_version, False)
            report["action"] = "verified-replacement"
            report["expected_sha256"] = None

        print(json.dumps(report, sort_keys=True))
        return 0
    except (ArtifactError, KeyError, OSError, TypeError, ValueError, urllib.error.URLError) as error:
        print(f"fetch-baritone: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(run())
