#!/usr/bin/env bash
# Negative self-test for the scan-only detectors selfcheck-build-wiring, selfcheck-orphan-scripts and selfcheck-shared-project-tests: each must pass on a copy of the tree and
# exit non-zero once a planted violation (stray script, per-OS dynlib copy, missing shared suite) is in that copy.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=DETECTORS-NEG
. tools/ci/lib.sh

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# expect <detector> <label> <mutation command run inside the copy>
expect() {
    local detector=$1 label=$2 mutate=$3 tree="$work/$2"
    mkdir -p "$tree"
    git ls-files -z --cached --others --exclude-standard | rsync -a --from0 --files-from=- ./ "$tree/" || fail "cannot copy the tree"
    (cd "$tree" && bash "tools/ci/selfcheck-$detector.sh" >/dev/null 2>&1) || fail "$detector fails on the clean copy"
    (cd "$tree" && eval "$mutate")
    if (cd "$tree" && bash "tools/ci/selfcheck-$detector.sh" >/dev/null 2>&1); then
        fail "$detector accepted the planted violation: $label"
    fi
    echo "$detector rejects $label"
}

expect orphan-scripts stray-script 'printf "#!/bin/sh\n" > tools/scripts/zz-orphan-selfcheck.sh'
expect build-wiring per-os-dynlib 'touch core/platform/src/nativeMain/kotlin/io/karandash/platform/NativeDynamicLibrary.macos.kt'
expect shared-project-tests missing-shared-suite 'rm projects/shared-tests/src/commonTest/kotlin/io/karandash/validators/ProjectRemoteExtendsTest.kt'
echo "DETECTORS-NEG OK"
