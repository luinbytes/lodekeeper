# 26.3 published preview jar check

The exact jar published in [Development Preview 1](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.1) passed all nine controlled gameplay cases on 5 October 2026. The isolated client exited normally after 177,880 ms.

The launch excluded the production development classes/resources and standalone core/navigation jars, and supplied the published jar through Fabric Loader. JVM class-loading records in [artifact.json](artifact.json) verify that the loaded production classes came from that jar. Only the separate development verification mod remained on the classpath. The jar SHA-256 matches the public release asset and inspected 24-version build manifest.

[run.json](run.json) records integrated-server inventory and menu observations for wood gathering, crafting tables, sticks, wooden/stone pickaxes, furnace crafting, iron smelting, a custom recipe, and eating during gathering. Recipes, ores and terrain were deliberately prepared in a disposable fixture; the run does not establish natural-world survival performance.

This checks a production jar inside an isolated development harness. Normal user-launcher acceptance, remote servers, and native gameplay of every published version remain open. No user saves or existing applications were touched.
