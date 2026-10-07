# Owned kernel source inputs for Minecraft 1.21.2 and 1.21.3

Both Minecraft versions map to Baritone 1.12.0 at the commit in `source-lock.json`. The lock records 1.21.2 as its representative `minecraft_version` and lists both versions in `minecraft_versions`. The host build must still resolve the exact Minecraft version for each artifact.

`scripts/fetch_pinned_source.py` verifies and extracts the locked archive. Run `prepare_sources.py --source-root <source-root>` to relocate upstream packages, generate the owned mixin configuration, and apply the 51 lifecycle overrides. `lifecycle-overrides.sha256` records their exact content. `drift-notes.md` lists upstream files that stayed byte-identical and files adapted for this pin.

This directory contains source inputs only. It does not establish compilation or runtime support.
