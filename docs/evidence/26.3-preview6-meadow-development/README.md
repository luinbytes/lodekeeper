# Preview 6 meadow launch check on 26.3

The development classes at source `26b5d9b7128da1e29bcfc64c19174af342247079` passed the empty-inventory wood-to-crafting-table fixture where the exact Preview 5 jar failed. The [run](run.json) records one crafting table, full health and idle completion after 18,806 ms from the command. First server movement occurred after 2,625 ms. These are single controlled observations, not natural-world or comparative performance results.

All three WALK-to-JUMP handoffs were captured after the engine's START callback and before client physics. Their horizontal speeds were below `.01`, with maximum `.0082500573`; each was grounded within `.10` of the source center. The separately labelled pure predicate probe rejects `.015`. Native collision and trajectory checks remain intact. The [console](console.txt) records the settled launches and progress; the [image](active-route.png) shows the path, target and elapsed timer.

The [source receipt](source.json) identifies development code. This run does not verify the CI release jar, ordinary launcher installation, bridge placement, parkour or modified friction physics. Those require separate evidence.
