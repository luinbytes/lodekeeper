# 1.21.1 DROP departure development check

The guarded development controller passed the forced platform case. It starts at exact server position `(0.5,65,0.5)` on a 3×3 bedrock platform at Y64 above a bedrock floor at Y63. The server supplies only one stone pickaxe.

The [receipt](run.json) requires a client-observed completed validated DROP from the platform to the floor, a grounded server Y64 checkpoint outside the platform, all nine platform blocks preserved, exactly one coal, full health and idle completion. The nearer encased ore remains intact and is rejected; the accessible ore is mined.

Earlier candidates collected coal but failed the completed-DROP proof. One cancelled after vanilla retained its grounded flag without support. Another overshot the strict route corridor before landing. Explicit departure tracking and velocity-based braking passed this fixture without expanding that corridor. A second final launch guard preserves full support before actual departure even if the terrain revision is unchanged.

These are development classes, not an exact released jar. This one-block, ordinary-friction case does not establish all drops, fluids, altered player sizes, ice or parkour behavior.
