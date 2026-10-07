from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path

KERNEL_PACKAGE = "dev/lodekeeper/navigation/kernel/"
MIXIN_CONFIG = "mixins.lodekeeper-kernel.json"
SOURCE_LOCK_PATH = "META-INF/lodekeeper/owned-kernel/source-lock.json"
OVERRIDE_MANIFEST_PATH = "META-INF/lodekeeper/owned-kernel/lifecycle-overrides.sha256"
SOURCE_MANIFEST_PATH = "META-INF/lodekeeper/owned-kernel/source-manifest.json"
SOURCE_FAMILY_DIFF_MANIFEST_PATH = "META-INF/lodekeeper/owned-kernel/source-family-diff-manifest.json"
LICENSE_TEXT = "META-INF/licenses/BARITONE-LICENSE.txt"
LICENSE_IMAGE = "META-INF/licenses/BARITONE-LICENSE-Part-2.jpg"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def expected_classes(source_manifest: dict) -> set[str]:
    generated_files = source_manifest.get("generated_files")
    require(isinstance(generated_files, dict), "Source manifest has no generated file map")
    result = set()
    for source in generated_files:
        if not source.endswith(".java"):
            continue
        require("/java/" in source, f"Unexpected generated Java source path: {source}")
        result.add(source.split("/java/", 1)[1][:-5] + ".class")
    require(bool(result), "Source manifest contains no generated Java files")
    return result


def read_override_hashes(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        digest, separator, relative = line.partition("  ")
        require(separator != "" and len(digest) == 64, f"Invalid override hash entry: {line}")
        require(relative not in result, f"Duplicate override path: {relative}")
        result[relative] = digest
    require(bool(result), "Override manifest is empty")
    return result


def verify_provenance(
    jar: zipfile.ZipFile,
    name_set: set[str],
    source_manifest: dict,
    source_lock_path: Path,
    override_manifest_path: Path,
    source_family_diff_manifest_path: Path,
) -> None:
    for path in (SOURCE_LOCK_PATH, OVERRIDE_MANIFEST_PATH, SOURCE_MANIFEST_PATH, SOURCE_FAMILY_DIFF_MANIFEST_PATH):
        require(path in name_set, f"Jar is missing owned-kernel provenance: {path}")

    lock = json.loads(source_lock_path.read_text(encoding="utf-8"))
    packaged_lock = json.loads(jar.read(SOURCE_LOCK_PATH))
    require(packaged_lock == lock, "Packaged source lock differs from the repository source lock")
    require(
        jar.read(OVERRIDE_MANIFEST_PATH) == override_manifest_path.read_bytes(),
        "Packaged override hash manifest differs from the repository manifest",
    )
    packaged_manifest = json.loads(jar.read(SOURCE_MANIFEST_PATH))
    require(packaged_manifest == source_manifest, "Packaged source manifest differs from generated sources")
    packaged_diff = json.loads(jar.read(SOURCE_FAMILY_DIFF_MANIFEST_PATH))
    expected_diff = json.loads(source_family_diff_manifest_path.read_text(encoding="utf-8"))
    require(packaged_diff == expected_diff, "Packaged source-family diff manifest differs from the repository manifest")
    require(
        source_manifest.get("minecraft_api_family") == lock.get("minecraft_api_family")
        and source_manifest.get("minecraft_versions") == lock.get("minecraft_versions")
        and source_manifest.get("baritone_version") == lock.get("baritone_version")
        and source_manifest.get("baritone_source_commit") == lock.get("source_commit"),
        "Generated source manifest version pins differ from the source lock",
    )

    pins = source_manifest.get("source_pins", {})
    require(pins.get("archive_sha256") == lock.get("archive_sha256"), "Source archive pin differs")
    require(
        pins.get("dependency_metadata_sha256") == lock.get("dependency_metadata_sha256"),
        "Dependency metadata pin differs",
    )
    require(
        pins.get("source_family_diff_manifest_sha256") == sha256(source_family_diff_manifest_path),
        "Source-family diff manifest pin differs",
    )
    require(
        source_manifest.get("override_manifest_sha256") == sha256(override_manifest_path),
        "Override manifest hash differs from the generated source manifest",
    )
    require(
        source_manifest.get("canonical_override_hashes") == read_override_hashes(override_manifest_path),
        "Canonical override hashes differ from the generated source manifest",
    )


def inspect(args: argparse.Namespace) -> None:
    source_manifest = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    require(source_manifest.get("schema_version") == 1, "Unsupported generated source manifest")
    required_classes = expected_classes(source_manifest)

    with zipfile.ZipFile(args.jar) as jar:
        names = [entry.filename for entry in jar.infolist()]
        name_set = set(names)
        require(len(names) == len(name_set), "Jar contains duplicate ZIP entry paths")
        require(jar.testzip() is None, "Jar has a corrupt ZIP entry")
        require(not any(name.startswith("baritone/") for name in names), "Jar contains upstream Baritone packages")
        require(not any(name.endswith(".jar") for name in names), "Jar contains a nested jar")
        require(not any(name.startswith("META-INF/jars/") for name in names), "Jar contains nested mod jars")
        require(
            "dev/lodekeeper/navigation/kernel/BaritoneProvider.class" not in name_set,
            "Jar contains the upstream Baritone provider",
        )
        require(
            "dev/lodekeeper/navigation/kernel/api/IBaritoneProvider.class" not in name_set,
            "Jar contains the upstream Baritone provider API",
        )
        require(
            "dev/lodekeeper/navigation/kernel/api/BaritoneAPI.class" not in name_set,
            "Jar contains the upstream Baritone API entrypoint",
        )
        require("lodekeeper-baritone.mixins.json" not in name_set, "Legacy Baritone bridge config remains packaged")
        require(
            "dev/lodekeeper/fabric/modern/mixin/BaritoneMiningAccessor.class" not in name_set,
            "Legacy Baritone mining bridge remains packaged",
        )

        missing = sorted(name for name in required_classes if name not in name_set)
        require(not missing, "Jar is missing generated kernel classes: " + ", ".join(missing[:8]))
        require(MIXIN_CONFIG in name_set, "Jar is missing the owned kernel mixin config")
        config = json.loads(jar.read(MIXIN_CONFIG))
        require(
            config.get("package") == "dev.lodekeeper.navigation.kernel.launch.mixins",
            "Kernel mixin config package is incorrect",
        )
        client_mixins = config.get("client")
        require(isinstance(client_mixins, list) and client_mixins, "Kernel client mixin list is empty or invalid")
        for mixin in client_mixins:
            entry = KERNEL_PACKAGE + "launch/mixins/" + mixin.replace(".", "/") + ".class"
            require(entry in name_set, f"Kernel mixin class is missing: {entry}")
        require(LICENSE_TEXT in name_set, "Jar is missing the upstream LGPL text notice")
        require(LICENSE_IMAGE in name_set, "Jar is missing the upstream license image")
        license_text = jar.read(LICENSE_TEXT).decode("utf-8", errors="replace")
        require("GNU LESSER GENERAL PUBLIC LICENSE" in license_text, "Packaged LGPL text is not recognizable")
        verify_provenance(
            jar,
            name_set,
            source_manifest,
            args.source_lock,
            args.override_manifest,
            args.source_family_diff_manifest,
        )

        if args.host_jar:
            require("fabric.mod.json" in name_set, "Host jar is missing fabric.mod.json")
            mod = json.loads(jar.read("fabric.mod.json"))
            mixins = mod.get("mixins", [])
            require(isinstance(mixins, list), "Host fabric.mod.json mixins must be a list")
            require(MIXIN_CONFIG in mixins, "Host fabric.mod.json does not register the owned kernel mixin config")
            require(
                "lodekeeper-baritone.mixins.json" not in mixins,
                "Host fabric.mod.json still registers the legacy Baritone bridge config",
            )
        else:
            require("fabric.mod.json" not in name_set, "Kernel library jar contains separate mod metadata")

    label = "host" if args.host_jar else "kernel library"
    print(f"PASS {label} jar includes all {len(required_classes)} generated classes and {len(client_mixins)} owned mixins")
    print("PASS no Baritone packages, provider, bridge, nested jars, or separate kernel mod metadata")
    print("PASS pinned source provenance and both upstream LGPL notices are present")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--source-lock", type=Path, required=True)
    parser.add_argument("--override-manifest", type=Path, required=True)
    parser.add_argument("--source-family-diff-manifest", type=Path, required=True)
    parser.add_argument("--host-jar", action="store_true")
    inspect(parser.parse_args())


if __name__ == "__main__":
    main()
