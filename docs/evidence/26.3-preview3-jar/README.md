# 26.3 exact candidate jar: default

9 controlled cases passed using the exact CI production jar at source `38d558f`. All production development outputs were removed; the verifier was retained separately. Class origins confirm the loaded production classes came from this jar. Bundled core and navigation classes match the original artifact byte for byte.

[Server observations](run.json), [artifact and runtime provenance](artifact.json). This proves these scenarios in an isolated development harness; ordinary-launcher, natural-world, remote-server and broader-version acceptance remain open.
