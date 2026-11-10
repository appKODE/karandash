#!/usr/bin/env bash
# Runs `karandash validate` ONCE for the GitHub Action and publishes passed / findings_count / sarif-file as step outputs.
# One run writes json (+ sarif, reviewdog, the requested format) through a generated overlay config whose `reporters:`
# block lists the files and which `extends:` the user's config (the CLI allows one --format per run, and an explicit
# --format suppresses `reporters:`). A .kts config cannot be extended, so there each extra file costs one more run.
# Env: INPUT_CONFIG, INPUT_FORMAT, INPUT_SARIF, INPUT_REVIEWDOG_FORMAT, INPUT_TOKEN, KARANDASH_CMD (default
#      "java -jar karandash.jar"), GITHUB_OUTPUT. Platform tokens (GITHUB_TOKEN) come from INPUT_TOKEN.
set -uo pipefail
command -v jq > /dev/null || { echo "Error: jq is required on the runner (install it before this action)" >&2; exit 1; }
CMD="${KARANDASH_CMD:-java -jar karandash.jar}"
OUT="${GITHUB_OUTPUT:-/dev/null}"
FORMAT="${INPUT_FORMAT:-console}"
RESULTS_JSON=karandash-results.json
[ -n "${INPUT_TOKEN:-}" ] && export GITHUB_TOKEN="$INPUT_TOKEN"

# format ids whose files this run must write
formats=(json)
if [ "${INPUT_SARIF:-false}" = "true" ] || [ "$FORMAT" = "sarif" ]; then formats+=(sarif); fi
if [ -n "${INPUT_REVIEWDOG_FORMAT:-}" ]; then formats+=("$INPUT_REVIEWDOG_FORMAT"); fi
if [ "$FORMAT" != "console" ]; then formats+=("$FORMAT"); fi
formats=($(printf '%s\n' "${formats[@]}" | sort -u))

config="${INPUT_CONFIG:-}"
if [ -z "$config" ]; then
  for c in .karandash/config.yaml .karandash/config.yml .karandash/config.json .karandash/config.kts; do
    if [ -f "$c" ]; then config="$c"; break; fi
  done
fi

rm -f karandash-results.*
EXIT_CODE=0
if [ "${config##*.}" = "kts" ]; then
  for f in "${formats[@]}"; do
    $CMD validate --config "$config" --format "$f" --output "karandash-results.$f"
    code=$?
    if [ "$f" = json ]; then EXIT_CODE=$code; fi
  done
else
  overlay=karandash-overlay.yaml
  : > "$overlay"
  if [ -n "$config" ]; then
    printf 'extends: "%s"\n' "$(cd "$(dirname "$config")" && pwd)/$(basename "$config")" >> "$overlay"
  fi
  echo "reporters:" >> "$overlay"
  for f in "${formats[@]}"; do
    printf '  %s:\n    output: karandash-results.%s\n' "$f" "$f" >> "$overlay"
  done
  $CMD validate --config "$overlay"
  EXIT_CODE=$?
  rm -f "$overlay"
fi

if ! COUNT=$(jq '.findings | length' "$RESULTS_JSON" 2>/dev/null); then
  echo "Error: $RESULTS_JSON missing or not valid JSON (exit code $EXIT_CODE)" >&2
  { echo "passed=false"; echo "findings_count=0"; echo "reviewdog-enabled=false"; } >> "$OUT"
  exit "$([ "$EXIT_CODE" -ne 0 ] && echo "$EXIT_CODE" || echo 1)"
fi

# console is printed by the run itself; a file format is shown from its file
if [ "$FORMAT" != "console" ] && [ -f "karandash-results.$FORMAT" ]; then cat "karandash-results.$FORMAT"; fi

if [ -f karandash-results.sarif ]; then echo "sarif-file=karandash-results.sarif" >> "$OUT"; fi

if [ -n "${INPUT_REVIEWDOG_FORMAT:-}" ]; then
  echo "reviewdog-enabled=true" >> "$OUT"
  echo "reviewdog-file=karandash-results.$INPUT_REVIEWDOG_FORMAT" >> "$OUT"
else
  echo "reviewdog-enabled=false" >> "$OUT"
fi

echo "findings_count=$COUNT" >> "$OUT"
if [ "$EXIT_CODE" -eq 0 ]; then echo "passed=true" >> "$OUT"; else echo "passed=false" >> "$OUT"; fi
echo "Validation $([ "$EXIT_CODE" -eq 0 ] && echo passed || echo failed) with $COUNT findings"
exit "$EXIT_CODE"
