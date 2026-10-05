# 1.21.1 native smoker batch

The isolated development client passed one `!lk get cooked_porkchop 72` command on 2026-10-05. The fixture supplied exactly 128 raw porkchops, nine coal and one smoker item; it supplied no cooked porkchops. Starting inventory was not empty.

The integrated server observed exactly 72 cooked porkchops, 56 raw porkchops remaining, zero inventory coal, one native smoker opening, full health and an idle engine. The action placed the provided station and refilled input across the first stack boundary. Completion took 361,457 ms and 7,229 world ticks from the command; overall isolated-client time was 378,581 ms. The native 72-operation cooking time is 360 seconds. This is a fixture observation, not a comparison with another mod.

[Server evidence](run.json) records initial and final inventories and timing. [Artifact provenance](artifact.json) identifies source commit `53c48a6`, the source-tree digest and paired production jar. Its [working-tree patch](source.patch) is empty: gameplay used development classes built from that committed source. Verification classes and content are excluded from the production jar.

The screenshot shows the emptied smoker and inventory stacks of 64 and eight cooked porkchops alongside 56 raw porkchops. Its framebuffer retains the preceding action HUD; server evidence supplies the final idle-state observation.

The client ran alone with a 1.5 GiB heap cap, two active processors, a 640×360 window, two-chunk render distance, simulation distance five, a 30 FPS cap and muted audio. No Gradle build ran alongside it; the owned client exited normally.

This verifies the declared-stock batch on 1.21.1. It does not establish natural-world acquisition, remote-server behavior, interruption recovery, unsupported short recipes or complete cooking coverage.
