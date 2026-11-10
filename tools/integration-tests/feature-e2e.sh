#!/usr/bin/env bash
#
# Feature end-to-end scenarios: every scenario id in docs/development/feature-matrix.md, run on the JVM fat JAR
# and on the host native binary (cli.kexe) against the mock GitLab server. Each scenario asserts the exit code and
# a key piece of output; live-mode scenarios also assert the writes the mock server received.
#
# Environment (all optional; defaults are the in-repo build outputs):
#   KARANDASH_JAR     CLI fat JAR (./gradlew :cli:shadowJar)
#   KARANDASH_NATIVE  CLI native binary for the host (./gradlew :cli:linkReleaseExecutable<Target>)
#   E2E_ONLY          run only the scenarios whose id matches this extended regex
#   CI                true = a missing binary or tool (node, openssl) is a failure, not a skip
#
set -uo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_DIR/../.." && pwd)"

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) NATIVE_TARGET=macosArm64 ;;
    Darwin-x86_64) NATIVE_TARGET=macosX64 ;;
    *) NATIVE_TARGET=linuxX64 ;;
esac
JAR="${KARANDASH_JAR:-$PROJECT_ROOT/cli/build/libs/karandash.jar}"
NATIVE="${KARANDASH_NATIVE:-$PROJECT_ROOT/cli/build/bin/$NATIVE_TARGET/releaseExecutable/cli.kexe}"
# Scenarios run from temp dirs, so a caller's relative path must be anchored to the repo root.
case "$JAR" in /*) ;; *) JAR="$PROJECT_ROOT/$JAR" ;; esac
case "$NATIVE" in /*) ;; *) NATIVE="$PROJECT_ROOT/$NATIVE" ;; esac
FIXTURES="$TEST_DIR/fixtures"
MOCK_SERVER="$TEST_DIR/mock-server/mock-gitlab-api.py"
ONLY="${E2E_ONLY:-}"

WORK="$(mktemp -d)"
MOCK_PID=""
cleanup() {
    [[ -n "$MOCK_PID" ]] && { kill "$MOCK_PID"; wait "$MOCK_PID"; } 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

PASS=0
FAIL=0
SKIP=0
FAILED_IDS=()
RUNTIME=""
SCEN_ENV=()
SCEN_DIR=""
SCEN_STDIN=""

# ── Mock server ──────────────────────────────────────────────────────────────

F="$WORK/f"
mkdir -p "$F"
# GitHub App key pair: the CLI signs its app JWT with the private key, the mock verifies it with the public one
command -v openssl >/dev/null 2>&1 && {
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$F/gh-app.pem" 2>/dev/null
    openssl pkey -in "$F/gh-app.pem" -pubout -out "$WORK/gh-app.pub" 2>/dev/null
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$F/gh-other.pem" 2>/dev/null
}

PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
REQUESTS="$WORK/requests.log"
LAST_OUT=""
BODIES="$WORK/bodies.log"
mkdir -p "$WORK/files"
MOCK_REQUEST_LOG="$REQUESTS" MOCK_BODY_LOG="$BODIES" MOCK_FILES_DIR="$WORK/files" MOCK_GITHUB_APP_PUBKEY="$WORK/gh-app.pub" python3 "$MOCK_SERVER" "$PORT" >"$WORK/mock.log" 2>&1 &
MOCK_PID=$!
for _ in $(seq 1 50); do
    curl -sf "http://localhost:$PORT/api/v4/projects/1/merge_requests/1" >/dev/null 2>&1 && break
    python3 -c 'import time; time.sleep(0.2)'
done
if ! curl -sf "http://localhost:$PORT/api/v4/projects/1/merge_requests/1" >/dev/null 2>&1; then
    echo "FAIL: mock GitLab server did not start on port $PORT" >&2
    cat "$WORK/mock.log" >&2
    exit 1
fi
MR_URL="http://localhost:$PORT/test/project/-/merge_requests/1"

# ── Fixtures ─────────────────────────────────────────────────────────────────
# The fixture MR (fixtures/test-mr.json) is titled "feat: add runtime verification" and labelled "integration";
# the mock MR is titled "feat: add runtime verification for plugins". title-format patterns match the whole title.

mkdir -p "$F/zero" "$F/doctor/.karandash" "$F/gh-doctor/.karandash"
cp "$FIXTURES/test-mr.json" "$F/mr.json"
cp "$FIXTURES/test-mr.json" "$F/zero/mr.json"

cat >"$F/pass.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^(feat|fix): .+"
reporters:
  console: true
EOF
cat >"$F/fail.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^fix: .+"
EOF
printf '{"rules": {"titleFormat": {"pattern": "^(feat|fix): .+"}}}\n' >"$F/pass.json"
cat >"$F/fail.kts" <<'EOF'
karandash {
    rules {
        titleFormat {
            pattern = "^fix: .+"
        }
    }
}
EOF
cat >"$F/base.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^fix: .+"
    max-length: 10
EOF
cat >"$F/child.yaml" <<'EOF'
extends: "base.yaml"
rules:
  title-format:
    pattern: "^feat: .+"
EOF
cp "$F/base.yaml" "$WORK/files/base.yaml"
cat >"$F/remote-child.yaml" <<EOF
allow_localhost: true
extends: "http://localhost:$PORT/files/base.yaml"
rules:
  title-format:
    pattern: "^feat: .+"
EOF
printf '^(feat|fix): .+' >"$F/pattern.txt"
cat >"$F/fileref.yaml" <<'EOF'
rules:
  title-format:
    pattern: { file: "pattern.txt" }
EOF
cat >"$F/env.yaml" <<'EOF'
rules:
  title-format:
    pattern: "${E2E_TITLE_PATTERN}"
EOF
cat >"$F/deny.yaml" <<'EOF'
rules:
  title-format:
    pattern: "${HOME}"
EOF
cat >"$F/snake.yaml" <<'EOF'
rules:
  title_format:
    max_length: 10
EOF
cat >"$F/profile.yaml" <<'EOF'
profile: minimal
EOF
cat >"$F/policy.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^fix: .+"
policies:
  - when: 'label_exists("integration")'
    severity: warning
EOF
cat >"$F/policy-off.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^fix: .+"
policies:
  - when: 'label_exists("integration") && !is_draft'
    modules: [title-format]
    enabled: false
EOF
cat >"$F/live.yaml" <<EOF
allow_localhost: true
platforms:
  gitlab:
    url: "http://localhost:$PORT"
    token: "test-token"
rules:
  title-format:
    pattern: "^fix: .+"
  labels:
    requiredLabels: ["reviewed"]
  approval:
    min-approvals: 1
reporters:
  console: true
EOF
cat >"$F/live-pass.yaml" <<EOF
allow_localhost: true
platforms:
  gitlab:
    url: "http://localhost:$PORT"
    token: "test-token"
rules:
  title-format:
    pattern: "^feat: .+"
EOF
cp "$F/live.yaml" "$F/doctor/.karandash/config.yaml"
# GitHub against the mock's GitHub Enterprise layout (/api/v3): a personal token, and a GitHub App installation
cat >"$F/gh-token.yaml" <<EOF
allow_localhost: true
platforms:
  github:
    url: "http://localhost:$PORT/api/v3"
    token: "test-token"
rules:
  title-format:
    pattern: "^feat: .+"
EOF
gh_app_config() {
    cat <<EOF
allow_localhost: true
platforms:
  github:
    url: "http://localhost:$PORT/api/v3"
    auth:
      type: app
      appId: "1234"
      installationId: "42"
      privateKey: { file: "$1" }
rules:
  title-format:
    pattern: "^feat: .+"
EOF
}
gh_app_config gh-app.pem >"$F/gh-app.yaml"
gh_app_config gh-other.pem >"$F/gh-app-wrong-key.yaml"
# a { file: } reference must stay under the config file's directory
[[ -f "$F/gh-app.pem" ]] && cp "$F/gh-app.pem" "$F/gh-doctor/.karandash/gh-app.pem"
gh_app_config gh-app.pem >"$F/gh-doctor/.karandash/config.yaml"
# A Dangerfile next to the config runs through node and adds its warn() as a finding
mkdir -p "$F/danger"
printf 'rules:\n  title-format:\n    pattern: "^feat: .+"\n' >"$F/danger/config.yaml"
printf "if (danger.git.modified_files.length > 0) warn('modified: ' + danger.git.modified_files[0]);\n" >"$F/danger/Dangerfile.js"
# LSP: initialize, shutdown, exit, framed as JSON-RPC over stdio
lsp_frame() { printf 'Content-Length: %d\r\n\r\n%s' "${#1}" "$1"; }
{
    lsp_frame '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}'
    lsp_frame '{"jsonrpc":"2.0","id":2,"method":"shutdown"}'
    lsp_frame '{"jsonrpc":"2.0","method":"exit"}'
} >"$F/lsp.in"
# LSP on an open config.kts: completion inside `rules {}` lists the rule ids, hover on `titleFormat` shows its docs
LSP_URI='file:///e2e/.karandash/config.kts'
{
    lsp_frame '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}'
    lsp_frame '{"jsonrpc":"2.0","method":"initialized","params":{}}'
    lsp_frame '{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"'"$LSP_URI"'","languageId":"kotlin","version":1,"text":"rules {\n    titleFormat {\n        pattern = \"^feat: .+\"\n    }\n    \n}\n"}}}'
    lsp_frame '{"jsonrpc":"2.0","id":2,"method":"textDocument/completion","params":{"textDocument":{"uri":"'"$LSP_URI"'"},"position":{"line":4,"character":4}}}'
    lsp_frame '{"jsonrpc":"2.0","id":3,"method":"textDocument/hover","params":{"textDocument":{"uri":"'"$LSP_URI"'"},"position":{"line":1,"character":6}}}'
    lsp_frame '{"jsonrpc":"2.0","id":4,"method":"shutdown"}'
    lsp_frame '{"jsonrpc":"2.0","method":"exit"}'
} >"$F/lsp-ide.in"
# Plugin signing takes an RSA DER key (PKCS#8 here; PKCS#1 works too); any file stands in for the plugin JAR
printf 'not a real plugin' >"$F/plugin.jar"
# (OpenSSL 3 `genpkey -outform DER` writes PKCS#1, so convert explicitly)
command -v openssl >/dev/null 2>&1 && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
    | openssl pkcs8 -topk8 -nocrypt -outform DER -out "$F/sign-key.der"
[[ -f "$F/sign-key.der" ]] && openssl pkey -inform DER -in "$F/sign-key.der" -pubout -outform DER -out "$F/sign-pub.der" 2>/dev/null

# Per-area fixtures and scenarios: each e2e.d/<area>.sh sets up its fixtures when sourced here and defines
# scenarios_<area>, which run_scenarios calls after the core scenarios.
for frag in "$TEST_DIR"/e2e.d/*.sh; do
    [[ -e "$frag" ]] || continue
    # shellcheck source=/dev/null
    source "$frag"
done

# ── Runner ───────────────────────────────────────────────────────────────────

# run_k <args>: the CLI under test with an isolated home directory, in the fixtures directory
run_k() {
    local home="$WORK/home-$RUNTIME"
    mkdir -p "$home"
    if [[ "$RUNTIME" == jvm ]]; then
        (cd "${SCEN_DIR:-$F}" && env ${SCEN_ENV[@]+"${SCEN_ENV[@]}"} HOME="$home" java -Duser.home="$home" -jar "$JAR" "$@" <"${SCEN_STDIN:-/dev/null}")
    else
        (cd "${SCEN_DIR:-$F}" && env ${SCEN_ENV[@]+"${SCEN_ENV[@]}"} HOME="$home" "$NATIVE" "$@" <"${SCEN_STDIN:-/dev/null}")
    fi
}

record_pass() { PASS=$((PASS + 1)); echo "  PASS  $RUNTIME  $1"; }
record_fail() {
    FAIL=$((FAIL + 1))
    FAILED_IDS+=("$RUNTIME:$1")
    echo "  FAIL  $RUNTIME  $1: $2"
}

# needs <id> <file or command>: skip scenario <id> when a host tool or generated input is missing
needs() {
    [[ -n "$ONLY" && ! "$1" =~ $ONLY ]] && return 1
    if [[ -e "$2" ]] || command -v "$2" >/dev/null 2>&1; then
        return 0
    fi
    if [[ "${CI:-}" == true ]]; then
        FAIL=$((FAIL + 1))
        FAILED_IDS+=("$RUNTIME:$1")
        echo "  FAIL  $RUNTIME  $1: $2 not available"
    else
        SKIP=$((SKIP + 1))
        echo "  SKIP  $RUNTIME  $1: $2 not available"
    fi
    return 1
}

# scenario <id> <expected exit> <output regex> -- <cli args...>
scenario() {
    local id="$1" want="$2" pattern="$3"
    shift 4
    [[ -n "$ONLY" && ! "$id" =~ $ONLY ]] && return
    local out code=0
    : >"$REQUESTS"
    : >"$BODIES"
    out="$(run_k "$@" 2>&1)" || code=$?
    LAST_OUT="$out"
    if [[ "$code" != "$want" ]]; then
        record_fail "$id" "exit $code, expected $want"
        printf '%s\n' "$out" | tail -n 15 | sed 's/^/        /'
    elif ! grep -Eq -- "$pattern" <<<"$out"; then
        record_fail "$id" "output does not match /$pattern/"
        printf '%s\n' "$out" | tail -n 15 | sed 's/^/        /'
    else
        record_pass "$id"
    fi
}

# out_has <id> <regex>: the output of the last scenario also matches (a second check on one run)
out_has() {
    [[ -n "$ONLY" && ! "$1" =~ $ONLY ]] && return
    if grep -Eq -- "$2" <<<"$LAST_OUT"; then
        record_pass "$1"
    else
        record_fail "$1" "last output does not match /$2/"
    fi
}

# requests <id> <regex>: the mock received a matching request during the last scenario
requests() {
    [[ -n "$ONLY" && ! "$1" =~ $ONLY ]] && return
    if grep -Eq -- "$2" "$REQUESTS"; then
        record_pass "$1"
    else
        record_fail "$1" "mock saw no request matching /$2/: $(tr '\n' ' ' <"$REQUESTS")"
    fi
}

# bodies <id> <regex>: a request body the mock received during the last scenario matches
# no_bodies <id> <regex>: none does (e.g. a secret that must be redacted before it leaves the CLI)
bodies() {
    [[ -n "$ONLY" && ! "$1" =~ $ONLY ]] && return
    if grep -Eq -- "$2" "$BODIES"; then
        record_pass "$1"
    else
        record_fail "$1" "mock saw no request body matching /$2/"
    fi
}
no_bodies() {
    [[ -n "$ONLY" && ! "$1" =~ $ONLY ]] && return
    if [[ -s "$BODIES" ]] && ! grep -Eq -- "$2" "$BODIES"; then
        record_pass "$1"
    else
        record_fail "$1" "no request bodies, or one matches /$2/"
    fi
}

# file_has <id> <file> <regex>: a file the last scenario wrote contains a match
# (byte-wise: a UTF-8 grep misses `.` on some binary files, e.g. a random RSA signature)
file_has() {
    [[ -n "$ONLY" && ! "$1" =~ $ONLY ]] && return
    if [[ -f "$F/$2" ]] && LC_ALL=C grep -Eq -- "$3" "$F/$2"; then
        record_pass "$1"
    else
        record_fail "$1" "$2 missing or does not match /$3/"
    fi
}

run_scenarios() {
    # CLI surface
    scenario cli-version 0 'karandash version [0-9]' -- --version
    scenario cli-help 0 'validate' -- --help

    # Config formats and loading
    scenario cfg-yaml 0 'No issues found' -- validate --mr mr.json --config pass.yaml
    scenario cfg-json 0 'No issues found' -- validate --mr mr.json --config pass.json
    scenario cfg-kts 1 'does not match required pattern.*titleFormat' -- validate --mr mr.json --config fail.kts
    scenario cfg-extends-local 0 'too long \(max: 10' -- validate --mr mr.json --config child.yaml
    scenario cfg-extends-remote 0 'too long \(max: 10' -- validate --mr mr.json --config remote-child.yaml
    requests cfg-extends-remote-fetch 'GET /files/base.yaml'
    scenario cfg-file-ref 0 'No issues found' -- validate --mr mr.json --config fileref.yaml
    SCEN_ENV=("E2E_TITLE_PATTERN=^fix: .+")
    scenario cfg-env 1 'Change the title to `fix: ' -- validate --mr mr.json --config env.yaml
    SCEN_ENV=()
    scenario cfg-env-unset 1 "E2E_TITLE_PATTERN' is referenced in config but not set" -- validate --mr mr.json --config env.yaml
    scenario cfg-env-denylist 1 "'\\$\\{HOME\\}'" -- validate --mr mr.json --config deny.yaml
    scenario cfg-normalize 0 'too long \(max: 10' -- validate --mr mr.json --config snake.yaml
    SCEN_DIR="$F/zero"
    scenario cfg-zero 0 'No config found, using default rules' -- validate --mr mr.json
    SCEN_DIR=""
    scenario cfg-dry-run 0 'Configuration is valid' -- validate --mr mr.json --config fail.yaml --dry-run
    scenario cfg-validate 0 '' -- config validate --file pass.yaml
    scenario cfg-describe 0 'Rule configurations' -- config describe rules
    scenario cfg-describe-effective 0 'title' -- config describe --effective --config pass.yaml
    scenario cfg-validate-extends 0 'Circular references: none' -- validate-extends --config child.yaml

    # Profiles
    scenario profile-list 0 'minimal' -- profile list
    scenario profile-show 0 'Profile: +minimal' -- profile show minimal
    scenario profile-export 0 '"profile": "minimal"' -- profile export minimal --format json
    rm -f "$F/init.yaml"
    scenario profile-init 0 'Created' -- init --profile minimal --format yaml --output init.yaml
    file_has profile-init-file init.yaml 'profile: minimal'
    scenario profile-apply 0 '' -- validate --mr mr.json --config profile.yaml

    # Rules, severity, policies and conditions
    scenario rule-fail 1 'Validation FAILED' -- validate --mr mr.json --config fail.yaml
    scenario policy-severity 0 'PASSED · 1 warning' -- validate --mr mr.json --config policy.yaml
    scenario policy-strict 5 'PASSED · 1 warning' -- validate --mr mr.json --config policy.yaml --strict
    scenario policy-disable 0 'No issues found' -- validate --mr mr.json --config policy-off.yaml
    scenario policy-verify 0 'Valid: true' -- verify-policy --config policy.yaml

    # Reporters and outputs
    scenario rep-console 1 'Validation FAILED · 1 error' -- validate --mr mr.json --config fail.yaml --format console
    scenario rep-json 1 '"passed": false' -- validate --mr mr.json --config fail.yaml --format json
    scenario rep-sarif 1 '"version": "2.1.0"' -- validate --mr mr.json --config fail.yaml --format sarif
    scenario rep-rdjsonl 1 '"severity":"ERROR"' -- validate --mr mr.json --config fail.yaml --format rdjsonl
    scenario rep-rdjson 1 '"diagnostics"' -- validate --mr mr.json --config fail.yaml --format rdjson
    scenario rep-markdown 0 '\*\*Passed\*\*' -- validate --mr mr.json --config pass.yaml --format markdown
    rm -f "$F/result.json"
    scenario rep-output-file 1 'Report written to result.json' -- validate --mr mr.json --config fail.yaml --format json --output result.json
    file_has rep-output-file-content result.json '"passed": false'
    scenario report-summary 0 'Total findings: 1' -- report summary --from result.json

    # Live mode against the mock GitLab server
    scenario live-validate 1 'Insufficient approvals' -- validate --platform gitlab --project test/project --mr-id 1 --config live.yaml
    requests live-comment 'POST /api/v4/projects/[^ ]+/merge_requests/1/(notes|discussions)'
    scenario live-pr-url 0 '' -- validate --pr "$MR_URL" --config live-pass.yaml
    scenario live-fix 1 '' -- validate --platform gitlab --project test/project --mr-id 1 --config live.yaml --fix --yes
    requests live-fix-write 'PUT /api/v4/projects/[^ ]+/merge_requests/1$'
    scenario live-simulate 0 'Approval Policy Simulation' -- simulate --mr-url "$MR_URL" --approvers alice --file live.yaml
    scenario live-deps 0 '' -- deps --mr-url "$MR_URL" --file live.yaml
    scenario live-test-rule 0 'Replayed: 1' -- test-rule titleFormat --mr-url "$MR_URL" --file live.yaml
    scenario live-mr-merge 0 "$MR_URL" -- mr merge "$MR_URL" --config live.yaml
    requests live-mr-merge-put 'PUT /api/v4/projects/[^ ]+/merge_requests/1/merge'
    # doctor has no --config: it discovers .karandash/config.yaml in the working directory
    SCEN_DIR="$F/doctor"
    scenario live-doctor 0 'gitlab: credentials accepted' -- doctor
    SCEN_DIR=""

    # GitHub (token and GitHub App) against the mock's GitHub Enterprise API
    scenario gh-token-validate 0 'Comment created' -- validate --platform github --project owner/repo --mr-id 1 --config gh-token.yaml
    requests gh-token-fetch 'GET /api/v3/repos/owner/repo/pulls/1/files'
    if needs gh-app-validate "$F/gh-app.pem"; then
        scenario gh-app-validate 0 'Comment created' -- validate --platform github --project owner/repo --mr-id 1 --config gh-app.yaml
        requests gh-app-exchange 'POST /api/v3/app/installations/42/access_tokens'
        requests gh-app-fetch 'GET /api/v3/repos/owner/repo/pulls/1/files'
        scenario gh-app-wrong-key 3 'could not be decoded: bad signature' -- validate --platform github --project owner/repo --mr-id 1 --config gh-app-wrong-key.yaml
        SCEN_DIR="$F/gh-doctor"
        scenario gh-app-doctor 0 'github: credentials accepted' -- doctor
        SCEN_DIR=""
    fi

    # Analysis and maintenance commands
    scenario cmd-explain 0 'Rule: titleFormat' -- explain --config pass.yaml
    scenario cmd-doctor-noconfig 0 'No config file, zero-config defaults apply' -- doctor
    # validate without an ai block records no statistics: before any AI run there is no history
    scenario cmd-stats-empty 0 'No statistics recorded yet' -- stats
    scenario cmd-calibrate 0 'Calibration Analysis \(|runs analyzed\)|Insufficient data: [0-9]+ runs' -- calibrate
    scenario cmd-test-rule 0 'Replayed: 1' -- test-rule titleFormat --snapshot mr.json --file pass.yaml
    scenario cmd-mutate 0 'utation' -- mutate --file pass.yaml
    scenario cmd-cache-stats 0 'Local CAS Cache Statistics' -- cache stats
    scenario cmd-cache-gc 0 'No cache namespaces found|GC complete' -- cache gc
    scenario cmd-cache-remote-stats 0 'Remote Cache Configuration' -- cache remote-stats
    scenario cmd-cache-clear 0 '' -- cache clear
    scenario cmd-plugin-list 0 'No plugins installed' -- plugin list
    scenario cmd-plugin-cache-clear 0 'Plugin cache is already empty|Cleared [0-9]+ cached plugin' -- plugin cache clear
    scenario cmd-plugin-load-invalid 2 'Error: ' -- plugin load plugin.jar
    scenario cmd-generate-schema 0 'Schema written' -- generate --schema
    file_has cmd-generate-schema-file .karandash.schema.json '"\$schema"'
    if needs cmd-plugin-sign "$F/sign-key.der"; then
        rm -f "$F/plugin.jar.sig"
        scenario cmd-plugin-sign 0 '' -- plugin sign plugin.jar --key sign-key.der
        file_has cmd-plugin-sign-file plugin.jar.sig '.'
        scenario cmd-plugin-verify 0 'Signature valid: plugin\.jar' -- plugin verify plugin.jar --key sign-pub.der
    fi
    SCEN_STDIN="$F/lsp.in"
    if [[ "$RUNTIME" == jvm ]]; then
        scenario lsp-initialize 0 '"hoverProvider":true' -- lsp
        SCEN_STDIN="$F/lsp-ide.in"
        scenario lsp-completion 0 '"id":2,"result":.*"label":"commitMessages"' -- lsp
        out_has lsp-completion-title-format '"label":"titleFormat"'
        out_has lsp-hover '"id":3,"result":\{"contents":\{"kind":"markdown","value":"# titleFormat'
    else
        scenario lsp-initialize 6 'JVM distribution \(karandash.jar\) only' -- lsp
    fi
    SCEN_STDIN=""
    if needs dangerfile-js node; then
        scenario dangerfile-js 0 'modified: src/main.kt' -- validate --mr mr.json --config danger/config.yaml
    fi

    # AI command group (env-gated); a model call needs a live LLM, so only usage paths run here
    scenario ai-gated 2 'no such subcommand ai' -- ai
    SCEN_ENV=("OLLAMA_HOST=http://127.0.0.1:1")
    scenario ai-fix-usage 2 'Usage: karandash ai fix' -- ai fix --rule x
    SCEN_ENV=()

    local fn
    for fn in $(compgen -A function scenarios_); do
        "$fn"
    done
}

run_runtime() {
    RUNTIME="$1"
    local bin="$2"
    if [[ ! -e "$bin" ]]; then
        if [[ "${CI:-}" == true ]]; then
            FAIL=$((FAIL + 1))
            FAILED_IDS+=("$RUNTIME:binary-missing")
            echo "FAIL: $RUNTIME binary missing: $bin"
        else
            SKIP=$((SKIP + 1))
            echo "SKIP: $RUNTIME binary missing: $bin"
        fi
        return
    fi
    echo "== $RUNTIME ($bin)"
    run_scenarios
}

run_runtime jvm "$JAR"
run_runtime native "$NATIVE"

echo ""
echo "feature-e2e: $PASS passed, $FAIL failed, $SKIP skipped"
if [[ $FAIL -gt 0 ]]; then
    printf '  failed: %s\n' "${FAILED_IDS[@]}"
    exit 1
fi
