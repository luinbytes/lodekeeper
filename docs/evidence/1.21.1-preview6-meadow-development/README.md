# Preview 6 meadow launch check on 1.21.1

Development source `26b5d9b7128da1e29bcfc64c19174af342247079` passes the empty-inventory meadow wood-to-table fixture where the exact Preview 5 jar failed. The [run](run.json) records one crafting table, full health and idle completion after 18,074 ms from the command. First server movement occurred after 2,437 ms. These are single controlled observations.

The recorder captured 2 settled WALK-to-JUMP handoffs before client physics, with zero unsettled handoffs. Maximum horizontal speed was 0.0070571960; each was grounded within `.10` of the source center. The separately labelled pure predicate probe rejects `.015`. Native collision checks remain intact. The [console](console.txt) records the launches and progress; the [image](active-route.png) shows the live route and timer.

The [source receipt](source.json) identifies development classes. Release-jar installation, natural-world performance, bridge placement and parkour need separate evidence.
