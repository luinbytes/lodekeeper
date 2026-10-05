# 26.3 bulk-wood baseline

This isolated baseline issued one `!lk get wood 64` command from an empty inventory with optional axe acquisition disabled. The server confirmed exactly 64 oak logs, no axes, no crafting-table opening and full health. Command-to-completion time was 278,043 ms and 5,561 world ticks. Client startup is excluded from case timing.

`artifact.json` identifies the paired production jar and exact development source: commit plus archived `source.patch`. Gameplay used the development classes, including the verifier, while the production jar excludes verification content. The world supplies 80 prepared oak-log blocks on a safe floor; this is not natural-world progression or a benchmark against another mod.
