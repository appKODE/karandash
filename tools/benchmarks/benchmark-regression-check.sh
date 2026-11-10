#!/usr/bin/env bash
set -euo pipefail

# Quick CLI Benchmark Regression Check
#
# Runs a small number of iterations and checks the averages against the thresholds in the
# platform baseline (tools/benchmarks/baseline-<os>-<arch>.json, "thresholds" block).
#
# Usage: benchmark-regression-check.sh <command> [iterations] [startup-threshold-ms] [validate-threshold-ms]
#
#   command    native binary path, or a full command line such as "java -jar cli/build/libs/karandash.jar"
#   BENCH_VARIANT       threshold set to read: native (default) or jar
#   BENCH_BASELINE      baseline json to read thresholds from (default: the one for this host)
#   threshold arguments override the baseline (the 21-rule threshold is then 2x the validate one)
#
# Exit codes:
#   0 - all benchmarks within thresholds
#   1 - regression detected (threshold exceeded) or a benchmarked command failed
#   2 - command not found, missing fixtures or baseline

COMMAND="${1:?Usage: benchmark-regression-check.sh <command> [iterations] [startup-threshold-ms] [validate-threshold-ms]}"
ITERATIONS="${2:-5}"
VARIANT="${BENCH_VARIANT:-native}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FIXTURES_DIR="$SCRIPT_DIR/fixtures/configs"

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) HOST=macos-arm64 ;;
    Linux-x86_64) HOST=linux-x64 ;;
    *) HOST="" ;;
esac
BASELINE="${BENCH_BASELINE:-$SCRIPT_DIR/baseline-$HOST.json}"

# threshold KEY: "<KEY>_<variant>_max_ms" from the baseline's thresholds block
threshold() {
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["thresholds"][sys.argv[2]])' \
        "$BASELINE" "${1}_${VARIANT}_max_ms" 2>/dev/null
}

if [ $# -ge 4 ]; then
    STARTUP_THRESHOLD_MS="$3"
    VALIDATE_THRESHOLD_MS="$4"
    FULL_THRESHOLD_MS=$((VALIDATE_THRESHOLD_MS * 2))
else
    if ! STARTUP_THRESHOLD_MS=$(threshold startup) ||
        ! VALIDATE_THRESHOLD_MS=$(threshold validation_3rules) ||
        ! FULL_THRESHOLD_MS=$(threshold validation_21rules); then
        echo "ERROR: no '$VARIANT' thresholds in baseline $BASELINE"
        exit 2
    fi
fi

read -r FIRST_WORD _ <<<"$COMMAND"
if [ ! -x "$FIRST_WORD" ] && ! command -v "$FIRST_WORD" >/dev/null 2>&1; then
    echo "ERROR: Command not found or not executable: $FIRST_WORD"
    exit 2
fi

if [ ! -f "$FIXTURES_DIR/minimal.yaml" ]; then
    echo "ERROR: Fixture config not found: $FIXTURES_DIR/minimal.yaml"
    exit 2
fi

echo "═══════════════════════════════════════════════════"
echo " Karandash CLI Regression Check ($VARIANT)"
echo " Command: $COMMAND"
echo " Iterations: $ITERATIONS"
echo " Thresholds: startup ${STARTUP_THRESHOLD_MS}ms, validate ${VALIDATE_THRESHOLD_MS}ms, validate-21 ${FULL_THRESHOLD_MS}ms"
echo "═══════════════════════════════════════════════════"
echo ""

now_ms() {
    if [[ "$(uname)" == "Darwin" ]]; then
        # perl starts in ~8ms; python3 took 30-80ms and swamped the ~40ms being measured
        perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'
    else
        echo $(($(date +%s%N) / 1000000))
    fi
}

# Prints the average in ms; exits 1 when the command fails, so a broken binary is not timed as fast.
measure_avg_ms() {
    local label="$1"
    local cmd="$2"
    local times=()

    for _ in $(seq 1 "$ITERATIONS"); do
        start_ms=$(now_ms)
        if ! eval "$cmd" > /dev/null 2>&1; then
            echo "FAILED: $label — '$cmd' exits non-zero" >&2
            eval "$cmd" 2>&1 | tail -n 20 >&2 || true
            exit 1
        fi
        end_ms=$(now_ms)
        times+=($((end_ms - start_ms)))
    done

    local total=0
    for t in "${times[@]}"; do total=$((total + t)); done
    local avg=$((total / ITERATIONS))

    IFS=$'\n' sorted=($(sort -n <<<"${times[*]}")); unset IFS
    echo "$avg"
    echo "  $label: avg=${avg}ms min=${sorted[0]}ms max=${sorted[$((ITERATIONS - 1))]}ms" >&2
}

FAILED=0

# check LABEL AVG THRESHOLD
check() {
    if [ "$2" -gt "$3" ]; then
        echo "REGRESSION: $1 ${2}ms exceeds threshold ${3}ms"
        FAILED=1
    else
        echo "OK: $1 ${2}ms (threshold: ${3}ms)"
    fi
}

echo "Measuring startup (--version)..."
STARTUP_MS=$(measure_avg_ms "startup" "$COMMAND --version")
check startup "$STARTUP_MS" "$STARTUP_THRESHOLD_MS"

echo ""
echo "Measuring validation (3 rules, dry-run)..."
VALIDATE_MS=$(measure_avg_ms "validate-3rules" "$COMMAND validate --config $FIXTURES_DIR/minimal.yaml --dry-run --quiet")
check validate "$VALIDATE_MS" "$VALIDATE_THRESHOLD_MS"

echo ""
echo "Measuring validation (21 rules, dry-run)..."
VALIDATE_FULL_MS=$(measure_avg_ms "validate-21rules" "$COMMAND validate --config $FIXTURES_DIR/full.yaml --dry-run --quiet")
check validate-21rules "$VALIDATE_FULL_MS" "$FULL_THRESHOLD_MS"

echo ""
echo "═══════════════════════════════════════════════════"
if [ "$FAILED" -eq 0 ]; then
    echo " All benchmarks PASSED"
else
    echo " REGRESSION DETECTED — benchmark thresholds exceeded"
fi
echo "═══════════════════════════════════════════════════"

exit $FAILED
