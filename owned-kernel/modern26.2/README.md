# Owned kernel candidate for Minecraft 26.2

This source candidate pins Baritone 1.19.0 at `9fadf7cf95293d7f6678981e77a939b4a7978373` for Minecraft 26.2. It uses Java 25 and official Mojang mappings. The repository Baritone dependency metadata hash is `f890ff89c3446ad6610700094d810ed2701a9ef5252e8b3e662ddb7988d68b45`.

`prepare_sources.py` verifies the source archive, metadata release, family diff manifest, and all 51 lifecycle override hashes before generating the owned source groups. `source-family-diff-manifest.json` records the pinned 26.3 baseline and the upstream and override hashes for every ported file.

The generated source is a library stage. It has no Baritone provider, standalone mod entrypoint, command manager, or eager bootstrap. Source preparation passes. Java compilation and game runtime checks have not been run for this candidate.
