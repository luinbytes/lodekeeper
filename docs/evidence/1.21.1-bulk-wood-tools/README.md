# 1.21.1 bulk wood with optional axes

One `!lk get wood 64` command started from an empty inventory. The server observed crafting-table use, two wooden axes, exactly 64 final oak logs and full health. Axe durability lots were 1 and 53. Command-to-completion time was 241,232 ms and 4,825 world ticks. The screenshot shows completion, held tools and the final log stack.

This is a prepared 80-log fixture on a safe floor. There is no paired 1.21.1 baseline, so this run establishes controlled behavior rather than a speed improvement for that version. `artifact.json` identifies the paired production jar and source commit plus archived patch. Gameplay used development classes; verification content is excluded from production jars.
