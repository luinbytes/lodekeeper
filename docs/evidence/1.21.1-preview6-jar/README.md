# Exact Preview 6 jar on 1.21.1

The [artifact receipt](artifact.json) identifies the immutable CI jar from source `26b5d9b7128da1e29bcfc64c19174af342247079`. Production development classes and core/navigation jars were removed from the isolated launch path. The [class origins](production-class-origins.txt) confirm the engine, movement controller, acquisition planner and launch helper loaded from Fabric's processed copy of that jar. Its metadata targets 1.21.1, and the core planner and navigation helper bytes match the original CI jar. Verification classes stayed separate.

The [run](run.json) passes the empty-inventory meadow wood-to-crafting-table fixture at full health and idle completion after 18,608 ms from the command. First server movement occurred after 2,421 ms. The recorder captured 3 settled WALK-to-JUMP handoffs before player physics with zero unsettled handoffs. The [console](console.txt) records the progress trace; the [image](active-route.png) shows the live route and timer.

This is exact-jar evidence in an isolated development harness. Ordinary launcher installation, natural-world performance, bridge placement, parkour and multiplayer acceptance remain separate.
