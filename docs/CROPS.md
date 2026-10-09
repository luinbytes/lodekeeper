# Crop acquisition

The 1.21.1 and 26.3 adapters publish mature wheat, carrots, potatoes and beetroot as acquisition sources. Requests use the existing `!lk get` command.

| Requested item | Planting item |
| --- | --- |
| `wheat` or `wheat_seeds` | `wheat_seeds` |
| `carrot` | `carrot` |
| `potato` | `potato` |
| `beetroot` or `beetroot_seeds` | `beetroot_seeds` |

Each operation reserves real ordinary planting stock before breaking a mature crop. Random seed yields do not supply forecast planting stock. The actor observes inventory and target receipts, replants its harvested cell, and restores its original hand before installing the result.

Crop work captures the original local job, plan, catalog, native session and input ownership. Permission loss ends new effects. Original repair and hand cleanup retain separate permissions within that captured scope. An uncertain send keeps its evidence and does not permit a retry.

Acquisition is bounded by thirty-two cycles and ten active minutes per job. Each crop operation has a thirty-second deadline. Protection, loaded-cell, border, build-height, stock and capacity checks apply at the effect boundary.

The [source evidence](evidence/resource-actions/crop-original-authority-source-r3.json) records successful artifact builds on 1.21.1 and 26.3, plus compatibility builds on 1.20.1 and 1.21.11. Crops remain unavailable on those compatibility adapters. Native harvest, replant, seed retention, refusal, stop and AIR preservation are unrun. Continuous renewable farming remains open.
