# shellcheck shell=bash
# Scenarios: authenticated remote extends (cfg-auth-), plugin registry (registry-), enrichment providers (enrich-),
# config watch mode (watch-) and the setup wizard (wizard-). Sourced by feature-e2e.sh.
#
# Authenticated extends and the plugin registry insist on HTTPS, so tls_proxy.py fronts the mock on
# https://localhost:$TLS_PORT with a throwaway certificate that the CLI under test is told to trust.

# -- HTTPS front ---------------------------------------------------------------

TLS_PORT=""
TLS_ENV_JVM=()
TLS_ENV_NATIVE=()
if command -v openssl >/dev/null 2>&1 && command -v keytool >/dev/null 2>&1; then
    TLS_DIR="$WORK/tls"
    mkdir -p "$TLS_DIR"
    if openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=localhost" \
        -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
        -keyout "$TLS_DIR/key.pem" -out "$TLS_DIR/cert.pem" >/dev/null 2>&1 &&
        keytool -importcert -noprompt -alias e2e -file "$TLS_DIR/cert.pem" -storetype PKCS12 \
            -keystore "$TLS_DIR/trust.p12" -storepass changeit >/dev/null 2>&1; then
        TLS_PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
        python3 "$TEST_DIR/mock-server/tls_proxy.py" "$TLS_PORT" "$PORT" "$TLS_DIR/cert.pem" "$TLS_DIR/key.pem" \
            >"$WORK/tls-proxy.log" 2>&1 &
        TLS_ENV_JVM=("JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=$TLS_DIR/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit")
        TLS_ENV_NATIVE=("SSL_CERT_FILE=$TLS_DIR/cert.pem" "CURL_CA_BUNDLE=$TLS_DIR/cert.pem")
        for _ in $(seq 1 50); do
            curl -sf --cacert "$TLS_DIR/cert.pem" "https://localhost:$TLS_PORT/api/v4/projects/1/merge_requests/1" >/dev/null 2>&1 && break
            python3 -c 'import time; time.sleep(0.2)'
        done
    fi
fi

# -- Fixtures ------------------------------------------------------------------

mkdir -p "$F/misc"
# Remote parents that need credentials: the extends entry carries its own auth block
misc_extends() { # <mock path segment> <auth block lines>
    cat <<EOT
allow_localhost: true
extends:
  - url: "https://localhost:$TLS_PORT/cfgauth/$1/base.yaml"
$2
rules:
  title-format:
    pattern: "^feat: .+"
EOT
}
misc_extends bearer '    auth:
      type: bearer-token
      token: "cfg-bearer-token"' >"$F/misc/auth-bearer.yaml"
misc_extends bearer '    auth:
      type: bearer-token
      token: "wrong-token"' >"$F/misc/auth-wrong.yaml"
misc_extends header '    auth:
      type: custom-header
      name: "X-Api-Key"
      value: "cfg-api-key"' >"$F/misc/auth-header.yaml"
misc_extends basic '    auth:
      type: basic-auth
      username: "cfg-user"
      password: "cfg-pass"' >"$F/misc/auth-basic.yaml"
misc_extends bearer '' >"$F/misc/auth-missing.yaml"
cat >"$F/misc/auth-http.yaml" <<EOF
allow_localhost: true
extends:
  - url: "http://localhost:$PORT/cfgauth/bearer/base.yaml"
    auth:
      type: bearer-token
      token: "cfg-bearer-token"
EOF

# Enrichment: Jira and Confluence on the mock; a snapshot MR that names a ticket and a Confluence page
cat >"$F/misc/enrich-mr.json" <<EOF
{
  "id": "enrich-1",
  "iid": 1,
  "title": "feat: ABC-123 add runtime verification",
  "description": "Design: http://localhost:$PORT/confluence/wiki/spaces/ENG/pages/123456/Design+notes",
  "source_branch": "feature/ABC-123-runtime",
  "target_branch": "main",
  "author": {"username": "testuser", "name": "Test User", "email": "test@example.com"},
  "state": "opened",
  "draft": false,
  "labels": [],
  "commits": [],
  "changes": [],
  "approvals": [],
  "ci_status": "success"
}
EOF
misc_enrich_config() { # <atlassian token>
    cat <<EOT
allow_localhost: true
enrichment:
  jira:
    url: "http://localhost:$PORT/jira"
    email: "e2e@example.com"
    token: "$1"
  confluence:
    url: "http://localhost:$PORT/confluence"
    email: "e2e@example.com"
    token: "atlassian-token"
rules:
  jira-ticket:
    projectKeys: ["ABC"]
    jiraUrl: "http://localhost:$PORT/jira"
    validateStatus: ["Done"]
EOT
}
misc_enrich_config atlassian-token >"$F/misc/enrich.yaml"
misc_enrich_config wrong-token >"$F/misc/enrich-badtoken.yaml"

# Plugin registry: one project directory per registry URL (the client caches each index per URL for 24h)
misc_registry_dir() { # <dir> <index path> [token]
    mkdir -p "$F/$1/.karandash"
    {
        printf 'plugins:\n  registry-url: "https://localhost:%s%s"\n  allow-localhost: true\n' "$TLS_PORT" "$2"
        [[ -z "${3:-}" ]] || printf '  token: "%s"\n' "$3"
    } >"$F/$1/.karandash/manifest.yaml"
}

# RegistryClient cache key of a registry URL: Kotlin String.hashCode() with the minus sign dropped
misc_url_hash() {
    python3 -c 'import sys
h = 0
for c in sys.argv[1]: h = (31 * h + ord(c)) & 0xFFFFFFFF
print(abs(h - (1 << 32) if h >= 1 << 31 else h))' "$1"
}

# Watch mode: the config under watch is rewritten while the CLI runs
printf 'rules:\n  title-format:\n    pattern: "^(feat|fix): .+"\n' >"$F/misc/watch-pass.yaml"
printf 'rules:\n  title-format:\n    pattern: "^fix: .+"\n' >"$F/misc/watch-fail.yaml"

# Wizard answers (one per prompt; blank accepts the default)
printf '\n\ny\n' >"$F/misc/wizard.in"

# -- Scenarios -----------------------------------------------------------------

misc_tls_env() { # SCEN_ENV so the CLI under test trusts the mock's certificate (native curl reads SSL_CERT_FILE)
    if [[ "$RUNTIME" == jvm ]]; then SCEN_ENV=("${TLS_ENV_JVM[@]}"); else SCEN_ENV=("${TLS_ENV_NATIVE[@]}"); fi
}

scenarios_misc() {
    misc_auth
    misc_registry
    misc_enrich
    misc_watch
    misc_wizard
}

misc_auth() {
    # Credentials are never sent over plain HTTP
    scenario cfg-auth-http-refused 2 'requires HTTPS' -- validate --mr mr.json --config misc/auth-http.yaml
    needs cfg-auth-bearer openssl && needs cfg-auth-bearer keytool || return 0
    # Without the trust settings the self-signed front is a clean fetch error, not an internal one
    # ErrorRenderer prints the code and the cause on separate lines and scenario greps line by line
    scenario cfg-auth-untrusted-cert 2 '(PKIX|TLS verification failed)' -- validate --mr mr.json --config misc/auth-bearer.yaml
    misc_tls_env
    scenario cfg-auth-missing 2 'E0013|401' -- validate --mr mr.json --config misc/auth-missing.yaml
    scenario cfg-auth-wrong-token 2 'E0013|401' -- validate --mr mr.json --config misc/auth-wrong.yaml
    scenario cfg-auth-bearer 0 'too long \(max: 10' -- validate --mr mr.json --config misc/auth-bearer.yaml
    requests cfg-auth-bearer-fetch 'GET /cfgauth/bearer/base.yaml'
    scenario cfg-auth-custom-header 0 'too long \(max: 10' -- validate --mr mr.json --config misc/auth-header.yaml
    scenario cfg-auth-basic 0 'too long \(max: 10' -- validate --mr mr.json --config misc/auth-basic.yaml
    SCEN_ENV=()
}

misc_registry() {
    needs registry-install openssl && needs registry-install keytool || return 0
    local plugins="$WORK/regplug-$RUNTIME"
    rm -rf "$plugins"
    misc_registry_dir misc/reg1 /registry/v1.json reg-token
    misc_registry_dir misc/reg2 /registry/v2.json reg-token
    misc_registry_dir misc/reg-notoken "/registry/v1.json?notoken"
    misc_tls_env
    SCEN_DIR="$F/misc/reg-notoken"
    scenario registry-no-token 2 'Failed to fetch registry: HTTP 401' -- plugin install --registry e2e-js-plugin --plugin-dir "$plugins"
    SCEN_DIR="$F/misc/reg1"
    scenario registry-install 0 'Installed e2e-js-plugin v1\.0\.0' -- plugin install --registry e2e-js-plugin --pin --plugin-dir "$plugins"
    requests registry-index-fetch 'GET /registry/v1\.json'
    requests registry-download 'GET /registry/dl/e2e-js-plugin-1\.0\.0\.mjs'
    file_has registry-pin-manifest misc/reg1/.karandash/manifest.yaml 'e2e-js-plugin'
    scenario registry-list 0 'e2e-js-plugin' -- plugin list --plugin-dir "$plugins"
    scenario registry-bad-checksum 1 'Checksum mismatch' -- plugin install --registry e2e-bad-checksum --plugin-dir "$plugins"
    # Offline search: an unreachable registry URL whose cache entry is a copy of the v1 index fetched above
    local cache="$WORK/home-$RUNTIME/.karandash/cache" offline_url="https://localhost:1/registry/v1.json"
    mkdir -p "$F/misc/reg-offline/.karandash"
    printf 'plugins:\n  registry-url: "%s"\n  allow-localhost: true\n' "$offline_url" >"$F/misc/reg-offline/.karandash/manifest.yaml"
    cp "$cache/registry-$(misc_url_hash "https://localhost:$TLS_PORT/registry/v1.json").json" \
        "$cache/registry-$(misc_url_hash "$offline_url").json" 2>/dev/null
    SCEN_DIR="$F/misc/reg-offline"
    scenario registry-search-offline 0 'using cached registry from .* ago' -- plugin search e2e --refresh
    SCEN_DIR="$F/misc/reg2"
    scenario registry-update-check 0 'update available' -- plugin update --check-only --plugin-dir "$plugins"
    scenario registry-update 0 'updated successfully|Updated successfully' -- plugin update --yes --plugin-dir "$plugins"
    requests registry-update-download 'GET /registry/dl/e2e-js-plugin-1\.1\.0\.mjs'
    # `plugin list` inspects .mjs files statically (version "unknown"), so check the installed artifact itself
    file_has registry-updated-artifact "../regplug-$RUNTIME/e2e-js-plugin.mjs" 'version: "1\.1\.0"'
    scenario registry-show 0 'Registry: https://localhost:[0-9]+/registry/v2\.json \(from' -- plugin registry show
    scenario registry-info 0 'ID: +e2e-js-plugin' -- plugin info e2e-js-plugin --plugin-dir "$plugins"
    scenario registry-info-deps 0 'Requires: karandash >= .*\(registry v1\.1\.0\)' -- plugin info e2e-js-plugin --dependencies --plugin-dir "$plugins"
    scenario registry-rebuild 0 'Rebuilt e2e-js-plugin v1\.1\.0 from the registry' -- plugin rebuild e2e-js-plugin --plugin-dir "$plugins"
    scenario registry-uninstall 0 'Removed plugin: e2e-js-plugin' -- plugin uninstall e2e-js-plugin --plugin-dir "$plugins"
    scenario registry-info-missing 2 'Plugin not found: e2e-js-plugin' -- plugin info e2e-js-plugin --plugin-dir "$plugins"
    mkdir -p "$F/misc/reg-set"
    SCEN_DIR="$F/misc/reg-set"
    scenario registry-set 0 'Registry: https://plugins\.example\.com/index\.json' -- plugin registry set https://plugins.example.com/index.json
    file_has registry-set-manifest misc/reg-set/.karandash/manifest.yaml 'plugins\.example\.com/index\.json'
    SCEN_DIR=""
    SCEN_ENV=()
}

misc_enrich() {
    scenario enrich-jira 1 "ABC-123.*In Progress|In Progress.*Done" -- validate --mr misc/enrich-mr.json --config misc/enrich.yaml
    requests enrich-jira-issue-fetch 'GET /jira/rest/api/3/issue/ABC-123'
    requests enrich-confluence-page-fetch 'GET /confluence/rest/api/content/123456'
}

# validate --watch in the background; rewrite the watched config; expect a second validation with the new result
misc_watch() {
    [[ -n "$ONLY" && ! "watch-revalidate" =~ $ONLY ]] && return 0
    local cfg="misc/watch-$RUNTIME-$$.yaml" out="$WORK/watch-$RUNTIME.out" _
    cp "$F/misc/watch-pass.yaml" "$F/$cfg"
    : >"$out"
    run_k validate --mr mr.json --config "$cfg" --watch >"$out" 2>&1 &
    local pid=$!
    for _ in $(seq 1 120); do grep -q 'Watching' "$out" && break; sleep 0.5; done
    if ! grep -q 'Watching' "$out"; then
        record_fail watch-start "no watch banner within 60s"
        tail -n 15 "$out" | sed 's/^/        /'
    else
        record_pass watch-start
        cp "$F/misc/watch-fail.yaml" "$F/$cfg"
        for _ in $(seq 1 120); do grep -q 'titleFormat' "$out" && break; sleep 0.5; done
        if grep -q 'titleFormat' "$out"; then
            record_pass watch-revalidate
        else
            record_fail watch-revalidate "config change did not trigger a re-validation within 60s"
            tail -n 15 "$out" | sed 's/^/        /'
        fi
    fi
    pkill -f "$cfg" 2>/dev/null
    kill "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    rm -f "$F/$cfg"
}

misc_wizard() {
    rm -f "$F/misc/wizard-out.yaml"
    SCEN_STDIN="$F/misc/wizard.in"
    scenario wizard-generate 0 '.' -- wizard --skip-detection --format yaml --output misc/wizard-out.yaml
    SCEN_STDIN=""
    file_has wizard-config-file misc/wizard-out.yaml 'rules:|profile:'
}
