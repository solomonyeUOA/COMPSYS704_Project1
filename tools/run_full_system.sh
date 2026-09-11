#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME_8="${JAVA_HOME_8:-/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home}"
SYSTEMJ_LIB="${SYSTEMJ_LIB:-/private/tmp/compsys704-project-systemj/COMPSYS704_Project1_SystemJ_lib}"

exec python3 "$ROOT_DIR/tools/project.py" run \
    --java-home "$JAVA_HOME_8" \
    --systemj-lib "$SYSTEMJ_LIB" \
    "$@"
