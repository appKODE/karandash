# Rule smoke on the binaries: every built-in rule (plugins/builtin/builtin-modules.txt) enabled in one config runs to
# completion (`Rule [<id>]: completed`, debug log) without an internal error; a per-rule `when:` that is false skips the
# rule and one that is true runs it; `severity-cap: warning` turns the failing run into a pass with a warning, and a
# per-rule `severity-cap: error` keeps that rule blocking.

RS="$F/rule-smoke"
RS_IDS=(titleFormat commitMessages jiraTicket branchNaming fileChanges codeowners approval reviewer cicdStatus conflict
    draftPrevention labels sizeLimits security contextTag descriptionFormat crossEntityConsistency languagePolicy
    commitBody dependencyValidation)
mkdir -p "$RS"
cp "$FIXTURES/test-mr.json" "$RS/mr.json" # target branch main, title "feat: add runtime verification"
{
    echo 'rules:'
    for id in "${RS_IDS[@]}"; do printf '  %s:\n    enabled: true\n' "$id"; done
} >"$RS/all.yaml"
cat >"$RS/when-skip.yaml" <<'EOF'
rules:
  title-format:
    pattern: "^fix: .+"
    when:
      expression: 'target_branch_matches("^release/")'
EOF
sed 's#\^release/#^main$#' "$RS/when-skip.yaml" >"$RS/when-run.yaml"
printf 'severity-cap: warning\nrules:\n  title-format:\n    pattern: "^fix: .+"\n' >"$RS/cap.yaml"
printf '    severity-cap: error\n' | cat "$RS/cap.yaml" - >"$RS/cap-rule.yaml"

scenarios_rule_smoke() {
    SCEN_DIR="$RS"
    # the MR fails jiraTicket (no ticket) and approval, so the run exits 1; an internal error would be 4
    scenario rule-smoke-all 1 'Configured rules \(20\)' -- \
        --verbose validate --mr mr.json --config all.yaml --no-auto-detect --no-cache --format json
    local id logged
    for id in "${RS_IDS[@]}"; do
        # the engine logs a rule under its declared id: dependencyValidation is declared kebab-case
        logged=$id
        [[ $id == dependencyValidation ]] && logged=dependency-validation
        out_has "rule-smoke-$id" "Rule \[$logged\]: completed in [0-9.]+ms"
    done
    scenario rule-when-skip 0 "Rule \[titleFormat\]: skipped \(when: 'target_branch_matches" -- \
        --verbose validate --mr mr.json --config when-skip.yaml --no-auto-detect --no-cache
    scenario rule-when-run 1 'Rule \[titleFormat\]: completed in [0-9.]+ms -- 1 finding' -- \
        --verbose validate --mr mr.json --config when-run.yaml --no-auto-detect --no-cache
    scenario rule-severity-cap 0 'PASSED — 1 warning' -- validate --mr mr.json --config cap.yaml --no-auto-detect --no-cache
    scenario rule-severity-cap-rule 1 'Validation FAILED · 1 error' -- \
        validate --mr mr.json --config cap-rule.yaml --no-auto-detect --no-cache
    SCEN_DIR=""
}
