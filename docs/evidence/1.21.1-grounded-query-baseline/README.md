# 1.21.1 native route query baseline

Development classes based on Preview 6 plus measurement counters. This is not an exact public-jar receipt or a measured speedup. The isolated client used 1536 MiB heap, two visible processors, a 640 by 360 window and a disposable prepared meadow.

[Run receipt](run.json) records eight identical native route searches, with two warmups and six measured samples. Median search CPU elapsed time was 63.26 ms, range 59.48 to 71.61 ms. All searches returned FOUND with the same path fingerprint, 56 expansions, 187 discovered nodes and 21 steps. Each performed 220 grounded-height collections and 372,275 voxel queries. Query counts do not establish which routine dominates CPU time. Fingerprinting happens outside the timed region.

The separate survival command acquired a crafting table from an empty inventory in 18,847 ms, with first server movement at 2,312 ms and final health 20.0. Travel, mining, pickup and crafting are included in command time. Native launch handoffs passed. Ordinary-world performance remains unverified.
