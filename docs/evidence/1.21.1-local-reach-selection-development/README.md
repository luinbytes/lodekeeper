# Nearby reach selection on 1.21.1

This development regression starts with an empty inventory and sends `get wood 1` through the ordinary automation engine. A visible oak log stands at `(3,64,0)`. A second log at `(-6,64,-6)` sits behind six bedrock faces in an earlier scanned chunk. Persistent leaves cover the visible log. The world and client are disposable.

## Baseline

The [baseline receipt](baseline-artifact.json) pins the production source and verifier hash. The [run](baseline.json) correctly failed its first-target requirement. The engine selected the enclosed log after 272 ms. Its first mining intent for the visible log appeared after 7,502 ms. It later acquired one oak log at full health, with the enclosed log and all six bedrock faces preserved.

The same fixture also sent `get crafting_table 1` from empty inventory. Its [baseline recipe-chain run](baseline-crafting-table.json) selected the enclosed log after 326 ms, then began mining the visible log after 7,502 ms. It crafted the table but failed the same first-target proof. This shows that the correction must cover recipe prerequisites as well as the wood alias.

Those are client-observed selection and mining-intent times. The receipt separately labels its integrated-server block-state observation. It does not claim an exact server mining-start timestamp. The [active mining image](baseline-active-mining.png) captures the later visible-log attempt.

This proves the selection defect in the controlled fixture. It does not identify the target selected in Lu's village, establish a stable speed ratio, or compare against another mod. A candidate must select and mine the visible log first, then collect its drop and return idle at full health with the decoy intact.

## Candidate

The independently reviewed candidate checks actual mining reach and face visibility before committing to a wider discovery result. Separate cursors cover the wood alias, recipe prerequisites and the selected gather step. Probes share a bounded per-tick deadline; constructing the small local coordinate list is outside that deadline. Cached results retain their request, world, recipe, origin and rejection keys. Completed empty local scans expire, and wider discovery resets after exploration or a new request.

The [wood-alias candidate](candidate-wood.json) also selected and began mining the visible log first after 245 ms. It collected one log at full health, returned idle and preserved the decoy. Its [console trace](candidate-wood-console.txt) records the elapsed time and phases.

The [crafting-table candidate](candidate-crafting-table.json) selected and began mining the visible log first after 283 ms. It acquired one table, returned idle with health 20, and preserved the enclosed log and all six bedrock faces. Command completion took 10,534 ms, including ordinary barehand mining, drop collection and inventory crafting. These are single controlled observations.

The [20-block crafting-table check](candidate-distant-crafting-table.json) also passed from an empty inventory. First server movement occurred after 1,509 ms; command completion took 14,741 ms. Its active route recorded 7 ms of search CPU, 19 expansions and 60 discovered nodes. Valid distant recipe-wood hints survived local preflight and reached navigation without a replan loop.

The [rendered route](candidate-distant-active-route.png) shows the path, target and elapsed timer at `0:00:01`. The [nearby trace](candidate-crafting-table-console.txt) and [distant trace](candidate-distant-crafting-table-console.txt) came from the isolated instances' `logs/latest.log`. The [focused diagnostic check](candidate-diagnostics-check.json) covers bounded logging, retained throttled transitions, overflow counts, task reset, elapsed time, phase labels and current goal counts. This reflection check complements gameplay; it does not replace it.

The [exploration regression](candidate-exploration.json) starts empty with the resource chunk unloaded. Two exploration waypoints led to eight server-confirmed logs and an idle engine at health 20. Discovery resumed in the newly reached area.

The [candidate artifact receipt](candidate-artifact.json) records the compiled jar and source hashes. These checks use development classes. Exact released-jar receipts remain separate. Broader natural-world performance and competitor comparisons remain open.
