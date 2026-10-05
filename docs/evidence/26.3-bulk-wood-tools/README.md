# 26.3 bulk wood with optional axes

One `!lk get wood 64` command started from an empty inventory. The mod gathered bootstrap wood, crafted and opened a table, made two wooden axes and finished with exactly 64 logs. Server evidence reports full health, one crafting-table opening and axe durability lots of 1 and 53. Command-to-completion time was 242,159 ms and 4,844 world ticks; startup is excluded.

The [baseline](../26.3-bulk-wood-baseline/run.json) used the same frozen source, world fixture and settings with the option disabled: 278,043 ms and 5,561 world ticks. This enabled run used about 12.9% less command-to-completion time in one preliminary controlled pair. It is not a general performance guarantee, natural-world acceptance or comparison against another mod. See the [protocol](../../HARVEST-INVESTMENT.md).

`artifact.json` identifies the paired production jar and source commit plus exact archived `source.patch`. Gameplay used development classes; verification content is excluded from distributed jars.
