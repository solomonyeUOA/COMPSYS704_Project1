#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME_8="${JAVA_HOME_8:-/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home}"
SYSTEMJ_LIB="${SYSTEMJ_LIB:-/private/tmp/compsys704-project-systemj/COMPSYS704_Project1_SystemJ_lib}"
BUILD_DIR="$ROOT_DIR/build"
GENERATED_DIR="$BUILD_DIR/generated"
CLASSES_DIR="$BUILD_DIR/classes"
LOG_DIR="$BUILD_DIR/full-system-logs"
PIDS=()

if [[ ! -x "$JAVA_HOME_8/bin/java" || ! -x "$JAVA_HOME_8/bin/javac" ]]; then
    echo "Temurin Java 8 not found at: $JAVA_HOME_8" >&2
    exit 1
fi
if [[ ! -f "$SYSTEMJ_LIB/sjc-2.2-13-g8ab684c-SNAPSHOT.jar" ]]; then
    echo "SystemJ library directory not found at: $SYSTEMJ_LIB" >&2
    exit 1
fi

cleanup() {
    local pid
    trap - EXIT INT TERM
    echo
    echo "Stopping full system..."
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
    for pid in "${PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT INT TERM

cd "$ROOT_DIR"
echo "Verifying project toolchain..."
python3 tools/verify_project_toolchain.py \
    --java-home "$JAVA_HOME_8" \
    --systemj-lib "$SYSTEMJ_LIB"

echo "Generating and compiling SystemJ sources..."
rm -rf "$GENERATED_DIR" "$CLASSES_DIR"
mkdir -p "$GENERATED_DIR" "$CLASSES_DIR" "$LOG_DIR"

# macOS ships Bash 3, so use newline-delimited arrays without requiring mapfile.
IFS=$'\n' read -r -d '' -a sysj_sources < <(
    find xuqi_pos xuqi_coordinator visualisation machines \
        -type f -name '*.sysj' -print | sort && printf '\0'
)
"$JAVA_HOME_8/bin/java" -cp "$SYSTEMJ_LIB/*" \
    com.systemj.compiler.JavaPrettyPrinter \
    -d "$GENERATED_DIR" --nojavac --silence \
    "${sysj_sources[@]}"

IFS=$'\n' read -r -d '' -a java_sources < <(
    find common xuqi_pos xuqi_coordinator visualisation machines \
        -type f -name '*.java' -print | sort && printf '\0'
)
"$JAVA_HOME_8/bin/javac" -cp "$SYSTEMJ_LIB/*" \
    -d "$CLASSES_DIR" \
    "$GENERATED_DIR"/*.java \
    "${java_sources[@]}"

CLASSPATH="$CLASSES_DIR:$SYSTEMJ_LIB/*"

start_runtime() {
    local name="$1"
    local config="$2"
    shift 2
    echo "Starting $name"
    "$JAVA_HOME_8/bin/java" "$@" -cp "$CLASSPATH" \
        com.systemj.SystemJRunner "$config" \
        >"$LOG_DIR/$name.log" 2>&1 &
    PIDS+=("$!")
}

# Start machine and display receivers before M1 begins dispatching work.
start_runtime "m2" "machines/transfer/member2_system.xml"
start_runtime "m3" "machines/rotary_lid/member3_system.xml" \
    -Djava.awt.headless=false
start_runtime "m4" "machines/filling_capping/member4_simulation.xml"
start_runtime "visualisation" "visualisation/abs_visualisation_plant.xml" \
    -Djava.awt.headless=false
sleep 2
start_runtime "coordinator" "xuqi_coordinator/coordinator.xml"
sleep 1
start_runtime "pos" "xuqi_pos/pos.xml" -Djava.awt.headless=false

sleep 2
for pid in "${PIDS[@]}"; do
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "A runtime stopped during startup. Check: $LOG_DIR" >&2
        exit 1
    fi
done

echo
echo "FULL SYSTEM RUNNING"
echo "Logs: $LOG_DIR"
echo "Use the POS window to submit an order."
echo "Stop this Run Configuration to terminate every runtime."

wait
