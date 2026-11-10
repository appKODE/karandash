#!/usr/bin/env bash
# Weekly digest: download the diagnostics/diagnostic.json artifact of every changes-validation job of the last N days
# from one or more GitLab projects (read-only, through `glab api`), then run `karandash stats --diagnostics` on them.
#
# Usage: tools/digest/collect-diagnostics.sh -p group/project [-p group/other] [-d 7] [-j changes-validation]
#                                            [-o build/digest] [-f text|markdown] [-k "karandash"]
#   -p  project path or id (repeatable, required)   -d  days back (default 7)
#   -j  job name (default changes-validation)        -o  download directory (default build/digest)
#   -f  digest format (default text)                 -k  karandash command (default: karandash on PATH)
# Needs glab (logged in to the GitLab host) and jq. Writes only under the download directory.
set -euo pipefail

PROJECTS=()
DAYS=7
JOB=changes-validation
OUT=build/digest
FORMAT=text
KARANDASH=karandash
while getopts "p:d:j:o:f:k:h" opt; do
  case $opt in
    p) PROJECTS+=("$OPTARG") ;;
    d) DAYS=$OPTARG ;;
    j) JOB=$OPTARG ;;
    o) OUT=$OPTARG ;;
    f) FORMAT=$OPTARG ;;
    k) KARANDASH=$OPTARG ;;
    *) sed -n '2,10p' "$0"; exit 2 ;;
  esac
done
[ ${#PROJECTS[@]} -gt 0 ] || { sed -n '2,10p' "$0"; exit 2; }
command -v glab >/dev/null || { echo "glab not found" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq not found" >&2; exit 2; }

# ISO date N days ago (BSD date on macOS, GNU date elsewhere)
SINCE=$(date -u -v-"${DAYS}"d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "$DAYS days ago" +%Y-%m-%dT%H:%M:%SZ)
mkdir -p "$OUT"
TOTAL=0
for project in "${PROJECTS[@]}"; do
  enc=$(jq -rn --arg p "$project" '$p | @uri')
  dir="$OUT/$(echo "$project" | tr '/' '_')"
  mkdir -p "$dir"
  page=1
  while :; do
    # newest first; stop paging once a page reaches past SINCE
    jobs=$(glab api "projects/$enc/jobs?scope[]=success&scope[]=failed&per_page=100&page=$page")
    [ "$(echo "$jobs" | jq length)" -gt 0 ] || break
    for id in $(echo "$jobs" | jq -r --arg job "$JOB" --arg since "$SINCE" \
        '.[] | select(.name == $job and .created_at >= $since and (.artifacts // [] | length) > 0) | .id'); do
      file="$dir/$id.json"
      [ -s "$file" ] && continue
      if glab api "projects/$enc/jobs/$id/artifacts/diagnostics/diagnostic.json" > "$file.part" 2>/dev/null; then
        mv "$file.part" "$file"
        TOTAL=$((TOTAL + 1))
      else
        rm -f "$file.part"
      fi
    done
    oldest=$(echo "$jobs" | jq -r '.[-1].created_at')
    [[ "$oldest" < "$SINCE" ]] && break
    page=$((page + 1))
  done
done
echo "Downloaded $TOTAL new diagnostic.json file(s) into $OUT (jobs '$JOB' since $SINCE)" >&2
$KARANDASH stats --diagnostics "$OUT" --format "$FORMAT"
