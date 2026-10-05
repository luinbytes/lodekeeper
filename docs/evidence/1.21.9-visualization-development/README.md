# 1.21.9 development visualization check

This checks development sources, not a released jar. An isolated survival client with an empty inventory acquired one crafting table from logs 20 blocks away. The integrated server observed the item and the engine returned idle. First movement occurred 1,223 ms after the command; completion took 15,225 ms. These prepared-world timings do not establish natural-world performance or a comparison with another mod.

The [active-route capture](screenshots/lodekeeper-2026-10-05T22-54-07-542947Z-3cb24809-nearby_crafting_table_20_blocks-active-route.png) shows the compact task panel, cyan route and yellow target with the version-specific world-render mixin applied. The [receipt](run.json) records inventory, health and timing. This does not verify the separate 1.21.10 artifact or every rendering condition.
