#!/usr/bin/env bash
# A test run must not leave cache/, .karandash/ or */.karandash/statistics.json in the checkout.
# Usage: check-repo-root-clean.sh [root]   (defaults to the repo root; exit 1 and list the strays when found)
root="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
strays=""
for d in cache .karandash; do
    [ -e "$root/$d" ] && strays="$strays $d"
done
found=$(find "$root" -path "$root/build" -prune -o -path '*/node_modules' -prune -o -path '*/.gradle' -prune -o \
    -path '*/build' -prune -o -name statistics.json -path '*/.karandash/*' -print 2>/dev/null)
[ -z "$found" ] || strays="$strays $found"
if [ -n "$strays" ]; then
    echo "stray test output in the checkout:$strays"
    exit 1
fi
echo "OK"
