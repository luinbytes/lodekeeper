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
  1.21.2|1.21.3)
    case "$target" in
      1.21.2) mappings=1.21.2+build.1; api=0.106.1+1.21.2 ;;
      1.21.3) mappings=1.21.3+build.2; api=0.114.1+1.21.3 ;;
    esac
    exec ./gradlew -Padapter=1212 -Pinput_api_family=1212 "-Pminecraft_version=$target" \
      "-Pyarn_mappings=$mappings" -Ploader_version=0.19.5 \
      "-Pfabric_version=$api" :core:test :nav:test :fabric-1212:build "$@"
    ;;
  1.21.4|1.21.5|1.21.6|1.21.7|1.21.8|1.21.9|1.21.10|1.21.11)
    case "$target" in
      1.21.4) mappings=1.21.4+build.8; api=0.119.4+1.21.4; family=1214 ;;
      1.21.5) mappings=1.21.5+build.1; api=0.128.2+1.21.5; family=1215 ;;
      1.21.6) mappings=1.21.6+build.1; api=0.128.2+1.21.6; family=1215 ;;
      1.21.7) mappings=1.21.7+build.8; api=0.129.0+1.21.7; family=1215 ;;
      1.21.8) mappings=1.21.8+build.1; api=0.136.1+1.21.8; family=1215 ;;
      1.21.9) mappings=1.21.9+build.1; api=0.134.1+1.21.9; family=1215 ;;
      1.21.10) mappings=1.21.10+build.3; api=0.138.4+1.21.10; family=1215 ;;
      1.21.11) mappings=1.21.11+build.6; api=0.141.6+1.21.11; family=1215 ;;
    esac
    exec ./gradlew -Padapter=1212 "-Pinput_api_family=$family" "-Pminecraft_version=$target" \
      "-Pyarn_mappings=$mappings" -Ploader_version=0.19.5 \
      "-Pfabric_version=$api" :core:test :nav:test :fabric-1212:build "$@"
    ;;
  26.1|26.1.1|26.1.2)
    case "$target" in
      26.1) api=0.145.1+26.1 ;;
      26.1.1) api=0.145.4+26.1.1 ;;
      26.1.2) api=0.155.3+26.1.2 ;;
    esac
    exec ./gradlew-modern -Padapter=modern -Pmodern_api_family=26.1 \
      "-Pminecraft_version=$target" -Ploader_version=0.19.5 \
      "-Pfabric_version=$api" :core:test :nav:test :fabric-modern:build "$@"
    ;;
  26.2)
    exec ./gradlew-modern -Padapter=modern -Pmodern_api_family=26.2 -Pminecraft_version=26.2 \
      -Ploader_version=0.19.5 -Pfabric_version=0.161.0+26.2 \
      :core:test :nav:test :fabric-modern:build "$@"
    ;;
  26.3)
    exec ./gradlew-modern -Padapter=modern -Pmodern_api_family=26.3 -Pminecraft_version=26.3 \
      -Ploader_version=0.19.5 -Pfabric_version=0.161.0+26.3 \
      :core:test :nav:test :fabric-modern:build "$@"
    ;;
  *)
    printf 'No implemented build profile for Minecraft %s yet. See docs/COMPATIBILITY.md for coverage evidence.\n' "$target" >&2
    exit 2
    ;;
esac
