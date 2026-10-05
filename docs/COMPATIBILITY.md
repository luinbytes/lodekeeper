# Compatibility evidence

The target covers all stable Java releases from 1.20 through 26.3. This is a target, not a supported-version declaration. A successful compile is separate from a successful gameplay scenario. Never install a jar for a different game release.

| Versions | JVM | Adapter build | Gameplay |
| --- | --- | --- | --- |
| 1.20 | 17 | Pending | Pending |
| 1.20.1 | 17 | Development jar compiles and remaps | Pending |
| 1.20.2, 1.20.3, 1.20.4 | 17 | Pending | Pending |
| 1.20.5, 1.20.6 | 21 | Pending | Pending |
| 1.21, 1.21.1 | 21 | Pending | Pending |
| 1.21.2, 1.21.3 | 21 | Pending; recipe-display API boundary | Pending |
| 1.21.4, 1.21.5 | 21 | Pending | Pending |
| 1.21.6, 1.21.7, 1.21.8 | 21 | Pending | Pending |
| 1.21.9, 1.21.10, 1.21.11 | 21 | Pending | Pending |
| 26.1, 26.1.1, 26.1.2 | 25 | Pending; unobfuscated source boundary | Pending |
| 26.2, 26.3 | 25 | Pending | Pending |

## What a check proves

- Core/JUnit: dependency quantities, tool/material reservations, cycle handling, deterministic search bounds, command caps.
- Navigation/JUnit: conservative unknown/hazard behavior, explicit actions, placement budgets, cancellation, stale searches and bounded partial routes.
- Build: the exact Minecraft/Fabric API compiles and the artifact has exact-version metadata.
- Controlled game fixtures: genuine client movement and interactions, confirmed by the integrated server. These do not prove natural-world progression.
- Natural-world acceptance: gathering, exploration and complete chains in ordinary seeded survival worlds.
- User acceptance: the user has tried the delivered version and accepts the experience.

## Known implementation limits

The initial adapter has synchronized shaped/shapeless recipes, furnace recipes, ordinary mining sources and reusable owned stations. It has a conservative integer-height navigation model; fractional slab/stair stances, vehicles, dimensions and specialty stations are not yet verified. The mechanic coverage plan is in [EXPERIENCE.md](EXPERIENCE.md). No benchmark comparison is claimed yet.
