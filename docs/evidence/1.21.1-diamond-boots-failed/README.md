# Failed single-command diamond boots check

This controlled 1.21.1 run starts with an empty inventory and sends one acquisition command. It gathers wood, constructs and uses a crafting table, and crafts wooden and stone pickaxes. It then pauses before iron smelting; the target boots are not obtained. The source patch and artifact manifest describe the exact development build.

This failure is retained alongside subsequent fixes. Separately instrumented local diagnosis found that pickup navigation could attempt an unsupported item cell and blacklist an unrelated ore target. Those diagnostic runs are not represented as this run's exact source evidence.
