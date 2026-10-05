# Empty-inventory iron-pickaxe development check

Minecraft 1.21.1 completed one ordinary `!lk get iron_pickaxe` command from an exactly empty server inventory. Eight prepared oak logs stood at x=20 through x=27. Ordinary stone and iron resources were prepared nearby. The verifier supplied no tools, table or target item.

The server confirmed one iron pickaxe, full health, four native table openings, one furnace opening and all four deepslate blocks preserved. Command-to-completion time was 122,607 ms; total client-run time was 139,986 ms, including setup and capture. These times are different measurements.

[Run receipt](run.json) records the initial empty stock, fixture positions and final inventory. The owned isolated client exited with code 0 under a 360-second watchdog. This used development class directories with the visualization enabled, before the subsequent bounded wood-publication correction. It is not exact-release-jar proof, a natural-world run or a comparative performance result.

A [later development repetition](repeat.json), after bounded hint publication and the DROP point-guard correction, also passed: first server movement at 1,800 ms and command completion at 120,785 ms. It began with an empty inventory, opened both native stations, retained all four deep deepslate blocks, and finished with one iron pickaxe and full health. This does not by itself prove which drop transition was exercised; a dedicated platform case is being added.
