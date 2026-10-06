#!/usr/bin/env python3
"""Package the pinned upstream sources alongside a Lodekeeper release."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tarfile
import tempfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024


def source_receipt(repository, commit, url, destination):
    if destination.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("Source archive exceeds its size bound")
    data = destination.read_bytes()
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        members = archive.getmembers()
        prefix = repository.rsplit("/", 1)[1] + "-" + commit + "/"
        if not members or not all(member.name == prefix[:-1] or member.name.startswith(prefix) for member in members):
            raise ValueError("Unexpected source archive root")
        if not any(member.isfile() for member in members):
            raise ValueError("Source archive contains no files")
    return {"repository": repository, "commit": commit, "url": url,
            "file": destination.name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}


def fetch_source(repository, commit, destination):
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+", repository):
        raise ValueError("Invalid source repository")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Source must use a full commit ID")
    url = f"https://codeload.github.com/{repository}/tar.gz/{commit}"
    if destination.is_file():
        return source_receipt(repository, commit, url, destination)
    deadline = time.monotonic() + 120
    request = urllib.request.Request(url, headers={"User-Agent": "lodekeeper-source-package/1"})
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=destination.parent, suffix=".part")
    try:
        with os.fdopen(descriptor, "wb") as output:
            with urllib.request.urlopen(request, timeout=20) as response:
                size = 0
                while data := response.read(256 * 1024):
                    size += len(data)
                    if size > MAX_ARCHIVE_BYTES or time.monotonic() > deadline:
                        raise ValueError("Source download exceeded its size or time bound")
                    output.write(data)
        receipt = source_receipt(repository, commit, url, Path(temporary))
        os.replace(temporary, destination)
        receipt["file"] = destination.name
        return receipt
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    metadata_path = ROOT / "third-party/baritone/dependencies.json"
    metadata = json.loads(metadata_path.read_text())
    cache = ROOT / ".cache/baritone/sources"
    sources = []
    for release in metadata["releases"]:
        name = f"baritone-{release['version']}-{release['source_commit']}.tar.gz"
        receipt = fetch_source("cabaletta/baritone", release["source_commit"], cache / name)
        receipt["baritoneVersion"] = release["version"]
        receipt["binarySha256"] = release["sha256"]
        sources.append(receipt)
        print(f"Fetched Baritone {release['version']} source", flush=True)

    # These are the exact nested native-library tags used by the pinned binaries.
    for version, commit in [("1.4.1", "b3fcce3e9fcadc0ae00a2a87494746606e68c95b"),
                            ("1.6", "1f3daf8e4e7058144eaecac7dc45f95bd15a3d21")]:
        name = f"nether-pathfinder-{version}-{commit}.tar.gz"
        receipt = fetch_source("babbaj/nether-pathfinder", commit, cache / name)
        receipt["netherPathfinderVersion"] = version
        receipt["licenseStatus"] = "no upstream license declaration found; see NOTICE.md"
        sources.append(receipt)

    submodules = [
        ("b3fcce3e9fcadc0ae00a2a87494746606e68c95b", "abseil-cpp", "abseil/abseil-cpp", "8f92175783c9685045c50f227e7c10f1cddb4d58"),
        ("1f3daf8e4e7058144eaecac7dc45f95bd15a3d21", "abseil-cpp", "abseil/abseil-cpp", "049aa40e7ec9e37ed47c4dd2452affb13cd62ebe"),
        ("1f3daf8e4e7058144eaecac7dc45f95bd15a3d21", "zlib-ng", "zlib-ng/zlib-ng", "cf89cf35037f152ce7adfeca864656de5d33ea1e"),
    ]
    for parent_commit, path, repository, commit in submodules:
        receipt = fetch_source(repository, commit, cache / f"{path}-{commit}.tar.gz")
        receipt["parentRepository"] = "babbaj/nether-pathfinder"
        receipt["parentCommit"] = parent_commit
        receipt["submodulePath"] = path
        sources.append(receipt)
    manifest = {"schemaVersion": 1, "baritoneSourcesModified": False, "sources": sources,
                "nativeLibrarySubmodules": "Exact pinned archives are included; place each at its parent's submodulePath when building."}
    options.output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=options.output.parent, suffix=".part")
    os.close(descriptor)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as bundle:
            for receipt in sources:
                bundle.write(cache / receipt["file"], "sources/" + receipt["file"])
            bundle.writestr("source-manifest.json", json.dumps(manifest, indent=2) + "\n")
            bundle.writestr("BUILDING.md", "# Rebuilding the bundled dependencies\n\n"
                "Extract the source archive for the matching version in source-manifest.json. "
                "For nether-pathfinder, extract the listed submodule archives into each submodulePath. "
                "Keep upstream build scripts and licenses with their sources. Follow each upstream README "
                "and build instructions; native builds require the platform C++ toolchain.\n\n"
                "Build the matching Baritone Fabric API jar, then build the matching Lodekeeper profile "
                "with -Pbaritone_jar=/absolute/path/to/baritone-api-fabric.jar. "
                "The included Gradle helper and fetch script validate and package the replacement. "
                "Lodekeeper source is available at https://github.com/luinbytes/lodekeeper.\n")
            for relative in ["third-party/baritone/dependencies.json", "third-party/baritone/NOTICE.md",
                             "third-party/baritone/licenses/COPYING", "third-party/baritone/licenses/COPYING.LESSER",
                             "gradle/baritone.gradle", "scripts/fetch-baritone.py", "scripts/package-baritone-sources.py"]:
                bundle.write(ROOT / relative, relative)
        os.replace(temporary, options.output)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print(json.dumps({"output": str(options.output.resolve()), "sourceArchives": len(sources),
                      "sha256": hashlib.sha256(options.output.read_bytes()).hexdigest()}), flush=True)


if __name__ == "__main__":
    main()
