# Build the modern 26.3 owned kernel

The `modern26.3` project fetches Baritone 1.20.0 source at commit `25111daedf1d59e6a8dfb5a3e61885cdb8d953df`. It checks the pinned archive SHA-256, rewrites upstream packages under `dev.lodekeeper.navigation.kernel`, and applies 51 hash-pinned source overrides. The generated mixin config names 18 client mixins. The source tree keeps both upstream LGPL notices.

The kernel project compiles with Java 25 and Minecraft 26.3's official Mojang names. It produces a plain library jar with no `fabric.mod.json` or entrypoint. The host build uses the same compiled source-set outputs and places them in the `fabric-modern` production jar.

Run the host package and jar inspection from the repository root:

```sh
./gradlew-modern --no-daemon --no-parallel --max-workers=1 \
  -Padapter=modern -Pmodern_api_family=26.3 -Pminecraft_version=26.3 \
  -Powned_kernel_modern_263=true :fabric-modern:inspectOwnedKernelHostJar
```

Run `:fabric-modern:check` to include the same inspection in the adapter checks. The setting `owned_kernel_modern_263` accepts only adapter `modern` with Minecraft 26.3. Other profiles keep using `gradle/baritone.gradle`.

Gradle fetches the source archive into the kernel project's `build/` directory. `source-lock.json` pins the archive URL, commit, SHA-256, Minecraft version, mapping names, dependency metadata hash, and reviewed baseline fingerprint. `overrides/lifecycle-overrides.sha256` lists every canonical override path and hash. The generator writes its source manifest under `build/generated/owned-kernel/`.

The jar inspection checks every generated Java class, each configured client mixin class, both LGPL notices, and the packaged source pins. It also rejects upstream `baritone/` packages, the external bridge, nested jars, and standalone kernel mod metadata. A successful build does not prove client runtime behavior or mixin injection targets.
