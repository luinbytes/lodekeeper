# Preview 4 published 1.21.1 jar check

The publicly downloaded `lodekeeper-1.21.1-0.1.0-preview.4.jar` matches SHA-256 `3d8735c585a3c5d74312b58c8f68a54e1fb507e974f3ba36c8fe75260db7e792`. It was built from immutable source `6f1145691225bc4f6b9f613f06d0e2c32a833da7`. The isolated local build passed 52 core, 73 navigation and nine adapter checks. Packaging inspection confirmed exact Minecraft and mod metadata, Java class levels, a populated Yarn refmap, and exclusion of verification fixtures and Baritone classes.

The [runtime receipt](run.json) passed the flat nearby crafting-table case from an empty inventory with wood 20 blocks away. The server observed one crafting table, full health and idle completion. First server displacement took 908 ms; command completion took 15,262 ms, including travel, mining and crafting. The saved active-route image was inspected and shows the path, target and compact panel.

[Artifact provenance](artifact.json) records the public download digest and production class origins in Fabric's processed release jar. Production development outputs were removed from the launch classpath. The separate development verifier remained to set up and observe the disposable world. This is an exact published-jar check in a development harness, not ordinary-launcher or natural-world acceptance.

Client input observations show 18 qualifying straight WALK arrivals, each with zero forward intent. That is the baseline for the subsequent continuous walking experiment, which is outside this release. The server movement timestamp and client input observations are separate measurements. A single run does not establish comparative performance.
