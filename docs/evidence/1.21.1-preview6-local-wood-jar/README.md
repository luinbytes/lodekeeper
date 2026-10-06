# Exact Preview 6 nearby selection on 1.21.1

The exact CI jar from source `26b5d9b7128da1e29bcfc64c19174af342247079` passes the chunk-boundary local selection fixture. The visible log at `(3,64,0)` was both the first engine target and first mining intent after 246 ms. The [run](run.json) records one log acquired after 4,701 ms from the command, full health and idle completion. The enclosed decoy at `(-6,64,-6)` and all six bedrock faces stayed intact.

The baseline fixture selected the enclosed decoy first and did not begin mining the visible log until 7,502 ms. This single controlled comparison proves the local-selection correction; it does not establish general performance or a Baritone comparison. First movement in the successful case occurred later because mining started within reach and movement was needed only for collection.

The [artifact receipt](artifact.json) records the exact jar digest and separate verification harness. The [class origins](production-class-origins.txt) confirm production classes came from Fabric's processed copy of that jar. The [console](console.txt) records target and progress events; the [image](active-mining.png) shows active mining and the elapsed timer. Ordinary launcher and natural-world acceptance remain open.
