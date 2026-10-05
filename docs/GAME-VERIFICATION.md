# Isolated in-game verification

Lodekeeper's planner and navigation tests run without opening Minecraft. This optional client run checks that the Fabric adapter can carry a small set of goals through real client input, server-side block changes, inventory synchronization and crafting.

The verifier is a separate development-only Fabric mod. It is inert unless launched with `-Dlodekeeper.verify=true`; it is not part of the published Lodekeeper jar. The launcher task should use one client with a 1.5 GiB heap and one Gradle worker.

## What it exercises

The verifier creates a peaceful superflat world with a fixed seed under the development run directory's `verification/worlds` folder. It never opens or modifies the normal `saves` folder. On a small raised stone pad it places eight oak logs, clears the player's inventory, and teleports the player to the starting point on the server thread. It then submits ordinary prefixed chat commands through Fabric's client chat hook:

1. `!lk get wood 8` — find, path to and mine the fixture logs from an empty inventory.
2. `!lk get crafting_table 1` — craft a table from the gathered inventory.
3. `!lk get stick 8` — craft sticks from the remaining inventory.

The result file records pass/fail, engine state, elapsed ticks, the inventory counts read on the integrated server thread, the player's server-side position and health, and each captured screenshot path. Screenshots are saved under the evidence folder's `screenshots/` subdirectory when Minecraft's screenshot recorder can capture them. These are real survival interactions in a controlled fixture world; they do not establish success in a natural world, on a multiplayer server, or for every Minecraft version.

## Run it

Build the normal Java and Fabric checks first. To launch the optional game check, use the dedicated `runVerificationClient` Loom run task from the repository root:

```sh
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon --max-workers=1 :fabric:runVerificationClient
```

This task must run with the separate `lodekeeper-verification` development mod on the client classpath and the `lodekeeper.verify` JVM property enabled. Start it from the title screen, with no other Minecraft client running. The harness creates a new uniquely named test world and ends its isolated client when complete. It stops with a failure record if the complete run exceeds five minutes.

If the environment does not have the JDK at the path above, set `JAVA_HOME` to a Java 17 installation. Do not launch the normal `runClient` task with the verification mod installed unless the JVM property is absent; the verifier is intended for its dedicated run configuration.

## Evidence and limits

The default Loom run directory is `fabric/run`. A run writes:

- `fabric/run/verification/evidence/run-<id>.json`
- screenshots under `fabric/run/verification/evidence/screenshots/`
- its isolated world under `fabric/run/verification/worlds/run-<id>/`

Keep the world and evidence when diagnosing a failure. After the client has stopped, the entire `fabric/run/verification` directory is disposable. The initial verifier covers basic acquisition and inventory crafting only. Furnace smelting, mining-tool progression, diamond equipment, parkour, building, custom registry content and natural-world exploration need separate scenarios before they can be claimed as in-game verified. This suite targets the currently compiled 1.20.1 artifact; each additional Minecraft version needs its own compile and runtime result.

## Recorded 1.20.1 check

The [2026-10-05 controlled run](evidence/1.20.1-basic/run.json) passed all three cases with full server-side health. Eight logs took 979 game ticks; the table took 101 ticks and eight sticks took 120 ticks. These timings include discovery, normal bare-hand mining, movement, inventory clicks and confirmation. They are fixture timings, not a comparison with other mods. The screenshots in that evidence directory belong to that exact run.
