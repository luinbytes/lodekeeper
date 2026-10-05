#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
target="${1:-1.20.1}"
if [[ $# -gt 0 ]]; then shift; fi
case "$target" in
  1.20)
    exec ./gradlew -Padapter=legacy -Pminecraft_version=1.20 \
      -Pyarn_mappings=1.20+build.1 -Ploader_version=0.19.5 \
      -Pfabric_version=0.83.0+1.20 :core:test :nav:test :fabric:build "$@"
    ;;
  1.20.1)
    exec ./gradlew -Padapter=legacy -Pminecraft_version=1.20.1 \
      -Pyarn_mappings=1.20.1+build.10 -Ploader_version=0.19.5 \
      -Pfabric_version=0.92.12+1.20.1 :core:test :nav:test :fabric:build "$@"
    ;;
  1.20.2|1.20.3|1.20.4)
    case "$target" in
      1.20.2) mappings=1.20.2+build.4; api=0.91.6+1.20.2 ;;
      1.20.3) mappings=1.20.3+build.1; api=0.91.1+1.20.3 ;;
      1.20.4) mappings=1.20.4+build.3; api=0.97.3+1.20.4 ;;
    esac
    exec ./gradlew -Padapter=1202 "-Pminecraft_version=$target" \
      "-Pyarn_mappings=$mappings" -Ploader_version=0.19.5 \
      "-Pfabric_version=$api" :core:test :nav:test :fabric-1202:build "$@"
    ;;
  1.20.5|1.20.6|1.21)
    case "$target" in
      1.20.5) mappings=1.20.5+build.1; api=0.97.8+1.20.5 ;;
      1.20.6) mappings=1.20.6+build.3; api=0.100.8+1.20.6 ;;
      1.21) mappings=1.21+build.9; api=0.102.0+1.21 ;;
    esac
    exec ./gradlew -Padapter=1211 "-Pminecraft_version=$target" \
      "-Pyarn_mappings=$mappings" -Ploader_version=0.19.5 \
      "-Pfabric_version=$api" :core:test :nav:test :fabric-1211:build "$@"
    ;;
  1.21.1)
    exec ./gradlew -Padapter=1211 -Pminecraft_version=1.21.1 \
      -Pyarn_mappings=1.21.1+build.3 -Ploader_version=0.19.5 \
      -Pfabric_version=0.110.0+1.21.1 :core:test :nav:test :fabric-1211:build "$@"
    ;;
  26.3)
    exec ./gradlew-modern -Padapter=modern -Pminecraft_version=26.3 \
      -Ploader_version=0.19.5 -Pfabric_version=0.161.0+26.3 \
      :core:test :nav:test :fabric-modern:build "$@"
    ;;
  *)
    printf 'No implemented build profile for Minecraft %s yet. See docs/COMPATIBILITY.md for coverage evidence.\n' "$target" >&2
    exit 2
    ;;
esac
