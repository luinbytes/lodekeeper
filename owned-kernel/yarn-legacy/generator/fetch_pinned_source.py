from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from urllib.request import Request, urlopen

MAX_ARCHIVE_BYTES = 128 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(lock: dict[str, str], archive: Path) -> None:
    expected = lock["archive_sha256"]
    if archive.exists():
        actual = sha256(archive)
        if actual != expected:
            raise SystemExit(f"Cached source archive hash mismatch: expected {expected}, got {actual}")
        return

    archive.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=f".{archive.name}.", suffix=".part", dir=archive.parent)
    temp = Path(temp_name)
    digest = hashlib.sha256()
    size = 0
    try:
        request = Request(lock["archive_url"], headers={"User-Agent": "lodekeeper-source-build/1"})
        with os.fdopen(fd, "wb") as output:
            with urlopen(request, timeout=45) as response:
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > MAX_ARCHIVE_BYTES:
                        raise SystemExit(f"Pinned source archive exceeds {MAX_ARCHIVE_BYTES} bytes")
                    digest.update(chunk)
                    output.write(chunk)
        actual = digest.hexdigest()
        if actual != expected:
            raise SystemExit(f"Downloaded source archive hash mismatch: expected {expected}, got {actual}")
        os.replace(temp, archive)
    finally:
        if temp.exists():
            temp.unlink()


def safe_extract(archive: Path, source_dir: Path, expected_root: str) -> None:
    source_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="owned-kernel-source-", dir=source_dir.parent) as temp_name:
        temp_root = Path(temp_name)
        seen: set[str] = set()
        with tarfile.open(archive, "r:gz") as source:
            for member in source.getmembers():
                archive_path = PurePosixPath(member.name)
                if (
                    archive_path.is_absolute()
                    or ".." in archive_path.parts
                    or "\\" in member.name
                    or not archive_path.parts
                    or archive_path.parts[0] != expected_root
                ):
                    raise SystemExit(f"Unsafe path in pinned source archive: {member.name}")
                rel_parts = archive_path.parts[1:]
                if not rel_parts:
                    target = temp_root / expected_root
                else:
                    target = temp_root.joinpath(expected_root, *rel_parts)
                relative_name = archive_path.as_posix()
                if relative_name in seen:
                    raise SystemExit(f"Duplicate path in pinned source archive: {member.name}")
                seen.add(relative_name)

                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                if not member.isfile():
                    raise SystemExit(f"Unsupported entry in pinned source archive: {member.name}")
                target.parent.mkdir(parents=True, exist_ok=True)
                source_file = source.extractfile(member)
                if source_file is None:
                    raise SystemExit(f"Could not read pinned source entry: {member.name}")
                with source_file, target.open("wb") as output:
                    shutil.copyfileobj(source_file, output)

        extracted = temp_root / expected_root
        if not extracted.is_dir():
            raise SystemExit(f"Pinned source archive did not contain {expected_root}")
        if source_dir.exists():
            existing = {path.relative_to(source_dir): path for path in source_dir.rglob('*') if path.is_file()}
            expected = {path.relative_to(extracted): path for path in extracted.rglob('*') if path.is_file()}
            if any(path.is_symlink() for path in source_dir.rglob('*')):
                raise SystemExit(f"Source output contains a symbolic link: {source_dir}")
            if existing:
                if existing.keys() != expected.keys() or any(
                        sha256(existing[name]) != sha256(expected[name]) for name in expected):
                    raise SystemExit(f"Source output differs from its pinned archive: {source_dir}")
                return
            source_dir.rmdir()
        os.replace(extracted, source_dir)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    args = parser.parse_args()

    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    if lock.get("schema_version") != 1:
        raise SystemExit("Unsupported source lock schema")
    if len(lock.get("source_commit", "")) != 40 or len(lock.get("archive_sha256", "")) != 64:
        raise SystemExit("Source lock must pin a full commit and SHA-256")
    if args.source_dir.name != lock["source_root"]:
        raise SystemExit(f"Source output directory must be named {lock['source_root']}")

    fetch(lock, args.archive)
    if sha256(args.archive) != lock["archive_sha256"]:
        raise SystemExit("Pinned source archive changed during extraction")
    safe_extract(args.archive, args.source_dir, lock["source_root"])
    print(f"Prepared Baritone {lock['baritone_version']} source {lock['source_commit']}")


if __name__ == "__main__":
    main()
