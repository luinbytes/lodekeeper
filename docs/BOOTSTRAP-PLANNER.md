# Acquisition planning and safe approaches

Execution uses `planFast` to seek a complete feasible acquisition chain before running the wider bounded beam search. A seed is accepted only after the ordinary quantity, protected-stock, tool-wear, crafting-slot, station and fuel checks succeed for the entire target. It is explicitly not marked optimal. Plan previews retain the wider planner.

The seed receives at most 1,024 expansions and three quarters of the caller's elapsed budget. If it fails, beam search starts from the original inventory, uses the remaining node allowance and keeps the original deadline. Failed speculative branches cannot consume real inventory or carry mutated forecast state into the fallback. Native gathering precedes cooking and crafting in the seed's source order, while usable held tools remain preferred over new tools. Newly required tools follow the adapter's declared bootstrap tier order.

The default elapsed budget is a cooperative 20 ms deadline on a single planning worker. Checkpoints cannot preempt an individual allocation, sort or JVM pause. The client permits four additional time-limit retries for cold startup; it does not retry arbitrary logical failures indefinitely. These bounds are not a comparative benchmark or an absolute wall-clock guarantee.

Log discovery scans eligible native log sources together and remembers every matching block type separately from its capped nearest-position list. A crowded common species cannot make a rarer species appear absent just because its position fell outside that list. The same discovery is used for logs required inside recipes.

Interaction navigation accepts a bounded set of validated stances, allowing one search to find a reachable alternative to an isolated nearest stance. Item collection validates loaded, supported, collision-free stances against the native pickup area. The contact margin accounts for arrival tolerance; a zero-length stance path safely centers the player when necessary. Pickup movement owns its failure independently of mining targets. Falling drops and pickup delay wait under the action timeout before a source is treated as exhausted.

Focused Java regressions cover native-like tool and furnace bootstrapping, recycling cycles, conflicting ingredient alternatives, shared fallback budgets, disconnected stance alternatives, copied goal inputs and heuristic consistency. Actual client evidence remains separate in [compatibility](COMPATIBILITY.md) and the [coverage ledger](COVERAGE.md).
