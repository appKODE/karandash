#!/usr/bin/env bash
# Gate part 2: plugin isolation, extensibility, dokka, mkdocs, compat-diff.
# Usage: KARANDASH_PREV_JAR=/path/to/previous-release/karandash.jar tools/ci/gate2.sh
# Run gate.sh first (needs cli/build/libs/karandash.jar). Without KARANDASH_PREV_JAR the compat-diff step is skipped.
# Logs per step in $GATE_LOG_DIR (default build/gate-logs). Exits non-zero if any step failed.
cd "$(dirname "$0")/../.." || exit 1
L=${GATE_LOG_DIR:-build/gate-logs}
mkdir -p "$L"
FAILED=0
. tools/ci/lib.sh
step ISO iso bash tools/ci/verify-plugin-isolation.sh
SKIP_PATH_GUARD=1 step EXT ext bash tools/ci/verify-extensibility.sh
# Gradle-running regression self-checks: jar-bridge shadowJar, SDK publish, examples harness
step JARBRIDGE jarbridge bash tools/ci/selfcheck-jar-bridge.sh
step SDKPUBLISH sdkpublish bash tools/ci/selfcheck-sdk-publish.sh
step PLUGINEX pluginex bash tools/ci/selfcheck-plugin-examples.sh
# no orphan script
step ORPHANS orphans bash tools/ci/selfcheck-orphan-scripts.sh
# the projects/* clones share their tests
step SHAREDTESTS sharedtests bash tools/ci/selfcheck-shared-project-tests.sh
# the dead build pieces are wired
step BUILDWIRING buildwiring bash tools/ci/selfcheck-build-wiring.sh
# The build-wiring, orphan-script and shared-test detectors reject a planted violation
step DETNEG detneg bash tools/ci/selfcheck-detectors-negative.sh
step INITSCRIPT initscript bash tools/ci/selfcheck-init-script.sh
# Consumer guard: review-bot base/base-kmp chains and the six app configs still load with the same rules and patterns
step CONSUMERS consumers ./gradlew --max-workers=3 :cli:jvmTest --tests 'io.karandash.cli.consumers.*'
step DOKKA dok ./gradlew :plugins:sdk:dokkaGenerate :core:api:dokkaGenerate
[ -d "$L/venv" ] || python3 -m venv "$L/venv"
step PIP pip "$L/venv/bin/pip" install -q -r docs/requirements.txt
step MKDOCS mk "$L/venv/bin/mkdocs" build --strict -d "$L/site"
if [ -n "${KARANDASH_PREV_JAR:-}" ]; then
  rm -rf "$L/compat" # one compat-diff per out dir; a shared dir gave a false pass
  step COMPAT compat bash tools/ci/compat-diff.sh "$KARANDASH_PREV_JAR" cli/build/libs/karandash.jar "$L/compat"
else
  echo "COMPAT skipped (set KARANDASH_PREV_JAR)"
fi
[ $FAILED -eq 0 ] && echo "GATE2 OK" || echo "GATE2 FAILED: $FAILED_STEPS (logs in $L)"
exit $FAILED
