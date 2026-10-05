# 26.3 development visualization check

Development classes passed the nearby crafting-table meadow case from an empty inventory with logs 20 blocks away. The server observed one crafting table, full health and idle completion. The captured active route was inspected and shows route geometry, target and compact task panel.

[Receipt](run.json). Command completion took 18,371 ms, including travel, mining and inventory crafting. The recorded first-movement value of 2,370 ms is the older client observation of a server snapshot polled every 20 ticks. It is an upper bound affected by polling delay. Later verifier code timestamps displacement on the server tick. Native synchronous route samples are diagnostic CPU timings, not normal incremental command latency.

This run does not validate the upcoming DROP phase or continuous walking work. It is not an exact published-jar check, a natural-world survival result or a competitor comparison.

A [guarded repetition](guarded-repeat.json), after the DROP phase and verifier proof corrections, passed the same meadow case. First displacement was timestamped on the server at 2133 ms; command completion took 17823 ms. Completion also required a readable saved active-route image. These are development classes, and one repetition does not establish a comparative speed gain.
