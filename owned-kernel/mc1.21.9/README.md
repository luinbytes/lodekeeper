# Owned kernel source candidate for Minecraft 1.21.9

This candidate pins Baritone 1.16.0 at `5bf45429eeb17020e90c1ae316f8bac4c62477d8` for Minecraft 1.21.9, 1.21.10. It uses Java 21 and official Mojang mappings. `source-lock.json` records the source archive, release jar, and dependency metadata hashes.

Run the source generator from this directory:

```sh
python3 scripts/fetch_pinned_source.py --lock source-lock.json --archive /Users/luinbytes/src/lodekeeper/.cache/baritone/sources/baritone-1.16.0-5bf45429eeb17020e90c1ae316f8bac4c62477d8.tar.gz --source-dir build/upstream/baritone-5bf45429eeb17020e90c1ae316f8bac4c62477d8
python3 prepare_sources.py --source-root build/upstream/baritone-5bf45429eeb17020e90c1ae316f8bac4c62477d8 --source-archive /Users/luinbytes/src/lodekeeper/.cache/baritone/sources/baritone-1.16.0-5bf45429eeb17020e90c1ae316f8bac4c62477d8.tar.gz
```

The generator verifies the exact release metadata, every mapped upstream source hash, all 50 family override hashes, and the family port manifest. It writes the owned source tree and `source-manifest.json` under `build/generated/owned-kernel/`.

The source candidate has no Gradle module file. The root build owns Java 21 and host remap wiring. Source preparation passed. Java compilation and game runtime checks have not run for this candidate.
