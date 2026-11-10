#!/usr/bin/env bash
#
# Karandash integration test suite: a short CLI smoke test, then every integration script in this
# directory. Each script prints its own pass/fail/skip detail; this runner fails if any of them fails.
#
# Environment (all optional; defaults are the in-repo build outputs):
#   KARANDASH_JAR     CLI fat JAR (./gradlew :cli:shadowJar)
#   KARANDASH_NATIVE  CLI native binary for the host (./gradlew :cli:linkReleaseExecutable<Target>)
#   TEST_MR, FIXTURES_DIR, MOCK_SERVER   fixtures for plugin-examples-test.sh
#   SKIP_SDK_PUBLISH  1 = the plugin SDK is already in mavenLocal
#   KARANDASH_BRIDGE_JAR  JAR bridge for native-runtime JAR plugins (built when unset)
#   CI                true = a skipped check (missing toolchain or binary) is a failure
#
set -uo pipefail

# The CLI under test must not auto-detect the CI job's own MR and post to it. CI itself stays: the scripts read it.
# Scenarios that need a platform's CI vars (e.g. Jenkins in e2e.d/platforms-b.sh) set them per run.
for var in $(compgen -e); do
    case "$var" in
        GITLAB_CI | CI_* | GITHUB_* | BITBUCKET_* | TF_BUILD | SYSTEM_* | AZURE_HTTP_USER_AGENT | JENKINS_URL | CHANGE_URL | \
            GERRIT_* | GITEA_* | CODEBUILD_*) unset "$var" ;;
    esac
done

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_DIR/../.." && pwd)"

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) NATIVE_TARGET=macosArm64 ;;
    Darwin-x86_64) NATIVE_TARGET=macosX64 ;;
    *) NATIVE_TARGET=linuxX64 ;;
esac
export KARANDASH_JAR="${KARANDASH_JAR:-$PROJECT_ROOT/cli/build/libs/karandash.jar}"
export KARANDASH_NATIVE="${KARANDASH_NATIVE:-$PROJECT_ROOT/cli/build/bin/$NATIVE_TARGET/releaseExecutable/cli.kexe}"
export TEST_MR="${TEST_MR:-$TEST_DIR/fixtures/test-mr.json}"
export FIXTURES_DIR="${FIXTURES_DIR:-$TEST_DIR/fixtures}"
export MOCK_SERVER="${MOCK_SERVER:-$TEST_DIR/mock-server/mock-gitlab-api.py}"

PASSED=()
FAILED=()

pass() { PASSED+=("$1"); echo "PASS: $1"; }
fail() { FAILED+=("$1"); echo "FAIL: $1"; }

# k <args>: the JVM CLI with an isolated home
SMOKE_HOME="$(mktemp -d)"
trap 'rm -rf "$SMOKE_HOME"' EXIT
k() { HOME="$SMOKE_HOME" java -Duser.home="$SMOKE_HOME" -jar "$KARANDASH_JAR" "$@" 2>&1; }

smoke() {
    echo "=== CLI smoke ==="
    if [[ ! -f "$KARANDASH_JAR" ]]; then
        fail "smoke: $KARANDASH_JAR not found (./gradlew :cli:shadowJar)"
        return
    fi
    if k --version | grep -Eq '[0-9]+\.[0-9]+\.[0-9]+'; then pass "smoke: --version prints a semver"; else fail "smoke: --version"; fi
    if k --help | grep -q "validate" && k plugin --help | grep -q "create"; then pass "smoke: --help lists commands"; else fail "smoke: --help"; fi
    if k plugin list | grep -q "No plugins installed"; then pass "smoke: plugin list on an empty home"; else fail "smoke: plugin list"; fi
}

# run_script <script> [args]: one integration script, in a subshell so its cwd changes stay local
run_script() {
    local name="$1"
    shift
    echo ""
    echo "=== $name $* ==="
    if (cd "$PROJECT_ROOT" && bash "$TEST_DIR/$name" "$@"); then pass "$name"; else fail "$name"; fi
}

smoke
if [[ "${SKIP_SDK_PUBLISH:-}" != "1" ]]; then
    echo "=== Publishing the plugin SDK to mavenLocal ==="
    if "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" --no-configuration-cache \
        --init-script "$PROJECT_ROOT/tools/ci/extensibility-publish.init.gradle.kts" \
        publishSdkToMavenLocal --console=plain -q; then
        export SKIP_SDK_PUBLISH=1
    else
        fail "publishSdkToMavenLocal"
    fi
fi
# The native CLI runs JAR plugins through the JAR bridge subprocess
if [[ -z "${KARANDASH_BRIDGE_JAR:-}" ]]; then
    "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" :plugins:loader:jar-bridge:shadowJar --console=plain -q || fail "jar-bridge shadowJar"
    KARANDASH_BRIDGE_JAR="$PROJECT_ROOT/plugins/loader/jar-bridge/build/libs/karandash-jar-bridge.jar"
    export KARANDASH_BRIDGE_JAR
fi
run_script quick-verify.sh
run_script plugin-create-test.sh
run_script plugin-lifecycle-test.sh
run_script script-module-test.sh
run_script native-plugin-test.sh
run_script multi-language-plugin-test.sh
run_script plugin-examples-test.sh "$PROJECT_ROOT/tools/examples"
run_script feature-e2e.sh

echo ""
echo "=== Summary: ${#PASSED[@]} passed, ${#FAILED[@]} failed ==="
for f in ${FAILED[@]+"${FAILED[@]}"}; do echo "  FAIL: $f"; done
[[ ${#FAILED[@]} -eq 0 ]]
