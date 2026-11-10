#!/usr/bin/env bash
# Regression: act-style smoke run of action.yml. The composite action only calls
# tools/action/install.sh and tools/action/validate.sh with INPUT_* env vars and reads $GITHUB_OUTPUT, so this runs
# the same two scripts the same way (no runner needed): install from a file:// release (checksum-verified), then validate the
# fixture MR with a failing and a passing config and check passed / findings-count / sarif-file / reviewdog-file.
# Env: KARANDASH_NATIVE (native binary) or cli/build/libs/karandash.jar (needs java).
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=action-yml
. tools/ci/lib.sh
command -v jq > /dev/null || fail "jq is required"
ROOT=$(pwd)
FIXTURE="$ROOT/tools/integration-tests/fixtures/test-mr.json"
if [ -n "${KARANDASH_NATIVE:-}" ]; then
  case "$KARANDASH_NATIVE" in /*) BIN="$KARANDASH_NATIVE" ;; *) BIN="$ROOT/$KARANDASH_NATIVE" ;; esac
else
  # the CI job gets the jar from its build-fatjar need (artifacts: true)
  [ -f "$ROOT/cli/build/libs/karandash.jar" ] ||
    fail "cli/build/libs/karandash.jar is missing: build it (./gradlew :cli:shadowJar) or give this job needs: build-fatjar with artifacts"
  BIN="java -jar $ROOT/cli/build/libs/karandash.jar"
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work" || exit 1

# 1. install.sh downloads karandash.jar + SHA256SUMS from <base>/<tag>/ and resolves "latest" via <api>/releases/latest
mkdir -p release/v0.0.0 release/v6.6.6 api/releases
echo "jar-bytes" > release/v0.0.0/karandash.jar
(cd release/v0.0.0 && sha256sum karandash.jar 2> /dev/null || shasum -a 256 karandash.jar) > release/v0.0.0/SHA256SUMS
echo "tampered" > release/v6.6.6/karandash.jar
cp release/v0.0.0/SHA256SUMS release/v6.6.6/SHA256SUMS
printf '{\n  "url": "x",\n  "tag_name": "v0.0.0",\n  "name": "v0.0.0"\n}\n' > api/releases/latest
fetch() { # version
  : > out-install
  rm -f karandash.jar
  KARANDASH_RELEASE_BASE="file://$work/release" KARANDASH_RELEASE_API="file://$work/api" INPUT_VERSION="$1" \
    GITHUB_OUTPUT="$work/out-install" bash "$ROOT/tools/action/install.sh" > /dev/null 2>&1
}
fetch v0.0.0 || fail "install.sh failed"
[ "$(cat karandash.jar)" = "jar-bytes" ] || fail "install.sh did not download karandash.jar"
grep -qx "version=v0.0.0" out-install || fail "install.sh did not output the version"
fetch latest || fail "install.sh could not resolve latest"
grep -qx "version=v0.0.0" out-install || fail "install.sh resolved latest to the wrong tag"
fetch v9.9.9 && fail "install.sh accepted a version that is not released"
fetch v6.6.6 && fail "install.sh accepted a jar whose checksum does not match SHA256SUMS"
[ -f karandash.jar ] && fail "install.sh left a jar with a bad checksum behind"
rm -f karandash.jar

# 2. validate.sh against the fixture MR; the wrapper adds what a PR event would supply
cat > karandash-wrapper.sh <<WRAP
#!/usr/bin/env bash
exec $BIN "\$@" --mr "$FIXTURE" --no-auto-detect
WRAP
chmod +x karandash-wrapper.sh
printf 'rules:\n  title-format:\n    pattern: "^NOPE-"\n' > failing.yaml
printf 'rules:\n  title-format:\n    pattern: ".*"\n' > passing.yaml

run() { # config, expected exit
  : > github-output
  rm -f karandash-results.*
  KARANDASH_CMD="$work/karandash-wrapper.sh" INPUT_TOKEN=dummy INPUT_CONFIG="$1" INPUT_FORMAT=console INPUT_SARIF=true \
    INPUT_REVIEWDOG_FORMAT=rdjsonl GITHUB_OUTPUT="$work/github-output" bash "$ROOT/tools/action/validate.sh" > log 2>&1
  code=$?
  [ "$code" -eq "$2" ] || { cat log; fail "$1: exit code $code, expected $2"; }
}
out() { sed -n "s/^$1=//p" github-output; }

run failing.yaml 1
[ "$(out passed)" = "false" ] || fail "failing config: passed=$(out passed)"
count=$(jq '.findings | length' karandash-results.json)
[ "$count" -gt 0 ] || fail "failing config produced no findings"
[ "$(out findings_count)" = "$count" ] || fail "findings_count=$(out findings_count), json has $count"
[ "$(out sarif-file)" = "karandash-results.sarif" ] && jq -e '.version == "2.1.0"' karandash-results.sarif > /dev/null ||
  fail "sarif-file output or SARIF content wrong"
[ "$(out reviewdog-enabled)" = "true" ] && [ -s "$(out reviewdog-file)" ] || fail "reviewdog output wrong"

run passing.yaml 0
[ "$(out passed)" = "true" ] || fail "passing config: passed=$(out passed)"
[ "$(out findings_count)" = "$(jq '.findings | length' karandash-results.json)" ] || fail "passing config: findings_count differs from json"
echo "action-yml OK"
