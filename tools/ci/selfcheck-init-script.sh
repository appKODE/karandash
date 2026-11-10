#!/usr/bin/env bash
# Regression: tools/ci/extensibility-publish.init.gradle.kts compiles under the repo's Gradle
# and under Gradle 8.5 (the version of tools/examples/standalone-kotlin-plugin, where `ProjectDependency.path` was an
# unresolved reference: job 2600543). The script is evaluated against an empty throwaway build with `help`.
cd "$(dirname "$0")/../.." || exit 1
INIT=$PWD/tools/ci/extensibility-publish.init.gradle.kts
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
echo 'rootProject.name = "initscript-probe"' > "$D/settings.gradle.kts"
GRADLE85=${GRADLE85:-$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.5-bin/*/gradle-8.5/bin/gradle 2>/dev/null | head -1)}
fail() { echo "init-script-gradle85: $1"; printf '%s\n' "$2" | tail -15; exit 1; }
out=$(./gradlew -p "$D" --init-script "$INIT" help --offline -q 2>&1) || fail "does not compile under the repo Gradle" "$out"
if [ -n "$GRADLE85" ]; then
  out=$("$GRADLE85" -p "$D" --init-script "$INIT" help --offline -q 2>&1) || fail "does not compile under Gradle 8.5" "$out"
elif [ -n "$CI" ]; then
  fail "Gradle 8.5 distribution not found (set GRADLE85)" ""
else
  echo "init-script-gradle85: Gradle 8.5 not installed locally, only the repo Gradle was checked"
fi
echo "init-script-gradle85 OK"
