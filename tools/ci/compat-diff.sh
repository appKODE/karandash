#!/usr/bin/env bash
# Runs the previous release's fat JAR and HEAD's fat JAR on every compat golden config and diffs:
#   1. `config validate --file <cfg>`              (stdout+stderr, exit code)
#   2. `validate --format json --config <cfg> --mr <fixture>`  (file mode, stdout, exit code)
#
# Usage: tools/ci/compat-diff.sh <previous.jar> <head.jar> [out-dir]
# Either binary may be a native cli.kexe instead of a jar (run directly): `<head.jar> <head cli.kexe>` checks
# the native binary against the JVM goldens.
# Exit 0 = identical, 1 = differences (see <out-dir>/compat.diff). Must run from the repo root:
# the config pipeline rejects config paths outside the working directory.
set -uo pipefail

PREV_JAR="$1"
HEAD_JAR="$2"
OUT="${3:-build/compat-diff}"
MR_FIXTURE="tools/integration-tests/fixtures/test-mr.json"
CONFIGS="$(find cli/src/jvmTest/resources/compat/configs -name config.yaml | sort)"

# Strip run-dependent noise: timestamps, durations, the tool version, absolute paths.
normalize() {
  sed -E \
    -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z?/<ts>/g' \
    -e 's/"(duration|durationMs|elapsedMs|timestamp)": *[0-9.]+/"\1": 0/g' \
    -e 's/[0-9]+(\.[0-9]+)? ?(ms|s)\b/<dur>/g' \
    -e 's/karandash[ /]v?[0-9]+\.[0-9]+\.[0-9]+/karandash <ver>/g' \
    -e "s|$PWD|<root>|g"
}

cli() {
  local bin="$1"; shift
  case "$bin" in
    *.jar) java -jar "$bin" "$@" ;;
    *) "$bin" "$@" ;;
  esac
}

run_all() {
  local jar="$1" dir="$2"
  mkdir -p "$dir"
  for cfg in $CONFIGS; do
    local name
    name="$(echo "$cfg" | sed -E 's|.*/configs/||; s|/config.yaml||; s|/|_|g')"
    cli "$jar" config validate --file "$cfg" > "$dir/$name.config-validate.txt" 2>&1
    echo "exit=$?" >> "$dir/$name.config-validate.txt"
    cli "$jar" validate --format json --no-auto-detect --config "$cfg" --mr "$MR_FIXTURE" \
      > "$dir/$name.validate.json" 2> /dev/null
    echo "exit=$?" >> "$dir/$name.validate.json"
    normalize < "$dir/$name.config-validate.txt" > "$dir/$name.config-validate.norm"
    normalize < "$dir/$name.validate.json" > "$dir/$name.validate.norm"
  done
}

# `validate` writes run state (cache/, .karandash/stats) into the working directory; remove what this run created
# so the repo-root guard stays green when gates run back to back.
created=()
for d in cache .karandash; do [ -e "$d" ] || created+=("$d"); done
clean_state() { [ ${#created[@]} -eq 0 ] || rm -rf "${created[@]}"; }
trap clean_state EXIT

rm -rf "$OUT"
run_all "$PREV_JAR" "$OUT/previous"
# the head run must not reuse the previous run's AI state (it would add an ai-incremental "cached" finding)
clean_state
run_all "$HEAD_JAR" "$OUT/head"

if diff -ru -x '*.txt' -x '*.json' "$OUT/previous" "$OUT/head" > "$OUT/compat.diff"; then
  echo "compat-diff: previous release and HEAD agree on all $(echo "$CONFIGS" | wc -l | tr -d ' ') golden configs"
  exit 0
fi
cat "$OUT/compat.diff"
echo "compat-diff: behavior differs from the previous release. Every difference must be listed under" \
  "the CHANGELOG entry for the release; set COMPAT_DIFF_ACCEPTED=true (the release workflow sets it on a real release) once it is."
[ "${COMPAT_DIFF_ACCEPTED:-false}" = "true" ] && exit 0
exit 1
