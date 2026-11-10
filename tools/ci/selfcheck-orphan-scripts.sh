#!/usr/bin/env bash
# Regression: no orphan script (the jlink runtime is wired through the buildCustomJre Gradle task). Every script under tools/scripts, tools/benchmarks,
# tools/jlink-config, tools/integration-tests, docs/deploy.sh and tools/audit/test_*.py is named by a CI workflow
# (.github/workflows), a Gradle task, a gate or the integration run-tests.sh driver; the mock servers, package-hybrid
# and the jlink runtime stay wired; the integration test data is used.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=orphan-scripts
. tools/ci/lib.sh

# Everything that may call a script: CI workflows, the user CI template, Gradle build files, the gates and the integration driver.
callers=$(
    {
        ls .github/workflows/*.yml .gitlab/templates/*.yml
        ls tools/ci/gate.sh tools/ci/gate2.sh tools/integration-tests/run-tests.sh
        find . -name '*.gradle.kts' -not -path '*/build/*' -not -path './.*/*' -not -path '*/node_modules/*'
    } 2>/dev/null
)
corpus=$(echo "$callers" | xargs cat)

# Run by hand inside the docker/Dockerfile.competitor-env image (danger-js, danger-kotlin, reviewdog): no CI job.
manual="tools/benchmarks/benchmark-competitors.sh"
orphans=""
for script in tools/scripts/*.sh tools/benchmarks/*.sh tools/jlink-config/*.sh tools/integration-tests/*.sh \
    docs/deploy.sh tools/audit/test_*.py; do
    case " $manual " in *" $script "*) continue ;; esac
    name=$(basename "$script")
    printf '%s' "$corpus" | grep -qF "$name" || orphans="$orphans $script"
done
[ -z "$orphans" ] || fail "scripts no CI workflow, Gradle task or gate calls:$orphans"

grep -q '^  *\$\[\[ inputs\.' .gitlab/templates/validate.yml 2>/dev/null || grep -q 'inputs\.' .gitlab/templates/validate.yml \
    || fail "validate.yml does not use its spec inputs"
grep -q '^spec:' .gitlab/templates/validate.yml || fail "validate.yml has no spec: header"

# already wired, pinned.
grep -q 'mock-gitlab-api.py' tools/ci/gate.sh || fail "the mock server is not passed to the integration scripts by gate.sh"
grep -q 'package-hybrid.sh' build.gradle.kts || fail "package-hybrid.sh lost its buildHybrid task"
grep -q 'build-jre.sh' build.gradle.kts || fail "jlink build-jre.sh lost its buildCustomJre task"
grep -q 'required-modules.txt' tools/jlink-config/build-jre.sh || fail "build-jre.sh does not read required-modules.txt"

# test-data is read by the quick-verify run.
for data in sample-mr.json test-config.kts; do
    grep -q "$data" tools/integration-tests/quick-verify.sh || fail "tools/integration-tests/test-data/$data is not used by quick-verify.sh"
done
echo "orphan-scripts OK"
