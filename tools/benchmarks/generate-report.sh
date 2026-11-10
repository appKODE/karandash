#!/usr/bin/env bash
set -euo pipefail

# Benchmark Report Generator (CI fan-in stage)
#
# Usage: generate-report.sh <native.csv> <fatjar.csv>
#
# Reads CSV artifacts from the benchmark jobs and produces
# a combined summary report.

NATIVE_CSV="${1:?Usage: generate-report.sh <native.csv> <fatjar.csv>}"
FATJAR_CSV="${2:?Missing fatjar.csv}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULTS_DIR="$PROJECT_DIR/benchmarks/results"

mkdir -p "$RESULTS_DIR"

SUMMARY_MD="$RESULTS_DIR/summary.md"
SUMMARY_CSV="$RESULTS_DIR/summary.csv"
COMPARISON="$RESULTS_DIR/comparison.txt"

read_csv_value() {
    local file="$1" metric="$2" column="$3"
    awk -F',' -v m="$metric" -v c="$column" '
        NR==1 { for(i=1;i<=NF;i++) if($i==c) col=i }
        $2==m { print $col }
    ' "$file" 2>/dev/null || echo "N/A"
}

echo "variant,metric,avg_ms,min_ms,max_ms,p50_ms,p95_ms" > "$SUMMARY_CSV"

for csv in "$NATIVE_CSV" "$FATJAR_CSV"; do
    if [ -f "$csv" ]; then
        tail -n +2 "$csv" >> "$SUMMARY_CSV"
    fi
done

native_startup=$(read_csv_value "$NATIVE_CSV" "cold-start" "avg_ms")
fatjar_startup=$(read_csv_value "$FATJAR_CSV" "cold-start" "avg_ms")

native_v3=$(read_csv_value "$NATIVE_CSV" "validate-3-rules" "avg_ms")
fatjar_v3=$(read_csv_value "$FATJAR_CSV" "validate-3-rules" "avg_ms")

native_v10=$(read_csv_value "$NATIVE_CSV" "validate-10-rules" "avg_ms")
fatjar_v10=$(read_csv_value "$FATJAR_CSV" "validate-10-rules" "avg_ms")

native_v21=$(read_csv_value "$NATIVE_CSV" "validate-21-rules" "avg_ms")
fatjar_v21=$(read_csv_value "$FATJAR_CSV" "validate-21-rules" "avg_ms")

DATE=$(date -u +%Y-%m-%d)

cat > "$COMPARISON" <<EOF
════════════════════════════════════════════════════════════════════
 KARANDASH BENCHMARK COMPARISON — ${DATE}
════════════════════════════════════════════════════════════════════

 Metric                       Native      Fat JAR
 ───────────────────────────  ──────────  ──────────
 Cold start (--version)       ${native_startup} ms    ${fatjar_startup} ms
 Validate (3 rules)           ${native_v3} ms    ${fatjar_v3} ms
 Validate (10 rules)          ${native_v10} ms    ${fatjar_v10} ms
 Validate (21 rules)          ${native_v21} ms    ${fatjar_v21} ms

════════════════════════════════════════════════════════════════════
EOF

cat "$COMPARISON" >&2

cat > "$SUMMARY_MD" <<EOF
# Karandash Benchmark Summary

**Date:** ${DATE}
**Pipeline:** \${CI_PIPELINE_URL:-local}

## Results

| Metric | Native | Fat JAR |
|--------|--------|---------|
| Cold start (\`--version\`) | ${native_startup} ms | ${fatjar_startup} ms |
| Validate (3 rules) | ${native_v3} ms | ${fatjar_v3} ms |
| Validate (10 rules) | ${native_v10} ms | ${fatjar_v10} ms |
| Validate (21 rules) | ${native_v21} ms | ${fatjar_v21} ms |

## Performance Budgets (Native)

| Metric | Budget | Actual | Status |
|--------|--------|--------|--------|
| Cold start | <100ms | ${native_startup} ms | $([ "${native_startup}" != "N/A" ] && [ "${native_startup}" -le 100 ] 2>/dev/null && echo "PASS" || echo "CHECK") |
| Validate (3 rules) | <150ms | ${native_v3} ms | $([ "${native_v3}" != "N/A" ] && [ "${native_v3}" -le 150 ] 2>/dev/null && echo "PASS" || echo "CHECK") |
| Validate (10 rules) | <300ms | ${native_v10} ms | $([ "${native_v10}" != "N/A" ] && [ "${native_v10}" -le 300 ] 2>/dev/null && echo "PASS" || echo "CHECK") |
| Validate (21 rules) | <500ms | ${native_v21} ms | $([ "${native_v21}" != "N/A" ] && [ "${native_v21}" -le 500 ] 2>/dev/null && echo "PASS" || echo "CHECK") |
EOF

echo "" >&2
echo "Summary:    $SUMMARY_MD" >&2
echo "CSV:        $SUMMARY_CSV" >&2
echo "Comparison: $COMPARISON" >&2
