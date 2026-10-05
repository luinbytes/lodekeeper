#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! java -version >/dev/null 2>&1 && [[ -d /usr/local/opt/openjdk@17 ]]; then
  export JAVA_HOME=/usr/local/opt/openjdk@17
  export PATH="$JAVA_HOME/bin:$PATH"
fi
./gradlew --no-daemon :core:test :nav:test :fabric:build "$@"
