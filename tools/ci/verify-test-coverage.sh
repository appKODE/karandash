#!/usr/bin/env bash
# Coverage backstop: a production Gradle module with more than 5 Kotlin files under src/*Main and no
# Kotlin file under src/*Test (or src/test) is untested. Modules listed in verify-test-coverage.baseline
# are tolerated (pre-existing debt); any OTHER such module fails the check. A baselined module that gains
# tests is reported so its line can be dropped. Not production, so not scanned: build-logic (Gradle plugins),
# tools/examples and tools/templates (sample plugins, checked by their own builds), test-fixtures (test support).
#
#   bash tools/ci/verify-test-coverage.sh          # check against the baseline
#   bash tools/ci/verify-test-coverage.sh --list   # print every untested module (path, main file count)
set -euo pipefail
cd "$(dirname "$0")/../.."

BASELINE=tools/ci/verify-test-coverage.baseline
MIN_MAIN_FILES=5

untested_modules() {
    find . -name build.gradle.kts \
        -not -path './build.gradle.kts' -not -path '*/build/*' \
        -not -path './build-logic/*' -not -path './tools/examples/*' -not -path './tools/templates/*' \
        -not -path './test-fixtures/*' \
        -not -path '*/node_modules/*' -not -path './.gradle/*' |
        sed 's|^\./||; s|/build.gradle.kts$||' | sort |
        while read -r module; do
            [ -d "$module/src" ] || continue
            main=$(find "$module"/src \( -path '*Main/*' -o -path '*/src/main/*' \) -name '*.kt' 2>/dev/null | wc -l | tr -d ' ')
            tests=$(find "$module"/src \( -path '*Test/*' -o -path '*/src/test/*' \) -name '*.kt' 2>/dev/null | wc -l | tr -d ' ')
            if [ "$main" -gt "$MIN_MAIN_FILES" ] && [ "$tests" -eq 0 ]; then
                echo "$module $main"
            fi
        done
}

if [ "${1:-}" = "--list" ]; then
    untested_modules
    exit 0
fi

current=$(untested_modules | cut -d' ' -f1)
baseline=$(grep -v '^#' "$BASELINE" | sed '/^$/d' || true)

new=$(comm -23 <(echo "$current" | sed '/^$/d' | sort) <(echo "$baseline" | sort))
fixed=$(comm -13 <(echo "$current" | sed '/^$/d' | sort) <(echo "$baseline" | sort))

if [ -n "$fixed" ]; then
    echo "verify-test-coverage: now tested, remove from $BASELINE:"
    echo "$fixed" | sed 's/^/  /'
fi
if [ -n "$new" ]; then
    echo "verify-test-coverage: FAIL, module(s) with more than $MIN_MAIN_FILES main Kotlin files and no tests:"
    echo "$new" | sed 's/^/  /'
    echo "Add tests (commonTest by default) rather than a baseline entry."
    exit 1
fi
echo "verify-test-coverage: OK ($(echo "$baseline" | sed '/^$/d' | wc -l | tr -d ' ') baselined untested module(s))"
