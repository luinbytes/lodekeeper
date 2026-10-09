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

The [checkpoint evidence](evidence/resource-actions/main-7b696b0-crop-checkpoint-gates.json) records native wheat passes on frozen `7b696b0` artifacts for 1.21.1 and 26.3. Each run collects four ordinary wheat and replants all four harvested cells. Primary retains 29 seeds and modern 31, with full health, unchanged unrelated stock, restored input, selection and native settings, and fresh post-idle receipts. All six existing animal/AIR and prepared recovery controls pass on those same artifacts. These controls do not exercise crop-pending AIR recovery.

The [surface correction](evidence/resource-actions/crop-surface-hit-source-r8.json) records successful artifact builds on 1.21.1, 26.3, 1.20.1 and 1.21.11. Crops remain unavailable on the compatibility adapters. Earlier wheat failures remain recorded for their named artifacts, including the [pre-send replant refusal](evidence/resource-actions/main-93ac718-crop-wheat-failed.json). The exact first conjunct of that combined refusal was not logged.

To try wheat acquisition, use the version-matched checkpoint artifact beside loaded mature wheat with ordinary wheat seeds in main inventory, then run `!lk get wheat 4`. Native verification used an isolated supplied fixture. Other crop outputs, wrong-seed refusal, stop, maximum-Y cases, crop-pending AIR and continuous renewable farming remain unverified. Handoff, storage, bucket/milk and remote candidates remain parked at this checkpoint.
