#!/usr/bin/env bash
# Regression: kotlinx-datetime is the plain artifact, not the 0.6.x-compat one that keeps the deprecated
# kotlinx.datetime.Clock/Instant alive, and no source imports those types (kotlin.time.Clock/Instant replace them).
# cryptography stays at 0.6.0: Maven Central lists it as <latest> and <release>.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=datetime-deps
. tools/ci/lib.sh
TOML=gradle/libs.versions.toml
datetime=$(grep -o '^kotlinxDatetime = "[^"]*"' "$TOML" | sed 's/.*"\(.*\)"/\1/')
[ -n "$datetime" ] || fail "no kotlinxDatetime version in $TOML"
case "$datetime" in *compat*) fail "kotlinxDatetime is the compat artifact $datetime" ;; esac
legacy=$(git grep -nE 'kotlinx\.datetime\.(Clock|Instant)\b' -- '*.kt' '*.kts')
[ -z "$legacy" ] || fail "legacy kotlinx.datetime Clock/Instant use: $legacy"
echo "datetime-deps OK"
