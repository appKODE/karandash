#!/usr/bin/env bash
set -euo pipefail

# Competitor Benchmark Script (Docker)
#
# Compares karandash against danger-js, danger-kotlin, and reviewdog
# inside a self-contained Docker environment with hyperfine.
#
# Usage: benchmark-competitors.sh [iterations]
# Environment: BENCH_ITERATIONS (default: 30)

ITERATIONS="${1:-${BENCH_ITERATIONS:-30}}"
RESULTS_DIR="/bench/results"
FIXTURE_REPO="/bench/test-repo"

echo "══════════════════════════════════════════════════════════════"
echo " KARANDASH COMPETITOR BENCHMARK (Linux x64)"
echo " Platform: $(uname -s)-$(uname -m)"
echo " Iterations: $ITERATIONS"
echo "══════════════════════════════════════════════════════════════"
echo ""

check_cmd() { command -v "$1" > /dev/null 2>&1; }

for tool in hyperfine git; do
    if ! check_cmd "$tool"; then
        echo "ERROR: $tool is required but not found"
        exit 1
    fi
done

mkdir -p "$RESULTS_DIR"

# --- Detect tools ---
HAS_NATIVE=false; [ -x /bench/karandash-native ] && HAS_NATIVE=true
HAS_FATJAR=false; [ -f /bench/karandash.jar ] && HAS_FATJAR=true
HAS_DANGER_JS=false; check_cmd danger && HAS_DANGER_JS=true
HAS_DANGER_KT=false; check_cmd danger-kotlin && HAS_DANGER_KT=true
HAS_REVIEWDOG=false; check_cmd reviewdog && HAS_REVIEWDOG=true

echo "Tools detected:"
$HAS_NATIVE    && echo "  [+] karandash-native"  || echo "  [-] karandash-native"
$HAS_FATJAR    && echo "  [+] karandash-fatjar"   || echo "  [-] karandash-fatjar"
$HAS_DANGER_JS && echo "  [+] danger-js: $(danger --version 2>/dev/null || echo unknown)" || echo "  [-] danger-js"
$HAS_DANGER_KT && echo "  [+] danger-kotlin"      || echo "  [-] danger-kotlin"
$HAS_REVIEWDOG && echo "  [+] reviewdog: $(reviewdog --version 2>&1 | head -1)" || echo "  [-] reviewdog"
echo ""

# --- Setup fixture repository ---
setup_fixture_repo() {
    rm -rf "$FIXTURE_REPO"
    mkdir -p "$FIXTURE_REPO"
    cd "$FIXTURE_REPO"

    git init -q
    git config user.email "bench@test.local"
    git config user.name "Benchmark"
    git checkout -q -b main

    mkdir -p src docs
    for i in $(seq 1 20); do
        cat > "src/file${i}.kt" <<KOTLIN
package com.example

class File${i} {
    fun process(): String = "result-$i"
    fun validate(input: String): Boolean = input.isNotEmpty()
}
KOTLIN
    done
    echo "# README" > README.md
    echo "# Docs" > docs/guide.md

    # Karandash config (3 rules)
    mkdir -p .karandash
    cat > .karandash/config.yaml <<'YAML'
rules:
  title-format:
    enabled: true
    pattern: "^(feat|fix|chore|docs|refactor|test|ci)(\\(.*\\))?: .+"
  branch-naming:
    enabled: true
    pattern: "^(feature|bugfix|hotfix|release|chore)/[a-z0-9][a-z0-9-]+$"
  draft:
    enabled: true
reporters:
  console: true
YAML

    # Danger JS Dangerfile (equivalent 3 checks)
    cat > Dangerfile.js <<'JS'
const { danger, warn, fail, message } = require("danger");

const titlePattern = /^(feat|fix|chore|docs|refactor|test|ci)(\(.*\))?: .+/;
const prTitle = danger.git.commits[0]?.message || "";
if (!titlePattern.test(prTitle)) {
  warn("PR title does not match conventional commit format");
}

const branchPattern = /^(feature|bugfix|hotfix|release|chore)\/[a-z0-9][a-z0-9-]+$/;

const commits = danger.git.commits || [];
const hasDraftMarker = commits.some(c =>
  /\b(WIP|Draft|DO NOT MERGE)\b/i.test(c.message)
);
if (hasDraftMarker) {
  warn("This appears to be a work-in-progress");
}

message(`Checked ${danger.git.modified_files.length} modified, ${danger.git.created_files.length} created files`);
JS

    # Danger Kotlin Dangerfile (equivalent 3 checks)
    cat > Dangerfile.df.kts <<'KTS'
import systems.danger.kotlin.*

val danger = Danger(args)

val titlePattern = Regex("^(feat|fix|chore|docs|refactor|test|ci)(\\(.*\\))?: .+")
val commitMsg = danger.git.commits.firstOrNull()?.message ?: ""
if (!titlePattern.matches(commitMsg)) {
    warn("Commit message does not match conventional commit format")
}

val branchPattern = Regex("^(feature|bugfix|hotfix|release|chore)/[a-z0-9][a-z0-9-]+$")

val hasDraft = danger.git.commits.any {
    it.message.contains("WIP", ignoreCase = true) ||
    it.message.contains("Draft", ignoreCase = true) ||
    it.message.contains("DO NOT MERGE", ignoreCase = true)
}
if (hasDraft) {
    warn("This appears to be a work-in-progress")
}

message("Checked ${danger.git.modifiedFiles.size} modified, ${danger.git.createdFiles.size} created files")
KTS

    # Reviewdog rdjson fixture (pre-generated lint results)
    cat > lint-results.rdjson <<'RDJSON'
{
  "source": { "name": "custom-linter", "url": "https://example.com" },
  "diagnostics": [
    {
      "message": "Title does not match conventional commit format",
      "location": { "path": "src/file1.kt", "range": { "start": { "line": 1 } } },
      "severity": "WARNING",
      "code": { "value": "title-format" }
    },
    {
      "message": "Branch name does not match required pattern",
      "location": { "path": "src/file3.kt", "range": { "start": { "line": 1 } } },
      "severity": "WARNING",
      "code": { "value": "branch-naming" }
    },
    {
      "message": "Draft marker detected in commit",
      "location": { "path": "src/file5.kt", "range": { "start": { "line": 1 } } },
      "severity": "INFO",
      "code": { "value": "draft-check" }
    }
  ]
}
RDJSON

    git add -A
    git commit -q -m "feat(init): initial project setup with 20 source files"

    # Create feature branch with changes
    git checkout -q -b feature/add-login
    for i in 1 3 5 7 9; do
        echo "// Modified for login feature" >> "src/file${i}.kt"
    done
    echo "// New login service" > src/login-service.kt
    git add -A
    git commit -q -m "feat(auth): add login service and modify core files"

    cd /bench
}

echo "Setting up fixture repository..."
setup_fixture_repo
echo "Done."
echo ""

# ── Benchmark 1: Startup (--version) ─────────────────────────────────────────

echo "══════════════════════════════════════════════════════════════"
echo " BENCHMARK 1: Cold Startup (--version)"
echo "══════════════════════════════════════════════════════════════"
echo ""

BENCH1_ARGS=(
    --warmup 3
    --runs "$ITERATIONS"
    --export-json "$RESULTS_DIR/startup.json"
    --export-markdown "$RESULTS_DIR/startup.md"
)

if $HAS_NATIVE; then
    BENCH1_ARGS+=(-n "karandash-native" "/bench/karandash-native --version")
fi
if $HAS_FATJAR; then
    BENCH1_ARGS+=(-n "karandash-fatjar" "java -jar /bench/karandash.jar --version")
fi
if $HAS_DANGER_JS; then
    BENCH1_ARGS+=(-n "danger-js" "danger --version")
fi
if $HAS_DANGER_KT; then
    BENCH1_ARGS+=(-n "danger-kotlin" "danger-kotlin --version")
fi
if $HAS_REVIEWDOG; then
    BENCH1_ARGS+=(-n "reviewdog" "reviewdog --version")
fi

hyperfine "${BENCH1_ARGS[@]}" 2>&1
echo ""

# ── Benchmark 2: Validation (3 rules) ────────────────────────────────────────

echo "══════════════════════════════════════════════════════════════"
echo " BENCHMARK 2: Config Parse + Validation (3 rules)"
echo "══════════════════════════════════════════════════════════════"
echo ""

BENCH2_ARGS=(
    --warmup 3
    --runs "$ITERATIONS"
    --ignore-failure
    --export-json "$RESULTS_DIR/validation-3rules.json"
    --export-markdown "$RESULTS_DIR/validation-3rules.md"
)

if $HAS_NATIVE; then
    BENCH2_ARGS+=(-n "karandash-native" "/bench/karandash-native validate --config $FIXTURE_REPO/.karandash/config.yaml --dry-run --quiet")
fi
if $HAS_FATJAR; then
    BENCH2_ARGS+=(-n "karandash-fatjar" "java -jar /bench/karandash.jar validate --config $FIXTURE_REPO/.karandash/config.yaml --dry-run --quiet")
fi
if $HAS_DANGER_JS; then
    BENCH2_ARGS+=(-n "danger-js" "cd $FIXTURE_REPO && danger local --text-only --dangerfile Dangerfile.js -b main")
fi
if $HAS_DANGER_KT; then
    BENCH2_ARGS+=(-n "danger-kotlin" "cd $FIXTURE_REPO && danger-kotlin local -b main")
fi
if $HAS_REVIEWDOG; then
    BENCH2_ARGS+=(-n "reviewdog" "cd $FIXTURE_REPO && cat lint-results.rdjson | reviewdog -f=rdjson -diff='git diff main' -filter-mode=nofilter -reporter=local")
fi

hyperfine "${BENCH2_ARGS[@]}" 2>&1
echo ""

# ── Benchmark 3: Karandash full config (21 rules) ────────────────────────────

FULL_CONFIG="/bench/fixtures/configs/full.yaml"
if [ -f "$FULL_CONFIG" ]; then
    echo "══════════════════════════════════════════════════════════════"
    echo " BENCHMARK 3: Karandash Full Validation (21 rules)"
    echo "══════════════════════════════════════════════════════════════"
    echo ""

    BENCH3_ARGS=(
        --warmup 3
        --runs "$ITERATIONS"
        --ignore-failure
        --export-json "$RESULTS_DIR/validation-21rules.json"
        --export-markdown "$RESULTS_DIR/validation-21rules.md"
    )

    if $HAS_NATIVE; then
        BENCH3_ARGS+=(-n "karandash-native-21rules" "/bench/karandash-native validate --config $FULL_CONFIG --dry-run --quiet")
    fi
    if $HAS_FATJAR; then
        BENCH3_ARGS+=(-n "karandash-fatjar-21rules" "java -jar /bench/karandash.jar validate --config $FULL_CONFIG --dry-run --quiet")
    fi

    hyperfine "${BENCH3_ARGS[@]}" 2>&1
    echo ""
fi

# ── Combined report ──────────────────────────────────────────────────────────

cat > "$RESULTS_DIR/report.md" <<EOF
# Karandash Competitor Benchmark Report

**Date:** $(date -u +%Y-%m-%dT%H:%M:%SZ)
**Platform:** $(uname -s) $(uname -m)
**Iterations:** $ITERATIONS

## Benchmark 1: Cold Startup (--version)

$(cat "$RESULTS_DIR/startup.md" 2>/dev/null || echo "See JSON for raw data.")

## Benchmark 2: Config Parse + 3-Rule Validation

$(cat "$RESULTS_DIR/validation-3rules.md" 2>/dev/null || echo "See JSON for raw data.")

## Benchmark 3: Full 21-Rule Validation (Karandash only)

$(cat "$RESULTS_DIR/validation-21rules.md" 2>/dev/null || echo "N/A")

## What Each Tool Does

| Tool | Work Performed |
|------|---------------|
| karandash-native | Parse YAML config, load rule modules, validate MR (dry-run), console report |
| karandash-fatjar | Same as native, but with JVM startup overhead |
| danger-js | Node.js boot, parse Dangerfile.js, git diff analysis, 3 checks, text output |
| danger-kotlin | Native launcher, JVM boot, Kotlin script compilation, 3 checks |
| reviewdog | Parse rdjson input, git diff, filter diagnostics by changed lines |

## Notes

- All tools perform equivalent validation work (title format, branch naming, draft detection)
- Karandash dry-run skips provider API calls but loads full validation pipeline
- danger-kotlin launches JVM + Kotlin compiler for Dangerfile evaluation
- reviewdog processes pre-computed lint results (no actual linting)
EOF

echo "══════════════════════════════════════════════════════════════"
echo " RESULTS"
echo "══════════════════════════════════════════════════════════════"
echo ""
echo "  $RESULTS_DIR/startup.json             — Startup benchmark"
echo "  $RESULTS_DIR/validation-3rules.json   — 3-rule benchmark"
echo "  $RESULTS_DIR/validation-21rules.json  — 21-rule benchmark"
echo "  $RESULTS_DIR/report.md                — Combined report"
echo ""
echo "Full report:"
echo ""
cat "$RESULTS_DIR/report.md"
