# 26.3 exact Preview 2 candidate jar: stonecutting_drain

1 controlled case passed using the exact CI production jar at source `bfb9fdc`. All production development outputs were removed; the verifier was retained separately. Class origins confirm the loaded production classes came from this jar. Bundled core and navigation classes match the original artifact byte for byte.

[Server observations](run.json), [artifact and runtime provenance](artifact.json). This proves these scenarios in an isolated development harness; ordinary-launcher, natural-world, remote-server and broader-version acceptance remain open.

The fixture sends ordinary `!lk stop` after 64 owned stone reaches the station, before output collection. A fresh server observation confirms all 128 stone returned, zero slabs, idle completion and full health. The placed station remains in the world; the check does not require it to return to inventory.
