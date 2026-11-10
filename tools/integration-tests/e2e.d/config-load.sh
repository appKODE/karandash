# Config loading on the binaries: the load-time checks (a rule config that does not decode, a `when:` with an unknown
# predicate, a typo'd module id), a named extends set from .karandash/extends-manifest.yaml whose entry carries a
# bearer-token auth block, and the KARANDASH_CONFIG_DIR / KARANDASH_CONFIG discovery overrides.

CL="$F/config-load"
mkdir -p "$CL/.karandash" "$CL/discover/.karandash"
cp "$FIXTURES/test-mr.json" "$CL/mr.json"
cat >"$CL/bad-rule.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^feat: .+"
    max-length: "abc"
EOF
cat >"$CL/bad-when.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^feat: .+"
    when: "no_such_predicate()"
EOF
cat >"$CL/typo.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^feat: .+"
  titel-format:
    enabled: true
EOF
cat >"$CL/.karandash/extends-manifest.yaml" <<EOF
extends-sets:
  secured:
    - url: "http://localhost:$PORT/files-auth/base.yaml"
      auth:
        type: bearer-token
        token: "\${E2E_FILES_TOKEN}"
EOF
cat >"$CL/set-child.yaml" <<'EOF'
allow_localhost: true
extends: "@secured"
rules:
  title-format:
    pattern: "^feat: .+"
EOF
# discovered only through the overrides: the scenarios run from $CL, whose .karandash/ holds no config
cp "$F/fail.yaml" "$CL/discover/.karandash/config.yaml"

scenarios_config_load() {
    SCEN_DIR="$CL"
    scenario cfg-load-checks-rule 2 "Invalid config for rule 'titleFormat': .*maxLength" -- \
        validate --mr mr.json --config bad-rule.yaml --no-auto-detect --no-cache
    scenario cfg-load-checks-when 0 "Invalid 'when' of rule 'titleFormat': Unknown condition: no_such_predicate\(\)" -- \
        validate --mr mr.json --config bad-when.yaml --no-auto-detect --no-cache
    scenario cfg-load-checks-typo 0 "Unknown plugin module: 'titelFormat' \(rules\)\. Did you mean 'titleFormat'\?" -- \
        validate --mr mr.json --config typo.yaml --no-auto-detect --no-cache
    scenario cfg-extends-sets-list 0 '@secured' -- validate-extends --list-sets
    out_has cfg-extends-sets-list-url "files-auth/base\.yaml"
    # the set's auth block reaches the fetch: a token is never sent over plain HTTP, so the parent is refused unfetched
    SCEN_ENV=("E2E_FILES_TOKEN=e2e-files-token")
    scenario cfg-extends-auth-sets 1 'E0013\] Authenticated request requires HTTPS.*files-auth/base\.yaml' -- \
        validate-extends --config set-child.yaml
    if [[ -z "$ONLY" || cfg-extends-auth-sets-unsent =~ $ONLY ]]; then
        if grep -q 'files-auth' "$REQUESTS"; then record_fail cfg-extends-auth-sets-unsent "the mock saw the request"; else
            record_pass cfg-extends-auth-sets-unsent
        fi
    fi
    SCEN_ENV=("KARANDASH_CONFIG_DIR=$CL/discover")
    scenario cfg-config-dir 1 'does not match required pattern' -- validate --mr mr.json --no-auto-detect --no-cache
    SCEN_ENV=("KARANDASH_CONFIG=$CL/discover/.karandash/config.yaml")
    scenario cfg-config-env 1 'does not match required pattern' -- validate --mr mr.json --no-auto-detect --no-cache
    SCEN_ENV=()
    SCEN_DIR=""
}
