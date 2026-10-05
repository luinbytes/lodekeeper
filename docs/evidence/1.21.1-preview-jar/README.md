# 1.21.1 published preview jar check

The exact jar published in [Development Preview 1](https://github.com/luinbytes/lodekeeper/releases/tag/v0.1.0-preview.1) passed all nine controlled gameplay cases on 5 October 2026. The isolated client exited normally after 174,296 ms.

The launch excluded production development classes/resources and standalone core/navigation jars, and supplied the published intermediary jar through Fabric Loader. Loader remapped the jar into the development namespace before loading it. JVM class-loading records in [artifact.json](artifact.json) verify that every recorded production class came from that single processed jar. All bundled Minecraft-independent core/navigation class bytes match the original published jar; both original and processed SHA-256 values are recorded. Only the separate development verification mod remained on the classpath.

[run.json](run.json) records nine passing integrated-server cases: gathering wood, crafting tables and sticks, wooden/stone pickaxes, furnace crafting, iron smelting, a custom recipe, and eating while gathering. The [screenshots](screenshots/) are native framebuffer captures; the final eating/gathering image was visually inspected. Prepared terrain and ores make these controlled fixtures, not natural-world survival benchmarks.

This is production-jar gameplay in a development harness, including Loader's namespace transformation. Normal user-launcher acceptance, remote servers, and native gameplay of every published version remain open. No user saves or existing applications were touched.
