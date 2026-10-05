# Stonecutter partial-prediction regression

A repeated run failed after accepting a locally predicted two-slab move before the server completed the whole 64-stone cohort. It used the same [source and paired artifact](../26.3-stonecutting/artifact.json) as the passing bulk run. The actor paused with the owned station open instead of assuming the remaining input was accounted for.

[Failure result](run.json). The fix requires the whole expected cohort to match input consumption and inventory gain before completing the move. Corrected [1.21.1](../1.21.1-stonecutting-receipts/run.json) and [26.3](../26.3-stonecutting-receipts/run.json) batches pass.
