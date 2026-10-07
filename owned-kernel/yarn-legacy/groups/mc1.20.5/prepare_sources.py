from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

GROUP = Path(__file__).resolve().parent
CANDIDATE = GROUP.parent.parent
PROFILE = 'mc1.20.5'


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare the pinned owned Baritone source candidate for mc1.20.5.")
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    command = [
        sys.executable,
        str(CANDIDATE / "generator" / "prepare_sources.py"),
        "--profile", PROFILE,
        "--source-root", args.source_root,
        "--lock-file", str(GROUP / "source-lock.json"),
        "--lifecycle-overrides", str(GROUP),
    ]
    if args.archive is not None:
        command += ["--archive", str(args.archive)]
    if args.output is not None:
        command += ["--output", str(args.output)]
    subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
