#!/usr/bin/env bash
# Regression: the CI base image carries a C compiler, and buildBoobyTrapLib fails in seconds with a
# clear message when cc is missing (it used to fail after 25 min with "problem occurred starting process 'command 'cc''").
cd "$(dirname "$0")/../.." || exit 1
grep -qE '^\s+gcc \\' docker/Dockerfile.base || { echo "cc-compiler: gcc missing from docker/Dockerfile.base"; exit 1; }
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
for f in /usr/bin/* /bin/*; do n=${f##*/}; [ "$n" = cc ] || ln -sf "$f" "$D/$n" 2>/dev/null; done
out=$(PATH=$D ./gradlew :plugins:inspector:buildBoobyTrapLib --rerun-tasks -q 2>&1)
printf '%s' "$out" | grep -q "cc not found on PATH" || { echo "cc-compiler: no fail-fast message without cc"; exit 1; }
echo "cc-compiler OK"
