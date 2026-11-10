#!/usr/bin/env bash
# Regression: the dead build pieces are wired: one posixMain dynlib, a compiling linuxArm64 target,
# the serialization reporter convention applied, one WASM alloc name, no unused test plugin in email-output.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=build-wiring
. tools/ci/lib.sh

[ -f core/platform/src/posixMain/kotlin/io/karandash/platform/NativeDynamicLibrary.posix.kt ] || fail "no posixMain dynlib"
copies=$(ls core/platform/src/*/kotlin/io/karandash/platform/NativeDynamicLibrary.*.kt | grep -E 'macos|linux' || true)
[ -z "$copies" ] || fail "per-OS dynlib copies remain: $copies"

grep -q 'linuxArm64()' build-logic/src/main/kotlin/karandash.kmp-library.gradle.kts || fail "kmp-library declares no linuxArm64 target"
grep -q 'karandash.linuxArm64=true' core/platform/gradle.properties || fail "core/platform does not opt into linuxArm64"
[ ! -d core/platform/src/linuxArm64Main ] || fail "linuxArm64Main still holds a private copy"

grep -rq 'karandash.kmp-serialization-reporter-module' --include=build.gradle.kts plugins || fail "kmp-serialization-reporter-module is applied by no module"

if grep -q '"karandash_alloc"' plugins/loader/src/commonMain/kotlin/io/karandash/loader/wasm/WasmAbi.kt; then
    fail "WasmAbi repeats the alloc export name"
fi

if grep -q 'mokkery' plugins/plugins-extended/outputs/email-output/build.gradle.kts; then
    fail "email-output applies mokkery without using it"
fi

[ -f core/platform/src/nativeMain/kotlin/io/karandash/platform/CaptureFile.kt ] || fail "no shared readCaptureFile"
echo "build-wiring OK"
