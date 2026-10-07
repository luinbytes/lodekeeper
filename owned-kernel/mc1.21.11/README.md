# Owned kernel source candidate for Minecraft 1.21.11

This candidate pins Baritone 1.17.0 at `23723891da460ef15797b02fe5b385b0c5b163cc` for Minecraft 1.21.11. It uses Java 21 and official Mojang mappings. `source-lock.json` records the source archive, release jar, and dependency metadata hashes.

Run the source generator from this directory:

```sh
python3 scripts/fetch_pinned_source.py --lock source-lock.json --archive /Users/luinbytes/src/lodekeeper/.cache/baritone/sources/baritone-1.17.0-23723891da460ef15797b02fe5b385b0c5b163cc.tar.gz --source-dir build/upstream/baritone-23723891da460ef15797b02fe5b385b0c5b163cc
python3 prepare_sources.py --source-root build/upstream/baritone-23723891da460ef15797b02fe5b385b0c5b163cc --source-archive /Users/luinbytes/src/lodekeeper/.cache/baritone/sources/baritone-1.17.0-23723891da460ef15797b02fe5b385b0c5b163cc.tar.gz
```

The generator verifies the exact release metadata, every mapped upstream source hash, all 50 family override hashes, and the family port manifest. It writes the owned source tree and `source-manifest.json` under `build/generated/owned-kernel/`.

The source candidate has no Gradle module file. The root build owns Java 21 and host remap wiring. Source preparation passed. Java compilation and game runtime checks have not run for this candidate.
