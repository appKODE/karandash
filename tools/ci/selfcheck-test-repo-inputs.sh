#!/usr/bin/env bash
# Regression: a module whose tests read repo files through TestFiles.findRepoRoot declares them in
# gradle.properties `karandash.testRepoInputs`, which karandash.kmp-library turns into test-cache inputs. Without it a cached
# test result survives an edit to those files. test-fixtures defines findRepoRoot; markdown-format reads only its own src/
# (already a cache input); plugins-ai/rules/ai only writes its sizing sweep under build/.
cd "$(dirname "$0")/../.." || exit 1
grep -q 'karandash.testRepoInputs' build-logic/src/main/kotlin/karandash.kmp-library.gradle.kts || { echo "test-repo-inputs: kmp-library no longer reads karandash.testRepoInputs"; exit 1; }
rc=0
for m in $(git grep -l 'findRepoRoot' -- '*.kt' | sed 's#/src/.*##' | sort -u); do
  case "$m" in test-fixtures | plugins/plugins-core/formats/markdown-format | plugins/plugins-ai/rules/ai) continue ;; esac
  # projects/shared-tests is a source dir, not a module: the projects that compile it carry the declaration
  if [ "$m" = projects/shared-tests ]; then
    for c in $(grep -l 'shared-tests/src/' projects/*/build.gradle.kts | xargs -n1 dirname); do
      grep -q '^karandash.testRepoInputs=' "$c/gradle.properties" 2>/dev/null || { echo "test-repo-inputs: $c compiles projects/shared-tests but declares no karandash.testRepoInputs"; rc=1; }
    done
    continue
  fi
  grep -q '^karandash.testRepoInputs=' "$m/gradle.properties" 2>/dev/null || { echo "test-repo-inputs: $m reads repo files in tests but declares no karandash.testRepoInputs"; rc=1; }
done
[ $rc -eq 0 ] && echo "test-repo-inputs OK"
exit $rc
