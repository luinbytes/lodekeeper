# Primary 1.21.1 owned navigation-kernel build candidate

This candidate builds Baritone 1.11.3 from a pinned source archive, applies the hash-checked lifecycle overrides, compiles under official Mojang mappings, and packages it into a single host mod jar. It does not build or embed the upstream Baritone mod jar. The kernel output has no Fabric mod manifest or entrypoint.

The mapping chain is explicit:

1. The kernel Loom project compiles Mojmap sources and remaps its library jar to intermediary.
2. `owned-kernel-hostremap` uses Loom 1.8.13 with the host's Yarn 1.21.1 build 3 mappings and an explicit `RemapJarTask` to remap that intermediary jar to named.
3. The host compiles against the named artifact, flattens its classes, mixin config/refmap, provenance manifest, and LGPL notices into the host jar, then Loom remaps the complete host jar back to intermediary.

The `smoke/` projects reproduce the two-project mapping and flatten pipeline without modifying the main repository. In the main repository, place this module at `owned-kernel/primary1.21.1`, place `smoke/host-remap/build.gradle` at `owned-kernel/primary1.21.1-hostremap/build.gradle`, include those projects only for the enabled `adapter=1211` owned-kernel profile, and apply `gradle/primary1211-host-integration.gradle` from `fabric-1211`. Keep the existing `gradle/baritone.gradle` path for profiles that have not moved to the owned kernel. Set `owned_kernel_primary_1211=true` only for the enabled primary profile. The integration expects root properties `minecraft_version=1.21.1`, `yarn_mappings=1.21.1+build.3`, `loader_version=0.19.5`, and `fabric_version=0.110.0+1.21.1` for the current primary profile.

Run the mapping/package smoke with the main repository's Gradle wrapper, JDK 21, and Python 3:

```sh
./gradlew -p /tmp/lodekeeper-owned-kernel-build-candidate \
  --no-daemon --no-parallel --max-workers=1 \
  :inspectOwnedKernelJar \
  :owned-kernel-hostremap:remapOwnedKernelForHost \
  :fabric-1211:inspectOwnedKernelHostNamedJar \
  :fabric-1211:compileJava \
  :fabric-1211:remapJar \
  :fabric-1211:inspectOwnedKernelHostJar
```

`fetchOwnedKernelSource` downloads only the locked archive, verifies its SHA-256 before extraction, and rejects unsafe archive entries. Generated sources include hashes for every generated file and the full override manifest. Jar inspection checks namespace, required classes, mixin config/refmap, absence of upstream package/bridge/embedded jars, no standalone kernel manifest, final host manifest registration, and packaged LGPL/provenance files.

The smoke validates compilation, both mapping tasks, host compile classpath, and final jar layout. It does not launch Minecraft or establish runtime navigation behavior.
