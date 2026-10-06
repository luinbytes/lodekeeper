# Preview 5 meadow launch failure on 1.21.1

The exact CI jar from source `97a3b046bde700e738daf3672abac51afca9fa54` also reproduces the [26.3 launch failure](../26.3-preview5-meadow-launch-failure/README.md). The [run](run.json), [jar receipt](artifact.json), [console trace](console.txt) and [active route](active-route.png) preserve the failed empty-inventory meadow case. Repeated jump rejections prevent collection and eventually exhaust bounded exploration. The player finishes at full health with no crafted table.

This rules out a latest-version-only failure. The next candidate must brake and settle at a centered launch without changing the strict native collision proof. Preview 5 remains unpublished.
