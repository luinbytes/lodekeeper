# Compatibility evidence

The target covers all stable Java releases from 1.20 through 26.3. This is a target, not a supported-version declaration. A successful compile is separate from a successful gameplay scenario. Never install a jar for a different game release. The exact 24-release dependency ledger and adapter boundaries are in [VERSION-MATRIX.md](VERSION-MATRIX.md).

| Versions | JVM | Adapter build | Gameplay |
| --- | --- | --- | --- |
| 1.20 | 17 | Exact CI development build passes | Pending |
| 1.20.1 | 17 | Development jar compiles and remaps | Nine controlled cases pass, including iron smelting, custom ore/3×3 crafting and automatic eating; natural-world checks pending |
| 1.20.2 | 17 | Exact local and CI development builds pass; adapter tests pass | Pending |
| 1.20.3, 1.20.4 | 17 | Each exact CI development build passes | Pending |
| 1.20.5, 1.20.6 | 21 | Each exact CI development build passes after identifier compatibility fix | Pending |
| 1.21 | 21 | Exact CI development build passes | Pending |
| **1.21.1** | **21** | **Development jar compiles, remaps and passes adapter tests** | **Nine controlled progression cases pass; natural-world checks pending** |
| 1.21.2, 1.21.3 | 21 | Exact local development builds pass; 1.21.2 verification source set also compiles | Pending |
| 1.21.4, 1.21.5 | 21 | Pending | Pending |
| 1.21.6, 1.21.7, 1.21.8 | 21 | Pending | Pending |
| 1.21.9, 1.21.10, 1.21.11 | 21 | Pending | Pending |
| 26.1, 26.1.1, 26.1.2 | 25 | Earlier 16-profile CI passes all three; current-head matrix pending | Pending |
| 26.2 | 25 | Exact local and earlier CI development builds pass; current-head matrix pending | Pending |
| 26.3 | 25 | Expanded development adapter, including automatic eating, compiles and packages; shared Java regressions pass | Nine controlled progression cases pass; natural-world checks pending |

## What a check proves

- Core/JUnit: dependency quantities, tool/material reservations, cycle handling, deterministic search bounds, command caps.
- Navigation/JUnit: conservative unknown/hazard behavior, explicit actions, placement budgets, cancellation, stale searches and bounded partial routes.
- Build: the exact Minecraft/Fabric API compiles and the artifact has exact-version metadata.
- Controlled game fixtures: genuine client movement and interactions, confirmed by the integrated server. These do not prove natural-world progression.
- Natural-world acceptance: gathering, exploration and complete chains in ordinary seeded survival worlds.
- User acceptance: the user has tried the delivered version and accepts the experience.

## Known implementation limits

The legacy adapter has synchronized shaped/shapeless recipes, furnace recipes, ordinary mining sources and reusable owned stations. Held-food eating is verified in the controlled 1.20.1, 1.21.1 and 26.3 fixtures. It has a conservative integer-height navigation model; fractional slab/stair stances, vehicles, dimensions and specialty stations are not yet verified. The mechanic coverage plan is in [EXPERIENCE.md](EXPERIENCE.md). No benchmark comparison is claimed yet.

Latest controlled 1.20.1 evidence: [nine-case server observations](evidence/1.20.1-progression/run.json), with per-case screenshots linked from the JSON. The 162,653 ms run started with an empty inventory on a deterministic resource pad; this is a controlled progression check, not a natural-world completion or comparative benchmark.

Controlled 1.21.1 evidence at `a795995`: [nine-case observations and screenshots](evidence/1.21.1-progression/run.json), with the [paired artifact digest](evidence/1.21.1-progression/artifact.json). The 160,429 ms run begins empty and passes the same progression, custom-content and food checks with health 20 throughout. Waiting for resource overlays before verifier startup resolved the earlier startup stall.

First 26.3 gameplay evidence: [server observations and failure](evidence/26.3-first-run/run.json), paired with the [development artifact digest](evidence/26.3-first-run/artifact.json). Logs, inventory crafting, sticks and a wooden pickaxe passed. Stone-pickaxe acquisition stopped at the route corridor safeguard; later cases were not run.

The `a795995` route fix then passes stone-pickaxe and furnace construction as well: [six-case observations and smelting blocker](evidence/26.3-furnace-run/run.json), with [artifact digest](evidence/26.3-furnace-run/artifact.json). Iron smelting has no plan, with cooking-fuel provider resolution under investigation; later cases remain unverified.

Controlled 26.3 progression at `f894786`: [nine-case server observations](evidence/26.3-progression/run.json), paired with the [artifact and source digests](evidence/26.3-progression/artifact.json). All nine cases pass in 167,452 ms with health 20 throughout and four real crafting-table openings. Resolving furnace providers from the server's reloadable registries and ranking held fuels within a bounded search fixed the smelting blocker. The fixture uses a prepared resource pad and does not establish ordinary-world completion or a comparative benchmark.

Exact build evidence: the [ten-version CI run](https://github.com/luinbytes/lodekeeper/actions/runs/37273002879) passed at `46f3496`. [Artifact inspection](evidence/builds/46f3496.json) verifies downloaded SHA-256 digests, exact Minecraft metadata, Java class versions, bundled Java 17 core, and exclusion of verifier fixtures. These checks prove packaging, not gameplay. This run predates the modern automatic-eating addition, which passed its separate local 26.3 build.

Updated exact build evidence: [all ten profiles pass at `c9b1825`](https://github.com/luinbytes/lodekeeper/actions/runs/37279029560). The [downloaded artifact inspection](evidence/builds/c9b1825.json) repeats those checks after normalized recipes, navigation safety changes and modern automatic eating, including the separate 1.20.5–1.20.6 crafting API overlay.

The [24-profile current-source CI matrix](https://github.com/luinbytes/lodekeeper/actions/runs/37287589333) is running. Earlier [16-profile CI](https://github.com/luinbytes/lodekeeper/actions/runs/37285757052) passed 14 exact builds; its 1.21.2/1.21.3 verification compilation failed at API seams fixed in subsequent local builds. Neither partial CI nor source overlays establish gameplay compatibility.
