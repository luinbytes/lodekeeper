# Reject terrain changes during navigation proofs

The three regression tests run against the previous `cba2ee6` Planner class fail as expected. A changed, invalid source probe returns `NO_PATH`; mutations during grounded-height collection and motion proof return `IN_PROGRESS`. All three must return `STALE` with no path. The [raw baseline](before.log) retains the JUnit assertions.

The candidate checks the terrain revision after the source probe, after expansion and before terminal search results. The 1.21.1 build passes 52 core tests, 91 navigation tests and 10 shared adapter tests. The same shared sources also compile on 1.20.1. [Source hashes and suite counts](summary.json) bind these checks to the reviewed planner and test files.

These checks cover the pure Java planner boundary. Native world/player context, dynamic collision changes and watch saturation remain separate verification work. This is development source, not a new public release or a performance measurement.
