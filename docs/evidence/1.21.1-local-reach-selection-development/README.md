# Nearby reach selection on 1.21.1

This development regression starts with an empty inventory and sends `get wood 1` through the ordinary automation engine. A visible oak log stands at `(3,64,0)`. A second log at `(-6,64,-6)` sits behind six bedrock faces in an earlier scanned chunk. Persistent leaves cover the visible log. The world and client are disposable.

## Baseline

The [baseline receipt](baseline-artifact.json) pins the production source and verifier hash. The [run](baseline.json) correctly failed its first-target requirement. The engine selected the enclosed log after 272 ms. Its first mining intent for the visible log appeared after 7,502 ms. It later acquired one oak log at full health, with the enclosed log and all six bedrock faces preserved.

Those are client-observed selection and mining-intent times. The receipt separately labels its integrated-server block-state observation. It does not claim an exact server mining-start timestamp. The [active mining image](baseline-active-mining.png) captures the later visible-log attempt.

This proves the selection defect in the controlled fixture. It does not identify the target selected in Lu's village, establish a stable speed ratio, or compare against another mod. A candidate must select and mine the visible log first, then collect its drop and return idle at full health with the decoy intact.
