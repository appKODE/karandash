#!/usr/bin/env bash
set -euo pipefail

# CLI Distribution Benchmark Script
#
# Usage: benchmark-cli.sh <command> <iterations> <variant>
#
# Arguments:
#   command     - The CLI command to benchmark (e.g., "cli/build/bin/.../cli.kexe" or "java -jar ...")
#   iterations  - Number of iterations per benchmark (default: 30)
#   variant     - Variant name for output files: native, fatjar, jar

COMMAND="${1:?Usage: benchmark-cli.sh <command> <iterations> <variant>}"
ITERATIONS="${2:-30}"
VARIANT="${3:-unknown}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULTS_DIR="$PROJECT_DIR/benchmarks/results"
FIXTURES_DIR="$SCRIPT_DIR/fixtures/configs"

mkdir -p "$RESULTS_DIR"

CSV_FILE="$RESULTS_DIR/${VARIANT}.csv"
META_FILE="$RESULTS_DIR/${VARIANT}-meta.json"
FAIL_TAIL_LINES="${BENCH_FAIL_LOG_LINES:-60}"

echo "variant,metric,iterations,avg_ms,min_ms,max_ms,p50_ms,p95_ms" > "$CSV_FILE"

measure() {
    local label="$1"
    local cmd="$2"
    local times=()
    local fail_output=""
    local failure_file=""
    local safe_label=""

    # Verify the command works before benchmarking
    if ! fail_output=$(eval "$cmd" 2>&1); then
        safe_label="$(printf '%s' "$label" | tr -c '[:alnum:]_.-' '_')"
        failure_file="$RESULTS_DIR/${VARIANT}-${safe_label}.failure.log"
        printf '%s\n' "$fail_output" > "$failure_file"

        echo "SKIPPED (command failed)" >&2
        echo "  ERROR: '$cmd' exits non-zero. Check dependencies (libcurl, libssl, java)." >&2
        echo "  Full output: $failure_file" >&2
        if [[ -n "$fail_output" ]]; then
            echo "  --- command output (last ${FAIL_TAIL_LINES} lines) ---" >&2
            printf '%s\n' "$fail_output" | tail -n "$FAIL_TAIL_LINES" >&2
            echo "  --- end command output ---" >&2
        fi
        echo "${VARIANT},${label},0,0,0,0,0,0" >> "$CSV_FILE"
        return 1
    fi

    echo -n "  $label ($ITERATIONS iterations)... " >&2

    for _ in $(seq 1 "$ITERATIONS"); do
        if [[ "$(uname)" == "Darwin" ]]; then
            # perl starts in ~8ms; python3 took 30-80ms and swamped the ~40ms being measured
            start_ns=$(perl -MTime::HiRes=time -e 'printf "%d", time * 1e9')
            eval "$cmd" > /dev/null 2>&1 || true
            end_ns=$(perl -MTime::HiRes=time -e 'printf "%d", time * 1e9')
            elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
        else
            start_ns=$(date +%s%N)
            eval "$cmd" > /dev/null 2>&1 || true
            end_ns=$(date +%s%N)
            elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
        fi
        times+=("$elapsed_ms")
    done

    IFS=$'\n' sorted=($(sort -n <<<"${times[*]}")); unset IFS

    local total=0
    for t in "${sorted[@]}"; do total=$((total + t)); done
    local avg=$((total / ITERATIONS))
    local min="${sorted[0]}"
    local max="${sorted[$((ITERATIONS - 1))]}"
    local p50_idx=$(( ITERATIONS / 2 ))
    local p95_idx=$(( ITERATIONS * 95 / 100 ))
    local p50="${sorted[$p50_idx]}"
    local p95="${sorted[$p95_idx]}"

    echo "avg=${avg}ms min=${min}ms max=${max}ms p50=${p50}ms p95=${p95}ms" >&2
    echo "${VARIANT},${label},${ITERATIONS},${avg},${min},${max},${p50},${p95}" >> "$CSV_FILE"
}

echo "=== Karandash CLI Benchmark: ${VARIANT} ===" >&2
echo "Command: ${COMMAND}" >&2
echo "Iterations: ${ITERATIONS}" >&2
echo "" >&2

# Cold startup
measure "cold-start" "$COMMAND --version"

# Validate with minimal config (3 rules)
if [ -f "$FIXTURES_DIR/minimal.yaml" ]; then
    measure "validate-3-rules" "$COMMAND validate --config $FIXTURES_DIR/minimal.yaml --dry-run 2>&1"
fi

# Validate with standard config (10 rules)
if [ -f "$FIXTURES_DIR/standard.yaml" ]; then
    measure "validate-10-rules" "$COMMAND validate --config $FIXTURES_DIR/standard.yaml --dry-run 2>&1"
fi

# Validate with full config (21 rules)
if [ -f "$FIXTURES_DIR/full.yaml" ]; then
    measure "validate-21-rules" "$COMMAND validate --config $FIXTURES_DIR/full.yaml --dry-run 2>&1"
fi

# Collect metadata
BINARY_SIZE=0
if [ -f "$COMMAND" ]; then
    if [[ "$(uname)" == "Darwin" ]]; then
        BINARY_SIZE=$(stat -f%z "$COMMAND" 2>/dev/null || echo 0)
    else
        BINARY_SIZE=$(stat -c%s "$COMMAND" 2>/dev/null || echo 0)
    fi
fi

cat > "$META_FILE" <<EOF
{
  "variant": "${VARIANT}",
  "iterations": ${ITERATIONS},
  "date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "platform": "$(uname -s)-$(uname -m)",
  "binary_size_bytes": ${BINARY_SIZE}
}
EOF

echo "" >&2
echo "Results: $CSV_FILE" >&2
echo "Meta:    $META_FILE" >&2
