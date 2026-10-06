# 26.3 native route query baseline

Development classes based on Preview 6 plus measurement counters. This is not an exact public-jar receipt or a measured speedup. The isolated client used 1536 MiB heap, two visible processors, a 640 by 360 window and a disposable prepared meadow.

[Run receipt](run.json) records eight identical native route searches, with two warmups and six measured samples. Median search CPU elapsed time was 69.38 ms, range 66.86 to 83.38 ms. All searches returned FOUND with the same path fingerprint, 56 expansions, 187 discovered nodes and 21 steps. Each performed 220 grounded-height collections and 372,275 voxel queries. Query counts do not establish which routine dominates CPU time. Fingerprinting happens outside the timed region.

The separate survival command acquired a crafting table from an empty inventory in 18,688 ms, with first server movement at 2,575 ms and final health 20.0. Travel, mining, pickup and crafting are included in command time. Native launch handoffs passed. Ordinary-world performance remains unverified.
