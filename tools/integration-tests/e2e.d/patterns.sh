# Pattern system on the binaries: a config `patterns:` entry written in pomsky, used by a rule as `@name` (a pass and a
# fail case, so a pattern that never compiled cannot pass both); a nested-quantifier rule pattern refused as an error
# finding; a cache TTL that is not a duration fails the config load (exit 2), not the run.

PT="$F/patterns"
mkdir -p "$PT"
cp "$FIXTURES/test-mr.json" "$PT/mr.json" # title "feat: add runtime verification"
cat >"$PT/pomsky-fail.yaml" <<'EOF'
patterns:
  feat-title: "pomsky: ^ 'feat: ' [Lu] .+"
rules:
  title-format:
    pattern: "@feat-title"
EOF
sed 's/\[Lu\]/[Ll]/' "$PT/pomsky-fail.yaml" >"$PT/pomsky-pass.yaml"
cat >"$PT/redos.yaml" <<'EOF'
rules:
  title-format:
    pattern: "(a+)+"
EOF
cat >"$PT/bad-ttl.yaml" <<'EOF'
cache:
  default-ttl: "abc"
rules:
  title-format:
    pattern: "^feat: .+"
EOF

scenarios_patterns() {
    SCEN_DIR="$PT"
    scenario patterns-pomsky-name-fail 1 'MR title does not match required pattern\. +titleFormat' -- \
        validate --mr mr.json --config pomsky-fail.yaml --no-auto-detect --no-cache
    scenario patterns-pomsky-name-pass 0 'No issues found' -- \
        validate --mr mr.json --config pomsky-pass.yaml --no-auto-detect --no-cache
    scenario patterns-redos-rule 1 "error +Regex pattern has nested quantifiers \(depth 2\) .*'\(a\+\)\+' +titleFormat" -- \
        validate --mr mr.json --config redos.yaml --no-auto-detect --no-cache
    scenario patterns-cache-ttl-invalid 2 "'cache' is invalid: Invalid duration: 'abc'" -- \
        validate --mr mr.json --config bad-ttl.yaml --no-auto-detect --no-cache
    SCEN_DIR=""
}
