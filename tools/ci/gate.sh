#!/usr/bin/env bash
# Gate part 1: format, static analysis, JVM tests + kover floors, static checks, native link + smoke, benchmark regression check, integration scripts.
# Usage: tools/ci/gate.sh   (run from anywhere; logs per step in $GATE_LOG_DIR, default build/gate-logs)
# Set EM_NODE_JS to a node binary for the JS-plugin integration tests. Exits non-zero if any step failed.
cd "$(dirname "$0")/../.." || exit 1
L=${GATE_LOG_DIR:-build/gate-logs}
mkdir -p "$L"
FAILED=0
# step NAME LOGNAME CMD... : run CMD into $L/LOGNAME.log, print "NAME rc", remember failure
. tools/ci/lib.sh
step_summary() { grep -E "^> Task .*FAILED|^e: |FAIL|BUILD FAILED" "$1" | head -10; }
step FMT g0 ./gradlew ktlintFormat
echo "  format touched $(git status --short | wc -l | tr -d ' ') paths"
step STATIC gstatic ./gradlew apiCheck ktlintCheck detekt compileCommonMainKotlinMetadata --continue
step JVM g1 ./gradlew jvmTest :dsl:lsp-server:test :tools:detekt-rules:test :tools:cache-server:test \
  :plugins:integration-tests:contracts:contractTest :plugins:loader:wasmExamplesTest \
  koverXmlReport koverHtmlReport koverVerify --continue
# koverVerify (per-module line floors) runs in the JVM step, as in CI test-jvm; this only checks a test file exists
step TESTFILES gcov bash tools/ci/verify-test-coverage.sh
# static regression self-checks (the jar-bridge one without its build); the Gradle-running ones are in gate2.sh
step SELFCHECK gself bash -c 'bash tools/ci/selfcheck-datetime-deps.sh && bash tools/ci/selfcheck-release-natives.sh && bash tools/ci/selfcheck-test-repo-inputs.sh && bash tools/ci/selfcheck-ci-jobs.sh && bash tools/ci/selfcheck-root-clean.sh && bash tools/ci/selfcheck-example-binaries.sh && bash tools/ci/selfcheck-init-script.sh && SELFCHECK_STATIC=1 bash tools/ci/selfcheck-jar-bridge.sh'
# native tests + release link for the host: macosArm64 on a Mac, linuxX64 elsewhere (the GitHub Actions Linux runner)
case "$(uname -s)" in
  Darwin) NATIVE_TARGET=macosArm64 NATIVE_LINK=:cli:linkReleaseExecutableMacosArm64 ;;
  *) NATIVE_TARGET=linuxX64 NATIVE_LINK=:cli:linkReleaseExecutableLinuxX64 ;;
esac
# one Gradle run per piece: in CI (no daemon) the release link then starts on an empty heap and fits a 7.7 GB runner
step NATIVE g2 bash -c 'r=0; for t in "$1" "$2" :cli:shadowJar; do ./gradlew "$t" --continue || r=1; done; exit $r' _ \
  "${NATIVE_TARGET}Test" "$NATIVE_LINK"
KEXE=cli/build/bin/$NATIVE_TARGET/releaseExecutable/cli.kexe
step SMOKE gsmoke "$KEXE" --version
export KARANDASH_JAR=cli/build/libs/karandash.jar KARANDASH_NATIVE=$KEXE \
  TEST_MR=tools/integration-tests/fixtures/test-mr.json FIXTURES_DIR=tools/integration-tests/fixtures \
  MOCK_SERVER=tools/integration-tests/mock-server/mock-gitlab-api.py
step BENCH gbench bash tools/benchmarks/benchmark-regression-check.sh "$KEXE"
step BENCHJAR gbenchjar env BENCH_VARIANT=jar bash tools/benchmarks/benchmark-regression-check.sh "java -jar $KARANDASH_JAR"
grep -hE "^(OK|REGRESSION|FAILED)" "$L/gbench.log" "$L/gbenchjar.log" 2>/dev/null
step INTEG git bash tools/integration-tests/run-tests.sh
grep -E "PASS|FAIL|SKIP|passed|failed" "$L/git.log" 2>/dev/null | tail -20
# the only place the project scenario tests are forced to run (--rerun in the script), so UP-TO-DATE in the JVM step cannot hide them
step SCENARIOS gscen bash -c 'for p in base base-kmp coinbox gridmon ledger navigator telecom wallet; do bash projects/$p/tests/run-tests.sh || exit 1; done'
[ $FAILED -eq 0 ] && echo "GATE OK" || echo "GATE FAILED: $FAILED_STEPS (logs in $L)"
exit $FAILED
