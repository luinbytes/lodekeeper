# 26.3 native blast-furnace batch

The isolated development client passed one `!lk get iron_ingot 72` command on 2026-10-05. The fixture supplied exactly 128 raw iron, nine coal and one blast-furnace item; it supplied no ingots. Starting inventory was not empty.

The integrated server observed exactly 72 ingots, 56 raw iron remaining, zero inventory coal, one native blast-furnace opening, full health and an idle engine. The action placed the provided station and refilled input across the first stack boundary. Completion took 361,958 ms and 7,236 world ticks from the command; overall isolated-client time was 380,595 ms. The native 72-operation cooking time is 360 seconds. This is a fixture observation, not a comparison with another mod.

[Server evidence](run.json) records initial and final inventories and timing. [Artifact provenance](artifact.json) identifies source commit `53c48a6`, the source-tree digest and paired production jar. Its [working-tree patch](source.patch) is empty: gameplay used development classes built from that committed source. Verification classes and content are excluded from the production jar.

The client ran alone with a 1.5 GiB heap cap, two active processors, a 640×360 window, two-chunk render distance, simulation distance five, a 30 FPS cap and muted audio. No Gradle build ran alongside it; the owned client exited normally. This modern harness records server evidence without a screenshot.

This verifies the declared-stock batch on 26.3. It does not establish natural-world acquisition, remote-server behavior, interruption recovery, cold-start waiting, unsupported short recipes or complete cooking coverage.
