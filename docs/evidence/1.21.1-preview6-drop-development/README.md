# Preview 6 drop regression on 1.21.1

Development source `26b5d9b7128da1e29bcfc64c19174af342247079` passes the existing raised-platform drop fixture after launch centering changed. The [run](run.json) records a completed client-validated DROP, a server height transition from 65 to 64, one coal, full health and all nine platform blocks preserved at idle completion.

Whole-client wall time was 29,094 ms including initialization. The [console](console.txt) retains the progress trace. This fixture covers ordinary-friction one-block departure and landing, not every fall, modified physics or release-jar installation.
