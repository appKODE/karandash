#!/usr/bin/env bash
# Regression: the root-clean guard passes on a clean tree and names each kind of stray.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX="root-clean selfcheck"
. tools/ci/lib.sh
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ci4.XXXXXX") || exit 1
trap 'rm -rf "$tmp"' EXIT
bash tools/ci/check-repo-root-clean.sh "$tmp" >/dev/null || fail "clean tree reported dirty"
mkdir "$tmp/cache"
bash tools/ci/check-repo-root-clean.sh "$tmp" >/dev/null && fail "root cache/ not detected"
rmdir "$tmp/cache"
mkdir -p "$tmp/mod/.karandash"
echo '{}' > "$tmp/mod/.karandash/statistics.json"
bash tools/ci/check-repo-root-clean.sh "$tmp" >/dev/null && fail "statistics.json not detected"
bash tools/ci/check-repo-root-clean.sh >/dev/null || fail "this checkout has stray test output"
echo "root-clean selfcheck OK"
