from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

KERNEL_PACKAGE = "dev/lodekeeper/navigation/kernel/"
MIXIN_CONFIG = "mixins.lodekeeper-kernel.json"
LICENSE_TEXT = "META-INF/licenses/BARITONE-LICENSE.txt"
LICENSE_IMAGE = "META-INF/licenses/BARITONE-LICENSE-Part-2.jpg"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def expected_classes(source_manifest: dict) -> set[str]:
    result = set()
    for source in source_manifest["generated_files"]:
        if not source.endswith(".java"):
            continue
        require("/java/" in source, f"Unexpected generated Java source path: {source}")
        relative = source.split("/java/", 1)[1][:-5] + ".class"
        result.add(relative)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--namespace", choices=("intermediary", "named"), required=True)
    parser.add_argument("--host-jar", action="store_true")
    args = parser.parse_args()

    source_manifest = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    require(source_manifest.get("schema_version") == 1, "Unsupported generated source manifest")
    required_classes = expected_classes(source_manifest)

    with zipfile.ZipFile(args.jar) as jar:
        names = [entry.filename for entry in jar.infolist()]
        name_set = set(names)
        require(len(names) == len(name_set), "Jar contains duplicate ZIP entry paths")
        require(jar.testzip() is None, "Jar has a corrupt ZIP entry")
        require("META-INF/MANIFEST.MF" in name_set, "Jar is missing META-INF/MANIFEST.MF")
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
        require(
            f"Fabric-Mapping-Namespace: {args.namespace}" in manifest,
            f"Jar mapping namespace does not equal {args.namespace}",
        )

        if args.host_jar:
            require("fabric.mod.json" in name_set, "Host jar is missing fabric.mod.json")
            mod = json.loads(jar.read("fabric.mod.json"))
            mixins = mod.get("mixins", [])
            require(isinstance(mixins, list), "Host fabric.mod.json mixins must be a list")
            require(MIXIN_CONFIG in mixins, "Host fabric.mod.json does not register the kernel mixin config")
            require("lodekeeper-baritone.mixins.json" not in mixins,
                    "Host fabric.mod.json still registers the legacy Baritone bridge config")
        else:
            require("fabric.mod.json" not in name_set, "Kernel library jar must not contain fabric.mod.json")

        require(not any(name.endswith(".jar") for name in names), "Jar contains a nested jar")
        require(not any(name.startswith("baritone/") for name in names), "Jar contains upstream Baritone packages")
        require("lodekeeper-baritone.mixins.json" not in name_set, "Legacy Baritone bridge config remains packaged")
        require("dev/lodekeeper/fabric/mixin/BaritoneMiningAccessor.class" not in name_set,
                "Legacy Baritone accessor remains packaged")

        missing = sorted(name for name in required_classes if name not in name_set)
        require(not missing, "Jar is missing generated kernel classes: " + ", ".join(missing[:8]))
        require(MIXIN_CONFIG in name_set, "Jar is missing the owned kernel mixin config")
        config = json.loads(jar.read(MIXIN_CONFIG))
        require(config.get("package") == "dev.lodekeeper.navigation.kernel.launch.mixins",
                "Kernel mixin config package is incorrect")
        refmap = config.get("refmap")
        require(isinstance(refmap, str) and refmap in name_set, "Kernel mixin refmap is missing")
        json.loads(jar.read(refmap))
        for mixin in config.get("client", []):
            entry = "dev/lodekeeper/navigation/kernel/launch/mixins/" + mixin.replace(".", "/") + ".class"
            require(entry in name_set, f"Kernel mixin class is missing: {entry}")
        require(LICENSE_TEXT in name_set, "Jar is missing the upstream LGPL text notice")
        license_text = jar.read(LICENSE_TEXT).decode("utf-8", errors="replace")
        require("GNU LESSER GENERAL PUBLIC LICENSE" in license_text,
                "Packaged Baritone license text is not recognizable")
        require(LICENSE_IMAGE in name_set, "Jar is missing the upstream license image")
        if args.host_jar:
            required_provenance = {
                "META-INF/lodekeeper/owned-kernel/source-lock.json",
                "META-INF/lodekeeper/owned-kernel/lifecycle-overrides.sha256",
                "META-INF/lodekeeper/owned-kernel/source-manifest.json",
            }
            missing_provenance = sorted(required_provenance - name_set)
            require(not missing_provenance,
                    "Host jar is missing owned-kernel provenance: " + ", ".join(missing_provenance))
            packaged_manifest = json.loads(
                jar.read("META-INF/lodekeeper/owned-kernel/source-manifest.json")
            )
            require(packaged_manifest == source_manifest,
                    "Packaged owned-kernel source manifest differs from the generated manifest")

    label = "host" if args.host_jar else "kernel library"
    print(f"PASS {label} jar namespace={args.namespace}, classes={len(required_classes)}, mixins and LGPL notices present")


if __name__ == "__main__":
    main()
