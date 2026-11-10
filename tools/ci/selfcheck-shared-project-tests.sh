#!/usr/bin/env bash
# Regression: the projects/* clones share their tests. The per-project copies of the config,
# remote-extends and requirements suites live once in projects/shared-tests; every project module compiles the
# real-config-chain test from there and carries its own ProjectSpec.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=shared-project-tests
. tools/ci/lib.sh

shared=projects/shared-tests/src
for f in commonTest/kotlin/io/karandash/validators/ProjectConfigIntegrationTest.kt \
    commonTest/kotlin/io/karandash/validators/ProjectRemoteExtendsTest.kt \
    requirementsTest/kotlin/io/karandash/validators/ProjectRequirementsIntegrationTest.kt \
    realChainTest/kotlin/io/karandash/validators/ProjectRealConfigChainTest.kt; do
    [ -f "$shared/$f" ] || fail "missing shared suite $shared/$f"
done

for module in projects/*/build.gradle.kts; do
    dir=$(dirname "$module")
    name=$(basename "$dir")
    [ -f "$dir/src/commonTest/kotlin/io/karandash/validators/ProjectSpec.kt" ] || fail "$name has no ProjectSpec.kt"
    grep -q 'shared-tests/src/realChainTest/kotlin' "$module" || fail "$name does not compile the shared real-config-chain test"
done

# The four clones run the shared config and remote-extends suites instead of keeping a copy each.
for name in coinbox gridmon ledger telecom; do
    grep -q 'shared-tests/src/commonTest/kotlin' "projects/$name/build.gradle.kts" || fail "$name does not use the shared suites"
    copies=$(ls "projects/$name/src/commonTest/kotlin/io/karandash/validators/" | grep -E 'ConfigIntegrationTest|RemoteExtendsTest' || true)
    [ -z "$copies" ] || fail "$name keeps its own copy of a shared suite: $copies"
done
for name in coinbox gridmon ledger; do
    [ ! -e "projects/$name/src/commonTest/kotlin/io/karandash/validators/${name}RequirementsIntegrationTest.kt" ] || fail "$name keeps its own requirements copy"
done

# no stray `~` or nested `projects` directory is tracked under a project.
stray=$(git ls-files projects | grep -E '^projects/[^/]+/(~|projects)/' || true)
[ -z "$stray" ] || fail "stray directories tracked under projects: $stray"
echo "shared-project-tests OK"
