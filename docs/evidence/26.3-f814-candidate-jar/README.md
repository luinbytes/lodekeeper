# 26.3 candidate jar at f814670

Nine server-observed controlled cases passed using the exact CI production jar from source f814670 and CI run 37351738964. Production development classes and core/navigation jars were removed; the verifier remained a separate development module. Class-load evidence confirms production engine, planner and navigation came from this jar.

This is intermediate candidate evidence before the fractional-start fix, not a claim that a later release has been tested. The harness uses an isolated superflat fixture and does not prove natural-world progression, multiplayer or ordinary-launcher acceptance.
