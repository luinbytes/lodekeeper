# Preview 11 development checkpoint

This branch contains an unfinished source-owned navigation migration. It is not a release candidate. The published preview 10 remains the user-test baseline.

The 1.21.1 and 26.3 integrations flatten relocated kernel classes into Lodekeeper. Artifact inspection rejects a separate Baritone manifest, nested jars, upstream package paths, and the old bridge. Upstream LGPL notices and pinned source hashes remain packaged.

The 1.21.1 and 26.3 nearby-wood fixtures passed in isolated clients with production development outputs removed from the classpath. Those results cover one prepared fixture on each version. They do not establish a fresh-world diamond-kit pass, general performance superiority, or complete mechanic coverage.

| Frozen working-tree artifact | SHA-256 | Result |
| --- | --- | --- |
| 1.21.1 target retention | `9906be741e1b01333e1737c3f27169460a8578a41b727555a82a3d07db0bb2d7` | Nearby wood passed |
| 26.3 target retention | `c2eae420c4a669408e12120f535393e3c6c2609f437ce1959dbac14ce0f674d5` | Nearby wood passed |
| 1.21.1 owned inventory echo | `8e856c093bf8db58149c7a78eb4baea0df2f74f73a2afc6cd27f806813ab2714` | Table placed and confirmed, wooden pickaxe crafted, then stone-pickaxe crafting timed out |
| 1.21.1 search preview and execution diagnostics | `c3a92656e5b72c418e1a1ec3bd1c7412d02971476438f469d8ffc3491c78b213` | Stone route repeatedly discarded before movement; native test failed |

These artifacts predate this checkpoint commit. Their source-tree fingerprints were frozen locally; they are not claimed to be commit-built release artifacts.

Current source includes persistent claims, preferred-station discovery, server-receipt station ownership and cleanup, optional surplus-only backfill, and bounded search, movement, action, and ownership overlays. Unit checks pass for the pure models. Several native feature gates remain pending.

Build the current 1.21.1 prototype with JDK 21:

```sh
bash scripts/build-version.sh 1.21.1 -Powned_kernel_primary_1211=true :fabric-1211:compileVerificationJava --no-daemon --max-workers=1
```

Build the 26.3 prototype with JDK 25:

```sh
bash scripts/build-version.sh 26.3 -Powned_kernel_modern_263=true :fabric-modern:compileVerificationJava --no-daemon --max-workers=1
```

Other profiles are still being ported. Their old build path cannot compile the newly owned imports. Do not treat those profiles as supported by this development branch until each exact artifact passes its build. The normal release build and all 24 profile checks must pass before publication.

The next release remains blocked on executor cancellation, complete table and furnace recovery, preferred-station and claim enforcement fixtures, native backfill conservation, current GUI and overlay checks, independent review, and the full version matrix. The requested ntfy message will follow the published, verified tag.
