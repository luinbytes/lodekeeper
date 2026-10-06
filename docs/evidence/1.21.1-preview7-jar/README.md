# Minecraft 1.21.1 Preview 7 jar check

The exact CI jar for source `15209767a9fa4b73e101fba5ed5499937bb7d14f` passed the native collision epoch checks and one ordinary wood-to-table command from an empty inventory on a prepared uneven meadow. The integrated server observed one table, health 20 and an idle engine.

First server movement took 2,339 ms; acquisition and crafting took 18,576 ms from the command. This is a single controlled functional run, not a paired speed benchmark or natural-world acceptance.

[Run evidence](run.json), [artifact hash and launch scope](artifact.json), [production class origins](production-class-origins.txt), [frozen verifier source hashes](fixture-source-hashes.json) and [active route screenshot](active-route.png) bind the result to the jar. Development production outputs were removed from the classpath. The verification fixture was retained separately and is excluded from the production jar.

The client used a 1,536 MiB heap and two active processors, ran without another client or Gradle, and exited with code zero. Ordinary launcher, multiplayer and broad player-mechanic acceptance remain pending.
