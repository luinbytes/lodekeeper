# Release and source packaging

Run release packaging only after the native CI gates have passed for the exact source commit. The release inspector binds each input to the 24-profile workflow, successful jobs, receipt artifact IDs, workflow run and head SHA, and each original artifact ZIP digest. It checks the extracted ZIP contents, JAR metadata, Java class versions, Java 17 core and navigation classes, owned-kernel source locks and lifecycle override hashes, generated source inventory, mixin classes, Yarn refmaps where applicable, and absence of Baritone runtime packages, providers, nested jars, and legacy bridge files. Verification fixtures must be absent from every artifact.

The inspected release contains 24 JARs, `SHA256SUMS`, and `build-evidence.json`. It refuses an existing output directory and publishes the complete staging directory atomically. An exact source ZIP can be checked against the same Git commit with `--lodekeeper-source-zip`.

```sh
python3 scripts/package-release.py \
  <artifact-root> <source40> <ci-run> <new-output-directory> \
  <receipt.json> 0.1.0-preview.11 \
  --lodekeeper-source-zip <lodekeeper-source.zip>
```

`source40` must be the full 40-character commit used by CI. The receipt is the JSON record downloaded for that workflow run. The artifact root contains the extracted artifact directories and the original ZIP for each profile.

## Source bundle

The source bundle contains the exact Lodekeeper Git archive for `source40`, the fourteen hash-pinned Baritone source archives used by the owned-kernel families, the family locks and override hashes in the source tree, and the exact notices and licenses. It writes a `source-manifest.json`, `SHA256SUMS`, and `BUILDING.md` alongside those sources. Downloads are streamed one archive at a time, each tarball is SHA-256 checked and inspected for unsafe paths and links, and temporary downloads are removed after they are copied into the bundle. The bundle itself is written to a temporary output and published atomically.

Validate the exact Git snapshot and all lock and override hashes without network access:

```sh
python3 scripts/package-baritone-sources.py --source40 <source40> --validate-only
```

Build a source bundle after the source snapshot has been committed:

```sh
python3 scripts/package-baritone-sources.py \
  --source40 <source40> --output <new-source-bundle.zip>
```

The bundle identifies Lodekeeper's application and adapters as MIT-licensed and the modified owned-kernel portion as LGPL-3.0-or-later Baritone source. Preserve the embedded upstream notices and license files when redistributing the modified kernel. Do not add unlicensed dependency archives to the source bundle.
